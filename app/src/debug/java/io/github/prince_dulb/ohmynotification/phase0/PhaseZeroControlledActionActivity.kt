package io.github.prince_dulb.ohmynotification.phase0

import android.app.Activity
import android.os.Bundle
import java.io.OutputStream
import java.util.Properties

class PhaseZeroControlledActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val commandToken = intent.getStringExtra(EXTRA_COMMAND_TOKEN).orEmpty()
        val result = RuntimeActionRegistry.sendLatestForCreator(CONTROLLED_SOURCE_PACKAGE)
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
