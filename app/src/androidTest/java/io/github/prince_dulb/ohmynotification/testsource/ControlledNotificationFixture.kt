package io.github.prince_dulb.ohmynotification.testsource

import android.content.Context
import org.json.JSONObject

internal data class ControlledNotificationFixture(
    val caseId: String,
    val syntheticId: String,
    val sourcePackage: String,
    val postedAtEpochMillis: Long,
    val title: String?,
    val text: String?,
    val styleHint: String,
    val targetToken: String?,
)

internal object ControlledNotificationFixtures {
    const val DATASET_ID = "notification-fields-v1"

    fun load(context: Context, caseId: String): ControlledNotificationFixture {
        val dataset = context.assets
            .open("$DATASET_ID/dataset.json")
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> JSONObject(reader.readText()) }
        val cases = dataset.getJSONArray("cases")
        val fixtureJson = (0 until cases.length())
            .asSequence()
            .map(cases::getJSONObject)
            .firstOrNull { candidate -> candidate.getString("caseId") == caseId }
            ?: error("Unknown synthetic notification case: $caseId")

        val text = when {
            fixtureJson.has("textGenerator") -> {
                val generator = fixtureJson.getJSONObject("textGenerator")
                generator.getString("character").repeat(generator.getInt("length"))
            }
            fixtureJson.isNull("text") -> null
            else -> fixtureJson.getString("text")
        }
        val action = fixtureJson.optJSONObject("action")

        return ControlledNotificationFixture(
            caseId = fixtureJson.getString("caseId"),
            syntheticId = fixtureJson.getString("syntheticId"),
            sourcePackage = fixtureJson.getString("sourcePackage"),
            postedAtEpochMillis = fixtureJson.getLong("postedAtEpochMillis"),
            title = if (fixtureJson.isNull("title")) null else fixtureJson.getString("title"),
            text = text,
            styleHint = fixtureJson.getString("styleHint"),
            targetToken = action?.optString("targetToken")?.takeIf(String::isNotBlank),
        )
    }
}
