package dev.bookah.photos

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/** What came of asking the reader to unlock. */
enum class UnlockResult { Success, Cancelled, NoLock }

/** Asks the reader to unlock with the phone's own screen lock (fingerprint, face or PIN). Behind an interface so tests can use a fake. */
fun interface Unlocker {
    fun unlock(title: String, subtitle: String?, onResult: (UnlockResult) -> Unit)
}

/**
 * The phone's own prompt (Android's BiometricPrompt): a fingerprint or face that the phone counts as good enough, or the PIN, pattern or password. The app never
 * sees any of them. If the phone has no screen lock at all, there is nothing to unlock with: [UnlockResult.NoLock].
 */
class PlatformUnlocker(private val activity: Activity) : Unlocker {
    override fun unlock(title: String, subtitle: String?, onResult: (UnlockResult) -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val manager = activity.getSystemService(BiometricManager::class.java)
        if (manager == null || manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(UnlockResult.NoLock)
            return
        }
        val prompt = BiometricPrompt.Builder(activity).setTitle(title).apply { subtitle?.let { setSubtitle(it) } }.setAllowedAuthenticators(allowed).build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(UnlockResult.Success)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onResult(UnlockResult.Cancelled)
        })
    }
}
