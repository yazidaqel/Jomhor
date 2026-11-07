package ma.jomhor.scanner

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors

/**
 * The device key of docs/04-api.md §Gate auth: ECDSA P-256 in Android Keystore, generated on the device with
 * PURPOSE_SIGN only. Keystore keys are non-exportable: the private key never leaves the secure hardware (or
 * the Keystore daemon on devices without it); only the public key and signatures come out.
 *
 * Methods (Dart side: lib/core/device_key.dart):
 * - `generate` → SPKI DER public key of a new key; replaces any previous key (re-enrolment).
 * - `publicKey` → SPKI DER, or null when no key exists.
 * - `sign` (bytes) → DER ECDSA signature, SHA-256.
 */
class DeviceKey : MethodChannel.MethodCallHandler {
    companion object {
        const val CHANNEL = "ma.jomhor.scanner/device_key"
        private const val ALIAS = "jomhor_device_key"
        private const val KEYSTORE = "AndroidKeyStore"
    }

    // Keystore calls can block (hardware-backed keys); keep them off the platform thread.
    private val worker = Executors.newSingleThreadExecutor()

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        worker.execute {
            try {
                val value: Any? =
                    when (call.method) {
                        "generate" -> generate()
                        "publicKey" -> publicKey()
                        "sign" -> sign(call.arguments as ByteArray)
                        else -> return@execute result.notImplemented()
                    }
                result.success(value)
            } catch (e: Exception) {
                // The message names the failure only; no key material is ever in it.
                result.error("DEVICE_KEY", e.javaClass.simpleName, null)
            }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generate(): ByteArray {
        keyStore().deleteEntry(ALIAS)
        val spec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(spec)
        // X.509 SubjectPublicKeyInfo DER.
        return generator.generateKeyPair().public.encoded
    }

    private fun publicKey(): ByteArray? = keyStore().getCertificate(ALIAS)?.publicKey?.encoded

    private fun sign(data: ByteArray): ByteArray {
        val key = keyStore().getKey(ALIAS, null) as? PrivateKey ?: throw IllegalStateException("no device key")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
        }
    }
}
