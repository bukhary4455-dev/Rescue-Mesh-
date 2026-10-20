package com.rescuemesh.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1: direct two-device messaging over Google Nearby Connections (BLE/Bluetooth/Wi-Fi Direct, no internet).
 * Only saved contacts are auto-connected. No multi-hop relay yet.
 */
object Mesh {
    private const val SERVICE = "com.rescuemesh.app.v1"
    private lateinit var app: Context
    private lateinit var prefs: android.content.SharedPreferences
    lateinit var db: AppDb
    lateinit var myId: String
    lateinit var myPub: ByteArray
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val name = MutableStateFlow("")
    val running = MutableStateFlow(false)
    val nearby = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap()) // endpointId -> (name, userId)
    val links = MutableStateFlow<Map<String, String>>(emptyMap()) // userId -> endpointId (real open connections)
    val error = MutableStateFlow<String?>(null)
    private val epToUser = ConcurrentHashMap<String, String>()
    private val handshake = ConcurrentHashMap<String, String>()
    private val client get() = Nearby.getConnectionsClient(app)

    fun init(c: Context) {
        app = c.applicationContext
        prefs = app.getSharedPreferences("p", 0)
        db = AppDb.get(app)
        name.value = prefs.getString("name", "") ?: ""
        myPub = Crypto.publicKey()
        myId = Crypto.userId(myPub)
    }

    fun setName(n: String) { prefs.edit().putString("name", n.trim()).apply(); name.value = n.trim() }
    fun myQr() = "rescuemesh://c?n=${Uri.encode(name.value)}&k=" +
        Base64.encodeToString(myPub, Base64.URL_SAFE or Base64.NO_WRAP)
    private fun info() = "${name.value.replace("|", "")}|$myId"
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun start() {
        if (running.value) return
        client.startAdvertising(info(), SERVICE, connCb, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { error.value = "Advertising failed: ${it.message}" }
        client.startDiscovery(SERVICE, discCb, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { error.value = "Discovery failed: ${it.message}. Check Bluetooth/Wi-Fi are on." }
        running.value = true
    }

    fun stop() {
        client.stopAdvertising(); client.stopDiscovery(); client.stopAllEndpoints()
        nearby.value = emptyMap(); links.value = emptyMap(); epToUser.clear(); handshake.clear()
        running.value = false
    }

    suspend fun addContact(raw: String): String {
        val u = Uri.parse(raw)
        if (u.scheme != "rescuemesh") return "Not a RescueMesh code."
        val n = u.getQueryParameter("n") ?: return "Invalid code."
        val pub = try { Base64.decode(u.getQueryParameter("k") ?: "", Base64.URL_SAFE or Base64.NO_WRAP) } catch (e: Exception) { return "Invalid code." }
        if (!Crypto.isValidPublicKey(pub)) return "Invalid key in code."
        val id = Crypto.userId(pub)
        if (id == myId) return "That is your own code."
        db.contacts().upsert(Contact(id, n.take(40), Base64.encodeToString(pub, Base64.NO_WRAP)))
        return "Added $n"
    }

    suspend fun send(peer: String, text: String) {
        val m = Msg(UUID.randomUUID().toString(), peer, true, text, System.currentTimeMillis(), "QUEUED")
        db.msgs().insert(m)
        trySend(m)
    }

    suspend fun flush(peer: String) = db.msgs().pendingFor(peer).forEach { trySend(it) }

    private suspend fun trySend(m: Msg) {
        val ep = links.value[m.peerId] ?: return
        val c = db.contacts().get(m.peerId) ?: return
        val ct = Crypto.encrypt(dec(c.pubKey), "${m.id}|$myId|${m.peerId}".toByteArray(), m.text.toByteArray())
        val j = JSONObject().put("t", "msg").put("id", m.id).put("from", myId).put("to", m.peerId)
            .put("c", Base64.encodeToString(ct, Base64.NO_WRAP))
        client.sendPayload(ep, Payload.fromBytes(j.toString().toByteArray()))
            .addOnSuccessListener { scope.launch { db.msgs().setSent(m.id) } }
    }

    private fun sendRaw(ep: String, s: String) { client.sendPayload(ep, Payload.fromBytes(s.toByteArray())) }

    private suspend fun handle(ep: String, uid: String, b: ByteArray) {
        val j = try { JSONObject(String(b)) } catch (e: Exception) { return }
        when (j.optString("t")) {
            "msg" -> {
                val mid = j.optString("id"); val from = j.optString("from")
                if (from != uid || j.optString("to") != myId || mid.isEmpty()) return
                val c = db.contacts().get(uid) ?: return
                val plain = try {
                    Crypto.decrypt(dec(c.pubKey), "$mid|$from|$myId".toByteArray(), Base64.decode(j.getString("c"), Base64.NO_WRAP))
                } catch (e: Exception) { return } // tampered / wrong key: drop
                db.msgs().insert(Msg(mid, uid, false, String(plain), System.currentTimeMillis(), "RECEIVED")) // IGNORE = dedupe
                sendRaw(ep, JSONObject().put("t", "ack").put("id", mid).toString())
            }
            "ack" -> db.msgs().delivered(j.optString("id"), uid)
        }
    }

    private val plCb = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val b = p.asBytes() ?: return
            val uid = epToUser[id] ?: handshake[id] ?: return
            scope.launch { handle(id, uid, b) }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, ci: ConnectionInfo) {
            val uid = ci.endpointName.split("|").getOrNull(1)
            scope.launch {
                if (uid != null && db.contacts().get(uid) != null) {
                    handshake[id] = uid; client.acceptConnection(id, plCb)
                } else client.rejectConnection(id) // strangers are never connected
            }
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            val uid = handshake.remove(id) ?: return
            if (r.status.isSuccess) {
                epToUser[id] = uid; links.update { it + (uid to id) }
                scope.launch { db.contacts().seen(uid, System.currentTimeMillis()); flush(uid) }
            }
        }
        override fun onDisconnected(id: String) { epToUser.remove(id)?.let { u -> links.update { it - u } } }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, i: DiscoveredEndpointInfo) {
            val p = i.endpointName.split("|"); if (p.size != 2) return
            nearby.update { it + (id to (p[0] to p[1])) }
            scope.launch {
                if (db.contacts().get(p[1]) != null) db.contacts().seen(p[1], System.currentTimeMillis())
                // Only the device with the smaller ID initiates, to avoid both sides colliding.
                if (db.contacts().get(p[1]) != null && myId < p[1] && !links.value.containsKey(p[1]))
                    client.requestConnection(info(), id, connCb)
            }
        }
        override fun onEndpointLost(id: String) { nearby.update { it - id } }
    }
}
