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

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.WorkerThread
import com.ichi2.anki.common.time.TimeManager
import timber.log.Timber

/** Outcome of grading one answered question, used both for persistence and the on-screen reveal. */
data class SpeedrunAnswerResult(
    val conceptCorrect: Boolean,
    val answerCorrect: Boolean,
)

/**
 * Stores speedrun quiz answers in a standalone, app-local SQLite database
 * (`ankidroid_speedrun.db`), following the [com.ichi2.anki.MetaDB] pattern.
 *
 * This is intentionally kept separate from the Anki collection database: it never touches
 * `col.db`. The `speedrun_performance` schema matches the desktop speedrun app so the two can be
 * reconciled later for sync.
 */
@WorkerThread
object SpeedrunDb {
    private const val DATABASE_NAME = "ankidroid_speedrun.db"
    private const val TABLE = "speedrun_performance"

    private var db: SQLiteDatabase? = null

    private fun openDB(context: Context): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        val opened = context.applicationContext.openOrCreateDatabase(DATABASE_NAME, 0, null)
        opened.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE (
            id INTEGER PRIMARY KEY,
            answered_at INTEGER NOT NULL,
            question_id INTEGER NOT NULL,
            topic TEXT NOT NULL,
            concept TEXT NOT NULL,
            chosen_concept TEXT NOT NULL,
            correct_concept TEXT NOT NULL,
            chosen_answer TEXT NOT NULL,
            correct_answer TEXT NOT NULL,
            concept_correct INTEGER NOT NULL,
            answer_correct INTEGER NOT NULL
            )""",
        )
        Timber.v("Opened speedrun performance DB")
        db = opened
        return opened
    }

    /**
     * Grades and persists one answered question, returning whether the concept identification and
     * the answer were correct so the caller can reveal both at once.
     *
     * @param chosenConcept the concept option the student selected in the first step
     * @param chosenAnswer the answer option the student selected in the second step
     */
    fun recordAnswer(
        context: Context,
        question: SpeedrunQuestion,
        chosenConcept: String,
        chosenAnswer: String,
    ): SpeedrunAnswerResult {
        val conceptCorrect = chosenConcept == question.concept
        val answerCorrect = chosenAnswer == question.correctAnswer
        val values =
            ContentValues().apply {
                put("answered_at", TimeManager.time.intTime())
                put("question_id", question.id)
                put("topic", question.topic)
                put("concept", question.concept)
                put("chosen_concept", chosenConcept)
                put("correct_concept", question.concept)
                put("chosen_answer", chosenAnswer)
                put("correct_answer", question.correctAnswer)
                put("concept_correct", if (conceptCorrect) 1 else 0)
                put("answer_correct", if (answerCorrect) 1 else 0)
            }
        try {
            openDB(context).insert(TABLE, null, values)
        } catch (e: Exception) {
            // Persistence is best-effort: a failure here must not derail the study session.
            Timber.w(e, "Failed to record speedrun answer")
        }
        return SpeedrunAnswerResult(conceptCorrect = conceptCorrect, answerCorrect = answerCorrect)
    }
}
