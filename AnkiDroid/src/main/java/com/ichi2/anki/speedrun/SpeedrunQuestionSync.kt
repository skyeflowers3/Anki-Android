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

import android.content.Context
import androidx.annotation.WorkerThread
import com.ichi2.anki.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Pulls AI-generated MCAT questions from the shared Firestore `speedrun_questions` collection
 * and caches them locally so they are available offline.
 *
 * This mirrors the desktop `pull_questions()` in `question_sync.py`:
 *  - Queries the collection with no per-user auth (questions are shared across all users).
 *  - Only keeps documents where `eval_passed == true`.
 *  - Deserialises each document using the same field names as the bundled `questions.json`.
 *
 * Usage pattern (from [StudyLoopActivity]):
 *  1. Call [readCache] immediately to get previously-fetched questions (may be empty on first run).
 *  2. Call [pullAndCache] in the background; the cache is updated for the *next* session.
 */
@WorkerThread
object SpeedrunQuestionSync {
    private val API_KEY get() = BuildConfig.SPEEDRUN_API_KEY
    private val PROJECT get() = BuildConfig.SPEEDRUN_PROJECT_ID

    private const val CACHE_FILE = "speedrun_generated_questions.json"
    private const val PAGE_SIZE = 500

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /** True when the build carries the Firebase project credentials. No SYNC_ID needed for question reads. */
    val isConfigured: Boolean get() = API_KEY.isNotEmpty() && PROJECT.isNotEmpty()

    private fun questionsUrl(): String =
        "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents" +
            "/speedrun_questions?key=$API_KEY&pageSize=$PAGE_SIZE"

    /**
     * Pulls eval-passed questions from Firestore and writes them to the local cache.
     * Returns the fetched list on success; returns an empty list and leaves the cache unchanged on failure.
     */
    fun pullAndCache(context: Context): List<SpeedrunQuestion> {
        if (!isConfigured) return emptyList()
        return try {
            val request =
                Request
                    .Builder()
                    .url(questionsUrl())
                    .get()
                    .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("question sync: pull failed (%d)", resp.code)
                    return emptyList()
                }
                val questions = parseDocuments(resp.body.string())
                writeCache(context, questions)
                Timber.i("question sync: cached %d generated questions", questions.size)
                questions
            }
        } catch (e: Exception) {
            Timber.w(e, "question sync: pull error")
            emptyList()
        }
    }

    /** Returns previously cached generated questions, or an empty list if no cache exists yet. */
    fun readCache(context: Context): List<SpeedrunQuestion> {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (!cacheFile.exists()) return emptyList()
        return try {
            json.decodeFromString<SpeedrunQuestionBank>(cacheFile.readText()).questions
        } catch (e: Exception) {
            Timber.w(e, "question sync: cache read error — ignoring stale cache")
            emptyList()
        }
    }

    private fun writeCache(
        context: Context,
        questions: List<SpeedrunQuestion>,
    ) {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        cacheFile.writeText(json.encodeToString(SpeedrunQuestionBank(questions)))
    }

    private fun parseDocuments(body: String): List<SpeedrunQuestion> {
        val documents = JSONObject(body).optJSONArray("documents") ?: return emptyList()
        val questions = ArrayList<SpeedrunQuestion>(documents.length())
        for (i in 0 until documents.length()) {
            val fields = documents.getJSONObject(i).optJSONObject("fields") ?: continue
            // Mirror desktop filter: only include questions that passed automated eval.
            val evalPassed = fields.optJSONObject("eval_passed")?.optBoolean("booleanValue", false) ?: false
            if (!evalPassed) continue
            try {
                val choices =
                    fields
                        .optJSONObject("choices")
                        ?.optJSONObject("arrayValue")
                        ?.optJSONArray("values")
                        ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).optString("stringValue") } }
                        ?: emptyList()

                val id =
                    fields.optJSONObject("id")?.optString("integerValue")?.toIntOrNull()
                        ?: continue // id is required; skip if missing

                questions.add(
                    SpeedrunQuestion(
                        id = id,
                        passage = fields.stringField("passage"),
                        question = fields.stringField("question"),
                        choices = choices,
                        correctAnswer = fields.stringField("correct_answer"),
                        topic = fields.stringField("topic"),
                        concept = fields.stringField("concept"),
                    ),
                )
            } catch (e: Exception) {
                Timber.w(e, "question sync: skipping malformed question document")
            }
        }
        return questions
    }

    private fun JSONObject.stringField(name: String): String = optJSONObject(name)?.optString("stringValue").orEmpty()
}
