package com.dhruv.blackout

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Phase 0 spike test 3: transparent host for BiometricPrompt. The cover is never touched
 * unless authentication succeeds, so cancelling the prompt leaves the cover up.
 */
class AuthActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.i(BlackoutService.TAG, "auth succeeded -> removing cover")
                    BlackoutService.instance?.hideCover()
                    finish()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.i(BlackoutService.TAG, "auth error $errorCode: $errString (cover stays)")
                    finish()
                }

                override fun onAuthenticationFailed() {
                    Log.i(BlackoutService.TAG, "auth attempt failed (not a match)")
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        Log.i(BlackoutService.TAG, "showing BiometricPrompt")
        prompt.authenticate(info)
    }
}
