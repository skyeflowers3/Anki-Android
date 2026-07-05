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
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.time.TimeManager
import timber.log.Timber

/** Mean FSRS retrievability for one MCAT section. */
data class SectionMemory(
    val section: SpeedrunScore.Section,
    val reviewed: Int,
    /** Mean retrievability 0–1, or null when there's no data. */
    val retrievability: Double?,
    val hasScore: Boolean,
)

/**
 * Computes the "memory score" — FSRS retrievability (R) aggregated per MCAT section — by reading
 * the collection with Anki's `extract_fsrs_retrievability` SQL function (the same query the desktop
 * uses). This is a read-only SELECT; it never mutates the collection.
 *
 * Retrievability requires the Rust backend's FSRS function; if it's unavailable the compute falls
 * back to an empty list and the caller shows "memory score unavailable".
 */
@WorkerThread
object SpeedrunMemoryScore {
    // Anki stores deck hierarchy with the unit separator (U+001F); "::" is only the display form.
    private const val DECK_SEPARATOR = "\u001f"

    private val topicToSection: Map<String, SpeedrunScore.Section> =
        SpeedrunScore.Section.entries
            .flatMap { section -> section.topics.map { it to section } }
            .toMap()

    private const val QUERY =
        "SELECT d.name, extract_fsrs_retrievability(" +
            "c.data, CASE WHEN c.odue != 0 THEN c.odue ELSE c.due END, c.ivl, ?, ?, ?) " +
            "FROM cards c JOIN decks d ON d.id = (CASE WHEN c.odid != 0 THEN c.odid ELSE c.did END)"

    /** Per-section mean retrievability. Empty list if the FSRS query is unavailable/fails. */
    suspend fun compute(): List<SectionMemory> =
        try {
            withCol {
                val days = sched.today
                val cutoff = sched.dayCutoff
                val now = TimeManager.time.intTime()
                val byTopic = HashMap<String, MutableList<Double>>()
                db.query(QUERY, days, cutoff, now).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (cursor.isNull(1)) continue
                        val topic = topicForDeckName(cursor.getString(0)) ?: continue
                        byTopic.getOrPut(topic) { mutableListOf() }.add(cursor.getDouble(1))
                    }
                }
                buildSections(byTopic)
            }
        } catch (e: Exception) {
            Timber.w(e, "speedrun: memory score unavailable (FSRS retrievability query failed)")
            emptyList()
        }

    /** Maps a full deck name (unit-separator or "::") to its MCAT topic, or null. */
    private fun topicForDeckName(name: String): String? = name.split(DECK_SEPARATOR, "::").firstOrNull { it in topicToSection }

    private fun buildSections(byTopic: Map<String, List<Double>>): List<SectionMemory> =
        SpeedrunScore.Section.entries.map { section ->
            val values = section.topics.flatMap { byTopic[it].orEmpty() }
            val meetsPerTopic =
                section.topics.size < 3 ||
                    section.topics.all { (byTopic[it]?.size ?: 0) >= SpeedrunScore.MULTI_TOPIC_MIN_ANSWERED }
            SectionMemory(
                section = section,
                reviewed = values.size,
                retrievability = if (values.isNotEmpty()) values.average() else null,
                hasScore = values.size >= SpeedrunScore.SECTION_MIN_ANSWERED && meetsPerTopic,
            )
        }
}
