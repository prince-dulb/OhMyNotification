package io.github.prince_dulb.ohmynotification.testsource

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class ControlledTargetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caseId = intent.getStringExtra(ControlledNotificationSource.EXTRA_CASE_ID) ?: "missing-case"
        val targetToken = intent.getStringExtra(ControlledNotificationSource.EXTRA_TARGET_TOKEN) ?: "missing-token"
        setContentView(
            TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                text = buildString {
                    appendLine(ControlledNotificationSource.MARKER)
                    appendLine("case=$caseId")
                    append("target=$targetToken")
                }
            },
        )
    }
}
