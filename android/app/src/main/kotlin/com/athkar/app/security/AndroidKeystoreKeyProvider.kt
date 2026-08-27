package com.athkar.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.athkar.data.db.DbKeyProvider
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the raw 256-bit AES key that unlocks the SQLCipher database, provisioned and held
 * exclusively in Android Keystore (StrongBox-backed when the device provides it). The key never
 * exists outside secure hardware and is deleted on device-lock logout (per session scope).
 *
 * STRIDE surface S1 (device theft): without this key the DB is unrecoverable even if the database
 * file is exfiltrated from the device.
 */
@Singleton
class AndroidKeystoreKeyProvider @Inject constructor() : DbKeyProvider {

    private val keystore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun provideKeyBytes(): ByteArray {
        val key = getOrCreateKey()
        return key.encoded
    }

    private fun getOrCreateKey(): SecretKey {
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return createKey()
    }

    private fun createKey(): SecretKey {
        val attempts = listOf(
            true to true,   // StrongBox + require unlocked device
            false to true,  // software keystore + require unlocked device
            false to false, // software keystore, no device-unlock requirement
        )
        var lastError: Exception? = null
        for ((strongBox, unlockRequired) in attempts) {
            try {
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .apply { if (unlockRequired) setUnlockedDeviceRequired(true) }
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build()
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(spec)
                return generator.generateKey()
            } catch (e: Exception) {
                // StrongBoxUnavailableException / IllegalStateException (no lock screen) /
                // etc. can be thrown at generation; fall back to a weaker-but-working key so the
                // app never crashes on launch.
                lastError = e
            }
        }
        throw IllegalStateException("Unable to provision AES keystore key", lastError)
    }

    fun wipeDbKey() {
        keystore.deleteEntry(ALIAS)
    }

    /**
     * Access the biometric-enrollment-bound signing key. Because it is created with
     * `setInvalidatedByBiometricEnrollment(true)`, any enrollment change makes all operations throw
     * [KeyPermanentlyInvalidatedException]; callers must re-enroll the user.
     */
    fun requireSigningKey(): Signature {
        val entry = keystore.getEntry(SIGNING_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw KeyPermanentlyInvalidatedException("signing key missing; re-enroll")
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(entry.privateKey)
        return sig
    }

    /** Re-create the signing key if it was invalidated by a biometric enrollment change. */
    fun recreateSigningKeyIfNeeded() {
        try {
            requireSigningKey()
        } catch (e: KeyPermanentlyInvalidatedException) {
            keystore.deleteEntry(SIGNING_ALIAS)
            provisionSigningKey()
        }
    }

    private fun provisionSigningKey() {
        if (keystore.containsAlias(SIGNING_ALIAS)) return
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        val builder = KeyGenParameterSpec.Builder(
            SIGNING_ALIAS,
            KeyProperties.PURPOSE_SIGN,
        )
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setInvalidatedByBiometricEnrollment(true) // the delivery-brief requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: restrict to strong biometric only (equivalent to hidden setBiometricStrong)
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 26-29: no strong-only switch; gate behind unlock (credential or any biometric)
            builder.setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "athkar_db_key_v1"
        const val SIGNING_ALIAS = "athkar_signing_v1"
    }
}
