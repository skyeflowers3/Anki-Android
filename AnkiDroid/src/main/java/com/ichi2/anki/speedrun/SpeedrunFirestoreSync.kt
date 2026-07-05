/*
 *  Copyright (c) 2026 AnkiDroid Contributors
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.speedrun

import androidx.annotation.WorkerThread
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.common.time.TimeManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Syncs [speedrun_performance][SpeedrunDb] records with Firestore via its REST API, using the same
 * shared document (`speedrun_performance/{SPEEDRUN_SYNC_ID}/records`) as the desktop/web app so
 * performance data stays identical across devices.
 *
 * Auth is an anonymous Firebase user (Identity Toolkit `signUp`); the resulting ID token is cached
 * for the session. All methods are blocking and must be called off the main thread; sync is
 * best-effort and never throws to the caller.
 */
@WorkerThread
object SpeedrunFirestoreSync {
    private val API_KEY = BuildConfig.SPEEDRUN_API_KEY
    private val PROJECT = BuildConfig.SPEEDRUN_PROJECT_ID
    private val SYNC_ID = BuildConfig.SPEEDRUN_SYNC_ID

    private const val PAGE_SIZE = 300
    private const val TOKEN_TTL_MS = 55 * 60 * 1000L // refresh before the 1h anonymous-token expiry

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient()

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenFetchedAtMs: Long = 0

    /** True when the build carries the Firebase key + sync id; otherwise sync is disabled. */
    val isConfigured: Boolean
        get() = API_KEY.isNotEmpty() && SYNC_ID.isNotEmpty()

    private fun recordsUrl(): String =
        "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents" +
            "/speedrun_performance/$SYNC_ID/records"

    /** Returns a valid anonymous ID token, (re)fetching if missing or near expiry. Null on failure. */
    private fun idToken(): String? {
        val now = TimeManager.time.intTimeMS()
        cachedToken?.let { if (now - tokenFetchedAtMs < TOKEN_TTL_MS) return it }
        return try {
            val request =
                Request
                    .Builder()
                    .url("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY")
                    .post(JSONObject().put("returnSecureToken", true).toString().toRequestBody(jsonMedia))
                    .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Timber.w("speedrun sync: anon auth failed (%d)", resp.code)
                    return null
                }
                val token = JSONObject(body).optString("idToken").ifEmpty { null }
                cachedToken = token
                tokenFetchedAtMs = now
                token
            }
        } catch (e: Exception) {
            Timber.w(e, "speedrun sync: anon auth error")
            null
        }
    }

    /** Pushes one record to Firestore (upsert keyed by [SpeedrunRecord.syncKey]). Returns success. */
    fun push(record: SpeedrunRecord): Boolean {
        if (!isConfigured) return false
        val token = idToken() ?: return false
        return try {
            val url = "${recordsUrl()}/${record.syncKey}?key=$API_KEY"
            val request =
                Request
                    .Builder()
                    .url(url)
                    .patch(record.toFirestoreDocument().toString().toRequestBody(jsonMedia))
                    .header("Authorization", "Bearer $token")
                    .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) Timber.w("speedrun sync: push failed (%d)", resp.code)
                resp.isSuccessful
            }
        } catch (e: Exception) {
            Timber.w(e, "speedrun sync: push error")
            false
        }
    }

    /** Pulls all records for this sync id from Firestore. Returns an empty list on failure. */
    fun pull(): List<SpeedrunRecord> {
        if (!isConfigured) return emptyList()
        val token = idToken() ?: return emptyList()
        return try {
            val request =
                Request
                    .Builder()
                    .url("${recordsUrl()}?key=$API_KEY&pageSize=$PAGE_SIZE")
                    .header("Authorization", "Bearer $token")
                    .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Timber.w("speedrun sync: pull failed (%d)", resp.code)
                    return emptyList()
                }
                parseDocuments(body)
            }
        } catch (e: Exception) {
            Timber.w(e, "speedrun sync: pull error")
            emptyList()
        }
    }

    private fun parseDocuments(body: String): List<SpeedrunRecord> {
        val documents = JSONObject(body).optJSONArray("documents") ?: return emptyList()
        val records = ArrayList<SpeedrunRecord>(documents.length())
        for (i in 0 until documents.length()) {
            val fields = documents.getJSONObject(i).optJSONObject("fields") ?: continue
            try {
                records.add(
                    SpeedrunRecord(
                        syncKey = fields.stringField("sync_key"),
                        answeredAt = fields.intField("answered_at").toLong(),
                        questionId = fields.stringField("question_id"),
                        topic = fields.stringField("topic"),
                        concept = fields.stringField("concept"),
                        chosenConcept = fields.stringField("chosen_concept"),
                        correctConcept = fields.stringField("correct_concept"),
                        chosenAnswer = fields.stringField("chosen_answer"),
                        correctAnswer = fields.stringField("correct_answer"),
                        conceptCorrect = fields.intField("concept_correct"),
                        applicationCorrect = fields.intField("application_correct"),
                        answerCorrect = fields.intField("answer_correct"),
                    ),
                )
            } catch (e: Exception) {
                Timber.w(e, "speedrun sync: skipping malformed record")
            }
        }
        return records
    }

    // Firestore typed-value helpers ------------------------------------------------------------

    private fun JSONObject.stringField(name: String): String = optJSONObject(name)?.optString("stringValue").orEmpty()

    /** integerValue comes back as a string in Firestore REST responses. */
    private fun JSONObject.intField(name: String): Int = optJSONObject(name)?.optString("integerValue")?.toIntOrNull() ?: 0

    private fun stringValue(value: String) = JSONObject().put("stringValue", value)

    private fun integerValue(value: Long) = JSONObject().put("integerValue", value.toString())

    private fun SpeedrunRecord.toFirestoreDocument(): JSONObject {
        val fields =
            JSONObject()
                .put("sync_key", stringValue(syncKey))
                .put("answered_at", integerValue(answeredAt))
                .put("question_id", stringValue(questionId))
                .put("topic", stringValue(topic))
                .put("concept", stringValue(concept))
                .put("chosen_concept", stringValue(chosenConcept))
                .put("correct_concept", stringValue(correctConcept))
                .put("chosen_answer", stringValue(chosenAnswer))
                .put("correct_answer", stringValue(correctAnswer))
                .put("concept_correct", integerValue(conceptCorrect.toLong()))
                .put("application_correct", integerValue(applicationCorrect.toLong()))
                .put("answer_correct", integerValue(answerCorrect.toLong()))
        return JSONObject().put("fields", fields)
    }
}
