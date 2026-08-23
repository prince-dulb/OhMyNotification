package io.github.prince_dulb.ohmynotification.testsource;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public final class ControlledNotificationCommandActivity extends Activity {
    private static final String DATASET_ID = "notification-fields-v1";
    private static final String RESULT_FILE_NAME = "controlled-notification-result.properties";
    private static final String RESULT_STATUS = "status";
    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_OK = "OK";
    private static final String STATUS_FAILURE = "FAILURE";
    private static final String EXTRA_OPERATION = "omn_operation";
    private static final String EXTRA_CASE_ID = "omn_case_id";
    private static final String EXTRA_COMMAND_TOKEN = "omn_command_token";
    private static final String OPERATION_PUBLISH = "publish";
    private static final String OPERATION_UPDATE = "update";
    private static final String OPERATION_REMOVE = "remove";
    private static final String OPERATION_OPEN = "open";
    private static final String DEFAULT_CASE_ID = "action";
    private static final String RESULT_ACTIVE = "active";
    private static final String RESULT_TITLE_CONTAINS_MARKER = "title_contains_marker";
    private static final String RESULT_TITLE_ENDS_WITH_UPDATE = "title_ends_with_update";
    private static final String RESULT_HAS_CONTENT_INTENT = "has_content_intent";
    private static final String RESULT_ACTION_COUNT = "action_count";
    private static final String RESULT_TARGET_OPENED = "target_opened";
    private static final String RESULT_COMMAND_TOKEN = "command_token";
    private static final String TARGET_RESULT_FILE_NAME = "controlled-target-result.properties";
    private static final String EXTRA_FIXTURE_POSTED_AT = "omn.synthetic.fixture_posted_at_epoch_millis";
    private static final String MARKER = "OMN_TEST_ONLY_NOTIFICATION";
    private static final String CHANNEL_ID = "omn_phase0_controlled_source";
    private static final String NOTIFICATION_TAG = MARKER;
    private static final int NOTIFICATION_ID = 0x4F4D4E;
    private static final int REQUEST_CODE = 0x504830;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Properties result = new Properties();
        String commandToken = getIntent().getStringExtra(EXTRA_COMMAND_TOKEN);
        if (commandToken == null) {
            commandToken = "MISSING";
        }
        result.setProperty(RESULT_STATUS, STATUS_STARTED);
        result.setProperty(RESULT_COMMAND_TOKEN, commandToken);
        writeResult(result);
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) {
                throw new IllegalStateException("NotificationManager unavailable");
            }
            String operation = getIntent().getStringExtra(EXTRA_OPERATION);
            String caseId = getIntent().getStringExtra(EXTRA_CASE_ID);
            if (caseId == null || caseId.isBlank()) {
                caseId = DEFAULT_CASE_ID;
            }
            boolean targetOpened = false;
            if (OPERATION_PUBLISH.equals(operation) || OPERATION_UPDATE.equals(operation)) {
                publish(manager, caseId, OPERATION_UPDATE.equals(operation));
            } else if (OPERATION_REMOVE.equals(operation)) {
                manager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
            } else if (OPERATION_OPEN.equals(operation)) {
                targetOpened = openActiveTarget(manager);
            } else {
                throw new IllegalArgumentException("Unsupported controlled notification operation");
            }

            Notification active = awaitExpectedState(manager, operation);
            result.clear();
            result.setProperty(RESULT_STATUS, STATUS_OK);
            result.setProperty(RESULT_COMMAND_TOKEN, commandToken);
            result.setProperty(RESULT_ACTIVE, Boolean.toString(active != null));
            CharSequence title = active == null
                    ? null
                    : active.extras.getCharSequence(Notification.EXTRA_TITLE);
            result.setProperty(
                    RESULT_TITLE_CONTAINS_MARKER,
                    Boolean.toString(title != null && title.toString().contains(MARKER))
            );
            result.setProperty(
                    RESULT_TITLE_ENDS_WITH_UPDATE,
                    Boolean.toString(title != null && title.toString().endsWith(" · update"))
            );
            result.setProperty(
                    RESULT_HAS_CONTENT_INTENT,
                    Boolean.toString(active != null && active.contentIntent != null)
            );
            result.setProperty(
                    RESULT_ACTION_COUNT,
                    Integer.toString(active == null || active.actions == null ? 0 : active.actions.length)
            );
            result.setProperty(RESULT_TARGET_OPENED, Boolean.toString(targetOpened));
        } catch (Throwable failure) {
            result.clear();
            result.setProperty(RESULT_COMMAND_TOKEN, commandToken);
            result.setProperty(
                    RESULT_STATUS,
                    STATUS_FAILURE + ":" + failure.getClass().getSimpleName()
            );
        }
        writeResult(result);
        finish();
    }

    private void publish(NotificationManager manager, String caseId, boolean update) throws Exception {
        JSONObject fixture = loadFixture(caseId);
        ensureChannel(manager);
        String title = fixture.isNull("title") ? "title-missing" : fixture.getString("title");
        String displayTitle = MARKER + " · " + title + " · " + (update ? "update" : "publish");
        String displayText = readText(fixture);
        JSONObject action = fixture.optJSONObject("action");
        String targetToken = action == null ? null : action.optString("targetToken", null);

        Intent targetIntent = new Intent();
        targetIntent.setClassName(
                getPackageName(),
                "io.github.prince_dulb.ohmynotification.testsource.ControlledTargetJavaActivity"
        );
        targetIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        targetIntent.putExtra("omn.synthetic.case_id", caseId);
        targetIntent.putExtra(
                "omn.synthetic.target_token",
                targetToken == null ? "OMN_SYNTHETIC_NO_ACTION" : targetToken
        );
        PendingIntent target = PendingIntent.getActivity(
                this,
                REQUEST_CODE,
                targetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Bundle syntheticExtras = new Bundle();
        syntheticExtras.putLong(EXTRA_FIXTURE_POSTED_AT, fixture.getLong("postedAtEpochMillis"));
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(displayTitle)
                .setContentText(displayText)
                .setSubText(fixture.getString("sourcePackage"))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(target)
                .setAutoCancel(false)
                .setOnlyAlertOnce(update)
                .setOngoing(false)
                .addExtras(syntheticExtras);
        if ("big_text".equals(fixture.getString("styleHint"))) {
            builder.setStyle(new Notification.BigTextStyle().bigText(displayText));
        }
        if (targetToken != null) {
            builder.addAction(
                    new Notification.Action.Builder(
                            Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                            "Open synthetic target",
                            target
                    ).build()
            );
        }
        manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
    }

    private JSONObject loadFixture(String caseId) throws Exception {
        StringBuilder content = new StringBuilder();
        try (
                InputStream input = getAssets().open(DATASET_ID + "/dataset.json");
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8)
                )
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append('\n');
            }
        }
        JSONArray cases = new JSONObject(content.toString()).getJSONArray("cases");
        for (int index = 0; index < cases.length(); index += 1) {
            JSONObject candidate = cases.getJSONObject(index);
            if (caseId.equals(candidate.getString("caseId"))) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown synthetic notification case");
    }

    private String readText(JSONObject fixture) throws Exception {
        JSONObject generator = fixture.optJSONObject("textGenerator");
        if (generator != null) {
            return generator.getString("character").repeat(generator.getInt("length"));
        }
        return fixture.isNull("text")
                ? "OMN_SYNTHETIC_TEXT_MISSING"
                : fixture.getString("text");
    }

    private void ensureChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OMN Phase 0 controlled source",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Test-only synthetic notifications; never shipped in production");
        manager.createNotificationChannel(channel);
    }

    private Notification findActive(NotificationManager manager) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (notification.getId() == NOTIFICATION_ID
                    && NOTIFICATION_TAG.equals(notification.getTag())) {
                return notification.getNotification();
            }
        }
        return null;
    }

    private Notification awaitExpectedState(NotificationManager manager, String operation) {
        long deadline = SystemClock.elapsedRealtime() + 2_000L;
        Notification active;
        do {
            active = findActive(manager);
            if (OPERATION_REMOVE.equals(operation) && active == null) {
                return null;
            }
            if (OPERATION_PUBLISH.equals(operation) && active != null) {
                return active;
            }
            if (OPERATION_UPDATE.equals(operation) && hasUpdatedTitle(active)) {
                return active;
            }
            if (OPERATION_OPEN.equals(operation)) {
                return active;
            }
            SystemClock.sleep(25L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return active;
    }

    private boolean openActiveTarget(NotificationManager manager) throws Exception {
        Notification active = findActive(manager);
        if (active == null || active.contentIntent == null) {
            return false;
        }
        deleteFile(TARGET_RESULT_FILE_NAME);
        active.contentIntent.send();
        long deadline = SystemClock.elapsedRealtime() + 2_000L;
        do {
            if (getFileStreamPath(TARGET_RESULT_FILE_NAME).isFile()) {
                return true;
            }
            SystemClock.sleep(25L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private boolean hasUpdatedTitle(Notification active) {
        if (active == null) {
            return false;
        }
        CharSequence title = active.extras.getCharSequence(Notification.EXTRA_TITLE);
        return title != null && title.toString().endsWith(" · update");
    }

    private void writeResult(Properties result) {
        try (OutputStream output = openFileOutput(RESULT_FILE_NAME, MODE_PRIVATE)) {
            result.store(output, null);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to write controlled notification result", failure);
        }
    }
}
