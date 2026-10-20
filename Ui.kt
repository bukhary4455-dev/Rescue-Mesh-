package com.rescuemesh.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RescueTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFFF6B5E))
        else lightColorScheme(primary = Color(0xFFC62828)),
        content = content
    )
}

private fun qr(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    for (x in 0 until size) for (y in 0 until size)
        bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

@Composable
fun RescueApp() {
    val name by Mesh.name.collectAsState()
    var route by remember { mutableStateOf("home") }
    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            name.isBlank() -> Setup()
            route == "contacts" -> { BackHandler { route = "home" }; Contacts { route = it } }
            route.startsWith("chat:") -> { BackHandler { route = "contacts" }; Chat(route.removePrefix("chat:")) }
            else -> Home { route = it }
        }
    }
}

@Composable
fun Setup() {
    var n by remember { mutableStateOf("") }
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("RescueMesh", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("Stay Connected When Networks Fail.")
        Text("RescueMesh sends messages directly between nearby phones that also run RescueMesh. No internet is needed, " +
            "but range is limited (roughly tens of meters) and delivery is not guaranteed. It does not replace emergency services.")
        OutlinedTextField(n, { n = it.take(30) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ Mesh.setName(n) }, enabled = n.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

@Composable
fun Home(go: (String) -> Unit) {
    val running by Mesh.running.collectAsState()
    val nearby by Mesh.nearby.collectAsState()
    val links by Mesh.links.collectAsState()
    val err by Mesh.error.collectAsState()
    val pending by Mesh.db.msgs().pendingCount().collectAsState(0)
    val delivered by Mesh.db.msgs().deliveredCount().collectAsState(0)
    val contacts by Mesh.db.contacts().all().collectAsState(emptyList())
    var showQr by remember { mutableStateOf(false) }
    val perms = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) else add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) Mesh.start()
        else Mesh.error.value = "Permission denied. RescueMesh needs 'Nearby devices' access to find other phones."
    }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("RescueMesh", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Offline Mode Active", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !running -> "Not searching. Start to look for nearby contacts."
                            links.isNotEmpty() -> "Linked to ${links.size} contact(s) right now."
                            else -> "Searching… no contact linked yet."
                        }
                    )
                    Text("Pending: $pending   Delivered: $delivered")
                    Button(
                        { if (running) Mesh.stop() else launcher.launch(perms) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (running) "Stop searching" else "Start searching") }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ go("contacts") }, Modifier.weight(1f)) { Text("Contacts (${contacts.size})") }
                OutlinedButton({ showQr = !showQr }, Modifier.weight(1f)) { Text(if (showQr) "Hide my QR" else "My QR code") }
            }
        }
        if (showQr) item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val bmp = remember { qr(Mesh.myQr()) }
                Image(bmp.asImageBitmap(), "My contact QR code", Modifier.size(240.dp))
                Text("ID: ${Mesh.myId}", style = MaterialTheme.typography.labelMedium)
                Text("Contacts must scan this in person to be trusted.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Nearby RescueMesh devices", style = MaterialTheme.typography.titleMedium) }
        if (nearby.isEmpty()) item { Text(if (running) "None found yet. Keep phones close and Bluetooth/Wi-Fi on." else "Start searching to discover devices.") }
        items(nearby.values.toList()) { (n, id) ->
            val known = contacts.any { it.userId == id }
            Text("$n — " + when { links.containsKey(id) -> "linked"; known -> "contact, connecting"; else -> "not a contact (ignored)" })
        }
        item {
            Text("Phase 1: direct messaging between saved contacts only. Searching runs while the app is open; there is no " +
                "multi-hop relay, SOS or location sharing yet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun Contacts(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts by Mesh.db.contacts().all().collectAsState(emptyList())
    val links by Mesh.links.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Contacts", style = MaterialTheme.typography.headlineMedium) }
        item {
            Button({
                GmsBarcodeScanning.getClient(ctx, GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
                    .startScan()
                    .addOnSuccessListener { b -> scope.launch { msg = Mesh.addContact(b.rawValue ?: "") } }
                    .addOnFailureListener { msg = "Scan cancelled or unavailable (needs Google Play services)." }
            }, Modifier.fillMaxWidth()) { Text("Scan a contact's QR code") }
            msg?.let { Text(it) }
        }
        if (contacts.isEmpty()) item { Text("No contacts yet. Open 'My QR code' on the other phone and scan it.") }
        items(contacts) { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Text("ID ${c.userId}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        when {
                            links.containsKey(c.userId) -> "Linked now"
                            c.lastSeen > 0 -> "Last seen nearby ${hm(c.lastSeen)}"
                            else -> "Not seen nearby yet"
                        }
                    )
                    Row {
                        TextButton({ go("chat:${c.userId}") }) { Text("Chat") }
                        TextButton({ scope.launch { Mesh.db.msgs().clear(c.userId); Mesh.db.contacts().delete(c.userId) } }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
fun Chat(id: String) {
    val scope = rememberCoroutineScope()
    val contact by remember(id) { Mesh.db.contacts().flow(id) }.collectAsState(null)
    val msgs by remember(id) { Mesh.db.msgs().chat(id) }.collectAsState(emptyList())
    val links by Mesh.links.collectAsState()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.lastIndex) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(contact?.name ?: "Contact", style = MaterialTheme.typography.titleLarge)
        Text(if (links.containsKey(id)) "Linked now" else "Not linked — messages are saved and sent when this contact is nearby.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(msgs, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(m.text)
                            Text(hm(m.ts) + if (m.outgoing) " · " + when (m.status) {
                                "QUEUED" -> "Queued, waiting for contact"
                                "SENT" -> "Sent to nearby device (not confirmed)"
                                else -> "Delivered ✓"
                            } else "", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        if (msgs.any { it.outgoing && it.status != "DELIVERED" })
            TextButton({ scope.launch { Mesh.flush(id) } }) { Text("Retry pending") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Message") })
            Button({ val t = text.trim(); text = ""; scope.launch { Mesh.send(id, t) } }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }
}
