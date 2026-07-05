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

import kotlin.math.roundToInt

/**
 * Computes MCAT-style readiness scores from the local [speedrun_performance][SpeedrunDb] tallies.
 * The formula and thresholds mirror the desktop/web app so scores line up across devices.
 */
object SpeedrunScore {
    /** Minimum answers across a whole section before a score is shown. */
    const val SECTION_MIN_ANSWERED = 30

    /** Additional per-topic minimum for sections with 3 topics (so one thin topic can't inflate). */
    const val MULTI_TOPIC_MIN_ANSWERED = 10

    /** Downward calibration for AI-generated questions relative to the real MCAT. */
    const val CALIBRATION = 0.92

    /** A topic counts toward coverage once it has this many answers. */
    const val MIN_ANSWERED_FOR_COVERAGE = 5

    /** Total MCAT topics tracked across all sections (Essential-Equations is optional/supplemental). */
    const val TOTAL_TOPICS = 7

    /**
     * Topics that count toward a section's answered/correct totals but are exempt from the
     * per-topic minimum ([MULTI_TOPIC_MIN_ANSWERED]).  Essential-Equations is supplemental —
     * students shouldn't be blocked from seeing a B/B score just because they haven't drilled it.
     */
    val OPTIONAL_TOPICS = setOf("Essential-Equations")

    /** MCAT sections and the [speedrun topics][SpeedrunQuestion.topic] that roll up into each. */
    enum class Section(
        val code: String,
        val displayName: String,
        val topics: List<String>,
    ) {
        BB("B/B", "Biological & Biochemical", listOf("Biology", "Biochemistry", "Essential-Equations")),
        CP("C/P", "Chemical & Physical", listOf("General-Chemistry", "Organic-Chemistry", "Physics-and-Math")),
        PS("P/S", "Psychological & Social", listOf("Behavioral")),
        CARS("CARS", "Critical Analysis & Reasoning", listOf("CARS")),
    }

    data class SectionScore(
        val section: Section,
        val answered: Int,
        val correct: Int,
        /** correct / answered, or null if nothing answered. */
        val accuracy: Double?,
        /** 118–132 readiness score, or null when there isn't enough data yet. */
        val readiness: Int?,
    ) {
        val hasScore: Boolean get() = readiness != null
    }

    data class Projection(
        val sections: List<SectionScore>,
        /** Sum of the four section scores (472–528), or null until every section has a score. */
        val total: Int?,
        /** Lower/upper bound of the ~95% confidence interval, or null when [total] is null. */
        val low: Int?,
        val high: Int?,
        /** "low" | "medium" | "high" (by total answered), or "" when [total] is null. */
        val confidence: String,
        /** Number of the [TOTAL_TOPICS] topics with at least [MIN_ANSWERED_FOR_COVERAGE] answers. */
        val topicsWithData: Int,
        val totalAnswered: Int,
        /** Answers recorded for the Essential-Equations topic (tracked separately as optional). */
        val essentialEquationsAnswered: Int,
    )

    /** Readiness score for a section given its accuracy: `118 + clamp((acc*CALIBRATION − 0.25)/0.75, 0, 1) * 14`. */
    fun readinessFor(accuracy: Double): Int {
        val scaled = ((accuracy * CALIBRATION - 0.25) / 0.75).coerceIn(0.0, 1.0)
        return (118 + scaled * 14).roundToInt()
    }

    fun compute(topicStats: Map<String, TopicStat>): Projection {
        val sectionScores =
            Section.entries.map { section ->
                val topicTallies = section.topics.map { topicStats[it] ?: TopicStat(0, 0) }
                val answered = topicTallies.sumOf { it.answered }
                val correct = topicTallies.sumOf { it.correct }
                val accuracy = if (answered > 0) correct.toDouble() / answered else null

                val meetsPerTopic =
                    section.topics.size < 3 ||
                        section.topics
                            .filter { it !in OPTIONAL_TOPICS }
                            .all { (topicStats[it]?.answered ?: 0) >= MULTI_TOPIC_MIN_ANSWERED }
                val hasEnough = answered >= SECTION_MIN_ANSWERED && meetsPerTopic

                SectionScore(
                    section = section,
                    answered = answered,
                    correct = correct,
                    accuracy = accuracy,
                    readiness = if (hasEnough && accuracy != null) readinessFor(accuracy) else null,
                )
            }

        val totalAnswered = sectionScores.sumOf { it.answered }
        val topicsWithData =
            Section.entries
                .flatMap { it.topics }
                .distinct()
                .count { (topicStats[it]?.answered ?: 0) >= MIN_ANSWERED_FOR_COVERAGE }

        val total = if (sectionScores.all { it.readiness != null }) sectionScores.sumOf { it.readiness!! } else null

        var low: Int? = null
        var high: Int? = null
        var confidence = ""
        if (total != null) {
            val margin =
                when {
                    totalAnswered < 100 -> 6
                    totalAnswered < 200 -> 4
                    else -> 3
                }
            low = total - margin
            high = total + margin
            confidence =
                when {
                    totalAnswered < 100 -> "low"
                    totalAnswered < 200 -> "medium"
                    else -> "high"
                }
        }

        return Projection(
            sections = sectionScores,
            total = total,
            low = low,
            high = high,
            confidence = confidence,
            topicsWithData = topicsWithData,
            totalAnswered = totalAnswered,
            essentialEquationsAnswered = topicStats["Essential-Equations"]?.answered ?: 0,
        )
    }
}
