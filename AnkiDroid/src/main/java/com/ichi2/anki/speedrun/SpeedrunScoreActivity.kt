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
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.NavigationDrawerActivity
import com.ichi2.anki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One of three MCAT score pages, selected via [Mode]:
 *  - **READINESS** — projected MCAT total (472–528) and per-section readiness (118–132), with a bar
 *    chart and the total score banner when all four sections have enough data.
 *  - **PERFORMANCE** — quiz accuracy per section, overall accuracy banner when any answers exist.
 *  - **MEMORY** — FSRS retrievability per section (excluding CARS), overall retention banner when
 *    enough cards have been reviewed.
 *
 * Each page shows a horizontal bar chart comparing the four MCAT sections and a summary banner at
 * the top when there is enough data to compute a total/overall score.
 */
class SpeedrunScoreActivity : NavigationDrawerActivity(R.layout.activity_speedrun_score) {
    enum class Mode { READINESS, PERFORMANCE, MEMORY }

    private val contentContainer: FrameLayout by lazy { findViewById(R.id.speedrun_score_content) }

    private val mode: Mode by lazy {
        val ordinal = intent.getIntExtra(EXTRA_MODE, Mode.READINESS.ordinal)
        Mode.values().getOrElse(ordinal) { Mode.READINESS }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initNavigationDrawer()
        supportActionBar?.title = getString(R.string.app_name)
        renderLoading()

        lifecycleScope.launch {
            val projection =
                withContext(Dispatchers.IO) {
                    if (SpeedrunFirestoreSync.isConfigured) {
                        SpeedrunFirestoreSync.pull().forEach {
                            SpeedrunDb.insertPulledRecord(this@SpeedrunScoreActivity, it)
                        }
                    }
                    SpeedrunScore.compute(SpeedrunDb.topicStats(this@SpeedrunScoreActivity))
                }
            val memory = SpeedrunMemoryScore.compute()
            when (mode) {
                Mode.READINESS -> renderReadinessPage(projection)
                Mode.PERFORMANCE -> renderPerformancePage(projection)
                Mode.MEMORY -> renderMemoryPage(memory)
            }
        }
    }

    private fun renderLoading() {
        val column = column()
        column.addView(heading(pageTitle()))
        column.addView(body("Loading…"))
        setScreen(scroll(column))
    }

    private fun pageTitle() =
        when (mode) {
            Mode.READINESS -> "Readiness"
            Mode.PERFORMANCE -> "Performance"
            Mode.MEMORY -> "Memory"
        }

    // region readiness page

    private fun renderReadinessPage(p: SpeedrunScore.Projection) {
        val column = column()
        column.addView(heading("Readiness"))

        if (p.total != null) {
            column.addView(scoreBanner("Projected MCAT: ${p.total}   (${p.low}–${p.high})"))
            column.addView(
                body("Confidence: ${p.confidence}   ·   ${p.topicsWithData}/${SpeedrunScore.TOTAL_TOPICS} topics covered"),
            )
        } else {
            column.addView(
                body(
                    "Not enough data yet " +
                        "(${p.topicsWithData}/${SpeedrunScore.TOTAL_TOPICS} topics · ${p.totalAnswered} answered)",
                ),
            )
        }

        column.addView(spacer(12))

        // Bars: readiness on a 118–132 scale (14-point range).
        val bars =
            p.sections.map { s ->
                BarEntry(
                    label = s.section.code,
                    value = s.readiness?.toFloat(),
                    min = 118f,
                    max = 132f,
                    displayText =
                        if (s.readiness != null) {
                            s.readiness.toString()
                        } else {
                            "needs data (${pendingRemaining(s)})"
                        },
                    colorIndex = s.section.ordinal,
                )
            }
        column.addView(barChart(bars))

        setScreen(scroll(column))
    }

    // endregion

    // region performance page

    private fun renderPerformancePage(p: SpeedrunScore.Projection) {
        val column = column()
        column.addView(heading("Performance"))

        val totalAnswered = p.sections.sumOf { it.answered }
        val totalCorrect = p.sections.sumOf { it.correct }
        if (totalAnswered > 0) {
            val overall = totalCorrect.toDouble() / totalAnswered
            column.addView(
                scoreBanner("Overall accuracy: ${pct1(overall)}%   ($totalCorrect / $totalAnswered)"),
            )
        } else {
            column.addView(body("No answers recorded yet."))
        }

        column.addView(spacer(12))

        val bars =
            p.sections.map { s ->
                BarEntry(
                    label = s.section.code,
                    value = s.accuracy?.toFloat(),
                    min = 0f,
                    max = 1f,
                    displayText =
                        if (s.accuracy != null) {
                            "${pct1(s.accuracy)}%  (${s.correct}/${s.answered})"
                        } else {
                            "no answers yet"
                        },
                    colorIndex = s.section.ordinal,
                )
            }
        column.addView(barChart(bars))

        setScreen(scroll(column))
    }

    // endregion

    // region memory page

    private fun renderMemoryPage(memory: List<SectionMemory>) {
        val column = column()
        column.addView(heading("Memory"))

        if (memory.isEmpty()) {
            column.addView(body("Memory score unavailable on this device."))
            setScreen(scroll(column))
            return
        }

        // CARS has no flashcards so it never has a memory score.
        val shown = memory.filter { it.section != SpeedrunScore.Section.CARS }

        val scored = shown.filter { it.hasScore && it.retrievability != null }
        if (scored.isNotEmpty()) {
            val mean = scored.map { it.retrievability!! }.average()
            column.addView(scoreBanner("Overall retention: ${pct1(mean)}%"))
        } else {
            column.addView(body("Not enough data yet for an overall score."))
        }

        column.addView(spacer(12))

        val bars =
            shown.map { m ->
                BarEntry(
                    label = m.section.code,
                    value = m.retrievability?.toFloat(),
                    min = 0f,
                    max = 1f,
                    displayText =
                        if (m.hasScore && m.retrievability != null) {
                            "${pct1(m.retrievability)}%  (${m.reviewed} cards)"
                        } else {
                            "needs ${SpeedrunScore.SECTION_MIN_ANSWERED}+ reviews (${m.reviewed} so far)"
                        },
                    colorIndex = m.section.ordinal,
                )
            }
        column.addView(barChart(bars))

        setScreen(scroll(column))
    }

    // endregion

    // region bar chart

    private data class BarEntry(
        val label: String,
        /** Null when there is no data for this section. */
        val value: Float?,
        val min: Float,
        val max: Float,
        val displayText: String,
        /** Index into [sectionColors]. */
        val colorIndex: Int,
    )

    /** One colour per MCAT section (BB, CP, PS, CARS). */
    private val sectionColors =
        intArrayOf(
            Color.parseColor("#1976D2"),
            Color.parseColor("#F57C00"),
            Color.parseColor("#388E3C"),
            Color.parseColor("#7B1FA2"),
        )

    private fun barChart(entries: List<BarEntry>): LinearLayout {
        val chart =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        for (entry in entries) {
            chart.addView(barRow(entry))
        }
        return chart
    }

    private fun barRow(entry: BarEntry): LinearLayout {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }

        // Section code label at fixed width.
        val label =
            TextView(this).apply {
                text = entry.label
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                layoutParams =
                    LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    }
            }

        // Coloured progress bar inside a grey track.
        val track =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams =
                    LinearLayout.LayoutParams(dp(110), dp(18)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        marginEnd = dp(10)
                    }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }

        val fraction =
            if (entry.value != null) {
                ((entry.value - entry.min) / (entry.max - entry.min)).coerceIn(0f, 1f)
            } else {
                0f
            }
        val barColor = sectionColors.getOrElse(entry.colorIndex) { sectionColors[0] }

        if (fraction > 0f) {
            track.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraction)
                    setBackgroundColor(barColor)
                },
            )
        }
        val remaining = 1f - fraction
        if (remaining > 0f) {
            track.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, remaining)
                },
            )
        }

        val valueLabel =
            TextView(this).apply {
                text = entry.displayText
                textSize = 13f
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
            }

        row.addView(label)
        row.addView(track)
        row.addView(valueLabel)
        return row
    }

    // endregion

    // region helpers

    /** Formats a 0–1 fraction as a percentage with one decimal place (e.g. 0.873 → "87.3"). */
    private fun pct1(fraction: Double): String = String.format(Locale.US, "%.1f", fraction * 100)

    private fun pendingRemaining(score: SpeedrunScore.SectionScore): String {
        val remaining = (SpeedrunScore.SECTION_MIN_ANSWERED - score.answered).coerceAtLeast(0)
        return if (remaining > 0) "$remaining more" else "need ${SpeedrunScore.MULTI_TOPIC_MIN_ANSWERED}+ per topic"
    }

    private fun setScreen(view: View) {
        contentContainer.removeAllViews()
        contentContainer.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun scroll(child: LinearLayout): ScrollView =
        ScrollView(this).apply {
            addView(
                child,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    private fun column(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

    private fun spacer(heightDp: Int): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        }

    private fun heading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        }

    /**
     * Prominent banner shown when a total/overall score is available.
     * Uses a soft indigo tint that works in both light and dark themes.
     */
    private fun scoreBanner(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 17f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.parseColor("#EEF2FF"))
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding(0, dp(2), 0, dp(4))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // endregion

    companion object {
        const val EXTRA_MODE = "speedrun_score_mode"

        fun getIntent(
            context: Context,
            mode: Mode = Mode.READINESS,
        ): Intent =
            Intent(context, SpeedrunScoreActivity::class.java)
                .putExtra(EXTRA_MODE, mode.ordinal)
    }
}
