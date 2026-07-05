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
import java.util.UUID

/** One persisted speedrun answer, matching the cross-device `speedrun_performance` schema. */
data class SpeedrunRecord(
    val syncKey: String,
    val answeredAt: Long,
    val questionId: String,
    val topic: String,
    val concept: String,
    val chosenConcept: String,
    val correctConcept: String,
    val chosenAnswer: String,
    val correctAnswer: String,
    val conceptCorrect: Int,
    val applicationCorrect: Int,
    val answerCorrect: Int,
)

/** Outcome of grading one answered question, plus the stored record for Firestore push. */
data class SpeedrunAnswerResult(
    val conceptCorrect: Boolean,
    val answerCorrect: Boolean,
    val record: SpeedrunRecord,
)

/** Per-topic tally used by [SpeedrunScore]. */
data class TopicStat(
    val answered: Int,
    val correct: Int,
)

/**
 * Stores speedrun quiz answers in a standalone, app-local SQLite database
 * (`ankidroid_speedrun.db`), following the [com.ichi2.anki.MetaDB] pattern. It never touches the
 * Anki collection database.
 *
 * The schema matches the desktop/web speedrun app so records can be reconciled through Firestore
 * (see [SpeedrunFirestoreSync]); [sync_key][SpeedrunRecord.syncKey] is the cross-device identity of
 * a record and is enforced UNIQUE so pulled duplicates are ignored.
 */
@WorkerThread
object SpeedrunDb {
    private const val DATABASE_NAME = "ankidroid_speedrun.db"
    private const val TABLE = "speedrun_performance"

    private val letters = listOf("A", "B", "C", "D", "E", "F")

    private var db: SQLiteDatabase? = null

    private fun openDB(context: Context): SQLiteDatabase {
        db?.let { if (it.isOpen) return it }
        val opened = context.applicationContext.openOrCreateDatabase(DATABASE_NAME, 0, null)
        // Fresh installs get the full schema. `sync_key` is declared plain here (not inline UNIQUE)
        // because SQLite cannot ALTER-ADD a UNIQUE column; uniqueness is enforced by the index below,
        // which works identically for fresh and migrated databases.
        opened.execSQL(
            """CREATE TABLE IF NOT EXISTS $TABLE (
            id INTEGER PRIMARY KEY,
            answered_at INTEGER NOT NULL,
            question_id TEXT NOT NULL,
            topic TEXT NOT NULL,
            concept TEXT NOT NULL,
            chosen_concept TEXT NOT NULL,
            correct_concept TEXT NOT NULL,
            chosen_answer TEXT NOT NULL,
            correct_answer TEXT NOT NULL,
            concept_correct INTEGER NOT NULL DEFAULT 0,
            application_correct INTEGER NOT NULL DEFAULT 0,
            answer_correct INTEGER NOT NULL,
            sync_key TEXT,
            synced INTEGER NOT NULL DEFAULT 0
            )""",
        )
        // Migrate databases created before application_correct / sync_key / synced existed.
        addColumnIfMissing(opened, "application_correct", "INTEGER NOT NULL DEFAULT 0")
        addColumnIfMissing(opened, "sync_key", "TEXT")
        addColumnIfMissing(opened, "synced", "INTEGER NOT NULL DEFAULT 0")
        opened.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_${TABLE}_sync_key ON $TABLE(sync_key)")
        Timber.v("Opened speedrun performance DB")
        db = opened
        return opened
    }

    private fun addColumnIfMissing(
        database: SQLiteDatabase,
        column: String,
        definition: String,
    ) {
        try {
            database.execSQL("ALTER TABLE $TABLE ADD COLUMN $column $definition")
        } catch (e: Exception) {
            // Column already exists – expected on all but the first migration.
            Timber.v("speedrun column %s already present", column)
        }
    }

    /** Letter (A/B/C/D…) for [choice] within [choices], or "" if not found. */
    private fun letterFor(
        choices: List<String>,
        choice: String,
    ): String {
        val index = choices.indexOf(choice)
        return if (index in letters.indices) letters[index] else ""
    }

    /**
     * Grades and persists one answered question, returning whether the concept identification and
     * the answer were correct, plus the stored [SpeedrunRecord] so the caller can push it to
     * Firestore. Answer fields are stored as letters (A/B/C/D) to match the desktop/web schema.
     */
    fun recordAnswer(
        context: Context,
        question: SpeedrunQuestion,
        chosenConcept: String,
        chosenAnswer: String,
    ): SpeedrunAnswerResult {
        val chosenLetter = letterFor(question.choices, chosenAnswer)
        val correctLetter = letterFor(question.choices, question.correctAnswer)
        val conceptCorrect =
            run {
                val chosen = normalise(chosenConcept)
                val correct = normalise(question.concept)
                chosen == correct ||
                    chosen.split(" ").containsAll(correct.split(" ")) ||
                    correct.split(" ").all { word -> chosen.contains(word) }
            }
        val answerCorrect = chosenLetter.isNotEmpty() && chosenLetter == correctLetter
        val record =
            SpeedrunRecord(
                syncKey = UUID.randomUUID().toString().replace("-", ""),
                answeredAt = TimeManager.time.intTime(),
                questionId = question.id.toString(),
                topic = question.topic,
                concept = question.concept,
                chosenConcept = chosenConcept,
                correctConcept = question.concept,
                chosenAnswer = chosenLetter,
                correctAnswer = correctLetter,
                conceptCorrect = if (conceptCorrect) 1 else 0,
                applicationCorrect = 0,
                answerCorrect = if (answerCorrect) 1 else 0,
            )
        try {
            openDB(context).insert(TABLE, null, record.toContentValues())
        } catch (e: Exception) {
            // Persistence is best-effort: a failure here must not derail the study session.
            Timber.w(e, "Failed to record speedrun answer")
        }
        return SpeedrunAnswerResult(conceptCorrect = conceptCorrect, answerCorrect = answerCorrect, record = record)
    }

    /**
     * Inserts a record pulled from Firestore, skipping it if its [sync_key][SpeedrunRecord.syncKey]
     * already exists locally (INSERT OR IGNORE against the UNIQUE index). Marks the record as synced
     * either way — if it came from Firestore it is by definition already there.
     */
    fun insertPulledRecord(
        context: Context,
        record: SpeedrunRecord,
    ) {
        try {
            val db = openDB(context)
            db.insertWithOnConflict(TABLE, null, record.toContentValues(), SQLiteDatabase.CONFLICT_IGNORE)
            // Mark synced=1 regardless of whether the row was just inserted or already existed.
            db.execSQL("UPDATE $TABLE SET synced = 1 WHERE sync_key = ?", arrayOf(record.syncKey))
        } catch (e: Exception) {
            Timber.w(e, "Failed to merge pulled speedrun record")
        }
    }

    /** Marks a locally-recorded answer as successfully pushed to Firestore. */
    fun markSynced(
        context: Context,
        syncKey: String,
    ) {
        try {
            openDB(context).execSQL("UPDATE $TABLE SET synced = 1 WHERE sync_key = ?", arrayOf(syncKey))
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark speedrun record as synced")
        }
    }

    /** All records not yet confirmed as pushed to Firestore. */
    fun unsyncedRecords(context: Context): List<SpeedrunRecord> {
        val records = mutableListOf<SpeedrunRecord>()
        try {
            openDB(context)
                .rawQuery(
                    """SELECT sync_key, answered_at, question_id, topic, concept,
                        chosen_concept, correct_concept, chosen_answer, correct_answer,
                        concept_correct, application_correct, answer_correct
                        FROM $TABLE WHERE synced = 0""",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        records.add(
                            SpeedrunRecord(
                                syncKey = c.getString(0),
                                answeredAt = c.getLong(1),
                                questionId = c.getString(2),
                                topic = c.getString(3),
                                concept = c.getString(4),
                                chosenConcept = c.getString(5),
                                correctConcept = c.getString(6),
                                chosenAnswer = c.getString(7),
                                correctAnswer = c.getString(8),
                                conceptCorrect = c.getInt(9),
                                applicationCorrect = c.getInt(10),
                                answerCorrect = c.getInt(11),
                            ),
                        )
                    }
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch unsynced speedrun records")
        }
        return records
    }

    /** Per-topic (answered count, correct count) used to compute accuracy. */
    fun topicStats(context: Context): Map<String, TopicStat> {
        val stats = mutableMapOf<String, TopicStat>()
        try {
            openDB(context)
                .rawQuery(
                    "SELECT topic, COUNT(*), COALESCE(SUM(answer_correct), 0) FROM $TABLE GROUP BY topic",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        stats[c.getString(0)] = TopicStat(answered = c.getInt(1), correct = c.getInt(2))
                    }
                }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read speedrun topic stats")
        }
        return stats
    }

    /**
     * Case-insensitive, punctuation-tolerant match used for concept grading. Mirrors the
     * desktop's offline approximation: all key words from the correct concept must appear in
     * the student's answer, regardless of order or punctuation.
     */
    private fun normalise(s: String) =
        s
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Per-topic (answered, correct) tallies. Returns the same data as [topicStats] but as
     * [Pair] to match the desktop sync layer's expected type.
     */
    fun fetchTopicResults(context: Context): Map<String, Pair<Int, Int>> =
        topicStats(context).mapValues { (_, stat) -> Pair(stat.answered, stat.correct) }

    private fun SpeedrunRecord.toContentValues() =
        ContentValues().apply {
            put("answered_at", answeredAt)
            put("question_id", questionId)
            put("topic", topic)
            put("concept", concept)
            put("chosen_concept", chosenConcept)
            put("correct_concept", correctConcept)
            put("chosen_answer", chosenAnswer)
            put("correct_answer", correctAnswer)
            put("concept_correct", conceptCorrect)
            put("application_correct", applicationCorrect)
            put("answer_correct", answerCorrect)
            put("sync_key", syncKey)
        }
}
