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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single MCAT-style question from the bundled speedrun question bank or from Firestore.
 *
 * The JSON structure mirrors the desktop speedrun app (`speedrun/questions.json`) so the two
 * stay interchangeable: each question has an id, a passage, the question stem, four [choices],
 * the [correctAnswer] (which is one of the [choices]), a [topic] and a [concept].
 *
 * Questions pulled from Firestore have [isGenerated] = true and a non-empty [source] string.
 */
@Serializable
data class SpeedrunQuestion(
    val id: Int,
    val passage: String,
    val question: String,
    val choices: List<String>,
    @SerialName("correct_answer") val correctAnswer: String,
    val topic: String,
    val concept: String,
    /** Non-empty for AI-generated questions (e.g. "GPT-4o · Biology"). */
    val source: String = "",
    /** True for questions pulled from Firestore; false for bundled questions. */
    @SerialName("is_generated") val isGenerated: Boolean = false,
)

/** Package-internal so [SpeedrunQuestionSync] can serialise/deserialise the cache file. */
@Serializable
internal data class SpeedrunQuestionBank(
    val questions: List<SpeedrunQuestion>,
)

/**
 * Loads the speedrun question bank bundled in `assets/speedrun/questions.json`, and optionally
 * merges it with AI-generated questions pulled from Firestore.
 *
 * This is a plain read of packaged assets; it does not touch the Anki collection.
 */
object SpeedrunQuestions {
    private const val ASSET_PATH = "speedrun/questions.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads and parses every question from the bundled asset. */
    fun load(context: Context): List<SpeedrunQuestion> {
        val raw =
            context.assets
                .open(ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
        return json.decodeFromString<SpeedrunQuestionBank>(raw).questions
    }

    /**
     * Returns the union of the bundled questions and any cached generated questions from Firestore,
     * deduplicated by [SpeedrunQuestion.id] (bundled questions take precedence on collision).
     *
     * Call this on startup instead of [load].  Kick off [SpeedrunQuestionSync.pullAndCache] in the
     * background afterwards so the cache is fresh for the next session.
     */
    fun loadMerged(context: Context): List<SpeedrunQuestion> {
        val bundled = load(context)
        val cached = SpeedrunQuestionSync.readCache(context)
        val bundledIds = bundled.map { it.id }.toHashSet()
        return bundled + cached.filter { it.id !in bundledIds }
    }
}
