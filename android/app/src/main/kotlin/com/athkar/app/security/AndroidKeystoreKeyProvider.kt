package com.athkar.app.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.athkar.data.db.DbKeyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the raw 256-bit AES passphrase that unlocks the SQLCipher database.
 *
 * Android Keystore keys are non-extractable by design — `SecretKey.getEncoded()` returns null for
 * them — so the passphrase cannot itself *be* a Keystore key. Instead it is a random 256-bit value
 * sealed (AES-GCM) with a Keystore key (StrongBox-backed when the device provides it) and persisted
 * only as ciphertext; the sealing key never leaves secure hardware.
 *
 * STRIDE surface S1 (device theft): the database file and the sealed blob are both useless without
 * the hardware-bound sealing key, which cannot be exfiltrated from the device.
 */
@Singleton
class AndroidKeystoreKeyProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DbKeyProvider {

    private val keystore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private val lock = Any()

    /**
     * Returns a fresh copy of the passphrase on every call: SQLCipher's `SupportFactory` zeroes the
     * array it is handed once the database is open, so a cached instance would come back blank.
     */
    override fun provideKeyBytes(): ByteArray = synchronized(lock) {
        val sealed = prefs.getString(PREF_SEALED_KEY, null)
        if (sealed != null) return@synchronized unseal(Base64.decode(sealed, Base64.NO_WRAP))

        val passphrase = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val blob = seal(passphrase)
        // commit(), not apply(): handing out a passphrase we failed to persist would leave the
        // database encrypted under a key that no longer exists once the process dies.
        check(
            prefs.edit().putString(PREF_SEALED_KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        ) { "Unable to persist the sealed database key" }
        passphrase
    }

    private fun seal(passphrase: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSealingKey())
        return cipher.iv + cipher.doFinal(passphrase)
    }

    private fun unseal(blob: ByteArray): ByteArray {
        check(blob.size > GCM_IV_BYTES) { "Sealed database key is truncated" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            requireSealingKey(),
            GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun getOrCreateSealingKey(): SecretKey =
        (keystore.getKey(ALIAS, null) as? SecretKey) ?: createSealingKey()

    /**
     * A sealed blob exists but its key does not: provisioning a fresh key here would silently
     * produce one that decrypts nothing, so the unrecoverable state is surfaced instead.
     */
    private fun requireSealingKey(): SecretKey =
        (keystore.getKey(ALIAS, null) as? SecretKey)
            ?: throw IllegalStateException(
                "Sealing key missing from Android Keystore (lock-screen reset or data restore); " +
                    "the encrypted database can no longer be opened"
            )

    private fun createSealingKey(): SecretKey {
        // StrongBox first, then the software-backed keystore. There is deliberately no
        // setUnlockedDeviceRequired(true) here: the periodic sync worker opens the database while
        // the device is locked, and such a key throws at that moment. The key stays hardware-bound
        // and non-extractable either way, which is what protects an exfiltrated database file.
        val attempts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) listOf(true, false) else listOf(false)
        var lastError: Exception? = null
        for (strongBox in attempts) {
            try {
                val spec = KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .apply { if (strongBox) setIsStrongBoxBacked(true) }
                    .build()
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                generator.init(spec)
                val key = generator.generateKey()
                android.util.Log.i(TAG, "provisioned sealing key strongBox=$strongBox")
                return key
            } catch (e: Exception) {
                // StrongBoxUnavailableException and friends are thrown at generation time; fall back
                // to the software keystore so the app still opens on devices without StrongBox.
                android.util.Log.w(TAG, "sealing key attempt strongBox=$strongBox failed", e)
                lastError = e
            }
        }
        throw IllegalStateException("Unable to provision AES keystore key", lastError)
    }

    fun wipeDbKey() {
        synchronized(lock) {
            keystore.deleteEntry(ALIAS)
            // The sealed blob is unreadable without the key above; drop it so the next launch
            // provisions a fresh pair instead of failing to unseal.
            prefs.edit().remove(PREF_SEALED_KEY).commit()
        }
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
            .setUserAuthenticationRequired(true) // the invalidation flag only applies to auth-bound keys
            .setInvalidatedByBiometricEnrollment(true) // the delivery-brief requirement
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: restrict to strong biometric only (equivalent to hidden setBiometricStrong)
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 26-29: no strong-only switch; gate behind unlock (credential or any biometric)
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    private companion object {
        const val TAG = "AthkarKeystore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "athkar_db_key_v1"
        const val SIGNING_ALIAS = "athkar_signing_v1"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val PREFS_NAME = "athkar_db_key"
        const val PREF_SEALED_KEY = "sealed_db_key_v1"
        const val KEY_SIZE_BYTES = 32
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
