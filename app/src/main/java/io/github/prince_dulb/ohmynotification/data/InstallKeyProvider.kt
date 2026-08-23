package io.github.prince_dulb.ohmynotification.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

internal object InstallKeyProvider {
    private const val PREFERENCES_NAME = "omn-local-keys"
    private const val FINGERPRINT_KEY = "content-fingerprint-hmac-v1"

    fun contentFingerprintKey(context: Context): ByteArray {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.getString(FINGERPRINT_KEY, null)?.let { encoded ->
            return Base64.decode(encoded, Base64.NO_WRAP)
        }

        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        preferences.edit()
            .putString(FINGERPRINT_KEY, Base64.encodeToString(generated, Base64.NO_WRAP))
            .apply()
        return generated
    }
}
