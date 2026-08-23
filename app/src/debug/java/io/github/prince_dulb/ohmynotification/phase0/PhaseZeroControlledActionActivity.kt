package io.github.prince_dulb.ohmynotification.phase0

import android.app.Activity
import android.app.ActivityOptions
import android.os.Bundle
import java.io.OutputStream
import java.util.Properties

class PhaseZeroControlledActionActivity : Activity() {
    private var dispatched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onPostResume() {
        super.onPostResume()
        if (dispatched) {
            return
        }
        dispatched = true

        val commandToken = intent.getStringExtra(EXTRA_COMMAND_TOKEN).orEmpty()
        val activityOptions = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
        }
        val result = RuntimeActionRegistry.sendLatestForCreator(
            creatorPackage = CONTROLLED_SOURCE_PACKAGE,
            options = activityOptions.toBundle(),
        )
        val properties = Properties().apply {
            setProperty("command_token", commandToken)
            setProperty("status", result.status.name)
            setProperty("registry_size_after", result.registrySizeAfter.toString())
        }
        openFileOutput(RESULT_FILE_NAME, MODE_PRIVATE).use { output: OutputStream ->
            properties.store(output, null)
        }
        finish()
    }

    private companion object {
        const val CONTROLLED_SOURCE_PACKAGE = "io.github.prince_dulb.ohmynotification.test"
        const val EXTRA_COMMAND_TOKEN = "omn_command_token"
        const val RESULT_FILE_NAME = "phase0-controlled-action-result.properties"
    }
}
