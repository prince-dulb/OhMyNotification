package io.github.prince_dulb.ohmynotification.testsource;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;

import java.io.OutputStream;
import java.util.Properties;

public final class ControlledTargetJavaActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String caseId = getIntent().getStringExtra("omn.synthetic.case_id");
        String targetToken = getIntent().getStringExtra("omn.synthetic.target_token");
        TextView content = new TextView(this);
        content.setGravity(Gravity.CENTER);
        content.setTextSize(18f);
        content.setText(
                "OMN_TEST_ONLY_NOTIFICATION\n"
                        + "case=" + (caseId == null ? "missing-case" : caseId) + "\n"
                        + "target=" + (targetToken == null ? "missing-token" : targetToken)
        );
        setContentView(content);
        Properties result = new Properties();
        result.setProperty("status", "OPENED");
        result.setProperty("case_id", caseId == null ? "missing-case" : caseId);
        result.setProperty("target_token", targetToken == null ? "missing-token" : targetToken);
        result.setProperty("opened_at_epoch_millis", Long.toString(System.currentTimeMillis()));
        result.setProperty("opened_elapsed_nanos", Long.toString(SystemClock.elapsedRealtimeNanos()));
        try (OutputStream output = openFileOutput("controlled-target-result.properties", MODE_PRIVATE)) {
            result.store(output, null);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to persist controlled target result", failure);
        }
        finish();
    }
}
