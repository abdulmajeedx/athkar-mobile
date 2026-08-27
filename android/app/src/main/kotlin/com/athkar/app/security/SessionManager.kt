package com.athkar.app.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates app locking:
 *   - Fingerprint/Face (BIOMETRIC_STRONG) via BiometricPrompt.
 *   - 6-digit PIN fallback with a 30s lockout after 5 failed attempts.
 *   - Auto-lock after 5 minutes of inactivity and immediately on backgrounding (the Activity also
 *     hides the task snapshot — see MainActivity onPause/secure flag).
 *   - A signing key allocated with `setInvalidatedByBiometricEnrollment(true)` so it is
 *     automatically destroyed when the user adds/removes a biometric (delivery brief) — the client
 *     must re-sign / re-enroll after any biometric change.
 */
@Singleton
class SessionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyProvider: AndroidKeystoreKeyProvider,
) {
    private val biometricManager = BiometricManager.from(context)
    private var isUnlocked = false
    private var lastActivityMillis = System.currentTimeMillis()
    private var pinFailures = 0
    private var lockoutUntilMillis = 0L

    val isBiometricEnrolled: Boolean
        get() = when (biometricManager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS, BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> true
            else -> false
        }

    /** Called on any user interaction to keep the session alive. */
    fun recordActivity() {
        lastActivityMillis = System.currentTimeMillis()
    }

    fun shouldAutoLock(): Boolean =
        System.currentTimeMillis() - lastActivityMillis > AUTO_LOCK_IDLE_MS

    fun onBackground() {
        lock()
    }

    fun lock() {
        isUnlocked = false
    }

    fun isUnlocked(): Boolean = isUnlocked

    /**
     * Attempt to unlock. Returns true if a biometric prompt should be shown; the caller then drives
     * [BiometricPrompt] and calls [onBiometricSuccess] / [onFailure].
     */
    fun requestUnlock(): Boolean {
        if (isUnlocked) return false
        // Re-enrolled biometric invalidates the signing key; detect and force re-enrollment.
        val invalid = try {
            keyProvider.requireSigningKey()
            false
        } catch (e: KeyPermanentlyInvalidatedException) {
            true
        } catch (e: Exception) {
            false
        }
        if (invalid) reEnrollRequired = true
        return isBiometricEnrolled
    }

    var reEnrollRequired: Boolean = false
        private set

    fun onBiometricSuccess() {
        isUnlocked = true
        pinFailures = 0
        reEnrollRequired = false
        keyProvider.recreateSigningKeyIfNeeded()
    }

    fun onFailure() {
        pinFailures++
        if (pinFailures >= 5) {
            lockoutUntilMillis = System.currentTimeMillis() + LOCKOUT_MS
            pinFailures = 0
        }
    }

    val isLockedOut: Boolean get() = System.currentTimeMillis() < lockoutUntilMillis

    fun onPinVerified() {
        isUnlocked = true
        pinFailures = 0
    }

    private companion object {
        const val BIOMETRIC_STRONG = BiometricManager.Authenticators.BIOMETRIC_STRONG
        const val AUTO_LOCK_IDLE_MS = 5L * 60_000L
        const val LOCKOUT_MS = 30_000L
    }
}
