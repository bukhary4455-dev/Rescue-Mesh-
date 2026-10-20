package com.rescuemesh.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** EC P-256 identity key lives in Android Keystore (never exported). Contacts share ECDH secrets; AES-256-GCM encrypts. */
object Crypto {
    private const val ALIAS = "rescuemesh_identity"
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun publicKey(): ByteArray {
        if (!ks.containsAlias(ALIAS)) {
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            g.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build()
            )
            g.generateKeyPair()
        }
        return ks.getCertificate(ALIAS).publicKey.encoded
    }

    fun userId(pub: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(pub).take(10).joinToString("") { "%02x".format(it) }

    fun isValidPublicKey(pub: ByteArray): Boolean = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub)); true
    } catch (e: Exception) { false }

    private fun sharedKey(peerPub: ByteArray): SecretKeySpec {
        val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub))
        val ka = KeyAgreement.getInstance("ECDH", "AndroidKeyStore")
        ka.init(ks.getKey(ALIAS, null) as PrivateKey)
        ka.doPhase(pk, true)
        val md = MessageDigest.getInstance("SHA-256")
        md.update("RescueMesh-v1".toByteArray())
        return SecretKeySpec(md.digest(ka.generateSecret()), "AES")
    }

    fun encrypt(peerPub: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sharedKey(peerPub))
        c.updateAAD(aad)
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(peerPub: ByteArray, aad: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, sharedKey(peerPub), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        c.updateAAD(aad)
        return c.doFinal(data, 12, data.size - 12)
    }
}
