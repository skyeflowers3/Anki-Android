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
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import anki.scheduler.CustomStudyRequest.Cram.CramKind
import anki.scheduler.copy
import anki.scheduler.customStudyRequest
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.NavigationDrawerActivity
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.libanki.DeckId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * A self-contained "speedrun" study loop for the top-level AnKing-MCAT deck.
 *
 * The session runs a fixed sequence of [blockTypes]. Each block is *either*:
 *  - a **flashcard block** of [CARDS_PER_FLASHCARD_BLOCK] cards drawn from the whole deck with all
 *    topics interleaved (via a temporary custom-study filtered deck reviewed by AnkiDroid's
 *    existing reviewer; the reviewer/scheduler code is untouched), or
 *  - a **practice-question block** of [QUESTIONS_PER_BLOCK] questions from the bundled bank, each
 *    asked in two steps (identify the concept, then answer) with both results revealed at once.
 *
 * Cards and questions are interleaved across topics because mixed practice is more effective than
 * blocking by topic. A summary screen at the end reports concept- and answer-accuracy. Answers are
 * persisted by [SpeedrunDb] into a standalone app-local database.
 */
class StudyLoopActivity : NavigationDrawerActivity(R.layout.activity_study_loop) {
    private enum class BlockType { FLASHCARDS, QUESTIONS }

    /** Container the swappable session screens are injected into, below the persistent toolbar. */
    private val contentContainer: FrameLayout by lazy { findViewById(R.id.study_loop_content) }

    /** The block sequence for one session: flashcards and questions alternated. */
    private val blockTypes =
        listOf(
            BlockType.FLASHCARDS,
            BlockType.QUESTIONS,
            BlockType.FLASHCARDS,
            BlockType.QUESTIONS,
            BlockType.FLASHCARDS,
        )

    private var homeDeckId: DeckId = 0
    private lateinit var questions: List<SpeedrunQuestion>

    /** All questions in a fixed shuffled order; question blocks consume from here in sequence. */
    private lateinit var practiceQueue: List<SpeedrunQuestion>
    private var practiceCursor = 0

    private var currentBlock = 0

    // Running tallies over every practice question answered this session.
    private var questionsAnswered = 0
    private var conceptCorrectCount = 0
    private var answerCorrectCount = 0

    // Current question-block state.
    private var blockQuestions: List<SpeedrunQuestion> = emptyList()
    private var questionIndex = 0
    private var chosenConcept: String? = null

    /** The temporary filtered deck currently being reviewed, if any. */
    private var activeFilteredDeckId: DeckId? = null

    private val reviewLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onFlashcardsFinished(result.resultCode)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Persistent toolbar + hamburger navigation drawer so the user can leave the loop anytime.
        initNavigationDrawer()
        supportActionBar?.title = getString(R.string.app_name)
        homeDeckId = intent.getLongExtra(EXTRA_DECK_ID, 0)
        mergeRemoteRecordsInBackground()
        refreshGeneratedQuestionsInBackground()

        lifecycleScope.launch {
            questions = withContext(Dispatchers.IO) { SpeedrunQuestions.loadMerged(this@StudyLoopActivity) }
            practiceQueue = questions.shuffled()
            showTransition()
        }
    }

    /** Pull-and-merge performance records from Firestore so scores reflect other devices. */
    private fun mergeRemoteRecordsInBackground() {
        if (!SpeedrunFirestoreSync.isConfigured) return
        lifecycleScope.launch(Dispatchers.IO) {
            val pulled = SpeedrunFirestoreSync.pull()
            pulled.forEach { SpeedrunDb.insertPulledRecord(this@StudyLoopActivity, it) }
            if (pulled.isNotEmpty()) Timber.i("speedrun: merged %d remote records", pulled.size)
        }
    }

    /**
     * Pulls newly eval-passed questions from Firestore in the background and updates the local
     * cache.  The updated pool will be used on the *next* session start; the current session
     * continues with the already-loaded [questions].
     */
    private fun refreshGeneratedQuestionsInBackground() {
        if (!SpeedrunQuestionSync.isConfigured) return
        lifecycleScope.launch(Dispatchers.IO) {
            val fetched = SpeedrunQuestionSync.pullAndCache(this@StudyLoopActivity)
            if (fetched.isNotEmpty()) Timber.i("speedrun: refreshed %d generated questions", fetched.size)
        }
    }

    // region session flow

    /** Announces the upcoming block (its type + "Block N of M") with a single Continue button. */
    private fun showTransition() {
        val column = column()
        column.addView(heading(blockTypes[currentBlock].displayName))
        column.addView(body("Block ${currentBlock + 1} of ${blockTypes.size}"))
        column.addView(primaryButton("Continue") { startBlock() })
        setScreen(scroll(column))
    }

    private fun startBlock() {
        when (blockTypes[currentBlock]) {
            BlockType.FLASHCARDS -> startFlashcardBlock()
            BlockType.QUESTIONS -> startQuestionBlock()
        }
    }

    // region flashcard block

    /** Builds a temporary mixed-topic filtered deck and launches the existing reviewer on it. */
    private fun startFlashcardBlock() {
        lifecycleScope.launch {
            activeFilteredDeckId =
                try {
                    createFilteredDeck()
                } catch (e: Exception) {
                    Timber.w(e, "Could not create filtered deck; skipping flashcards for this block")
                    null
                }
            if (activeFilteredDeckId == null) {
                advanceBlock()
            } else {
                reviewLauncher.launch(Reviewer.getIntent(this@StudyLoopActivity))
            }
        }
    }

    private fun onFlashcardsFinished(resultCode: Int) {
        Timber.i("Flashcard block finished with resultCode=%d", resultCode)
        lifecycleScope.launch {
            activeFilteredDeckId?.let { id ->
                try {
                    cleanupFilteredDeck(id)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to clean up temporary filtered deck")
                }
            }
            activeFilteredDeckId = null
            advanceBlock()
        }
    }

    // endregion

    // region question block

    private fun startQuestionBlock() {
        blockQuestions = nextPracticeQuestions(QUESTIONS_PER_BLOCK)
        questionIndex = 0
        showConceptStep()
    }

    /** Step 1: show the passage + question and ask which concept is being tested. */
    private fun showConceptStep() {
        val question = blockQuestions[questionIndex]
        chosenConcept = null
        showChoiceScreen(
            question = question,
            prompt = "What concept is this question testing?",
            options = conceptOptionsFor(question),
            buttonLabel = "Submit concept",
        ) { chosen ->
            chosenConcept = chosen
            showAnswerStep()
        }
    }

    /** Step 2: show the same passage + question and ask for the answer. */
    private fun showAnswerStep() {
        val question = blockQuestions[questionIndex]
        showChoiceScreen(
            question = question,
            prompt = "What is the answer?",
            options = question.choices,
            buttonLabel = "Submit answer",
        ) { chosenAnswer ->
            recordAndReveal(question, chosenAnswer)
        }
    }

    private fun recordAndReveal(
        question: SpeedrunQuestion,
        chosenAnswer: String,
    ) {
        val concept = chosenConcept ?: ""
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    SpeedrunDb.recordAnswer(
                        context = this@StudyLoopActivity,
                        question = question,
                        chosenConcept = concept,
                        chosenAnswer = chosenAnswer,
                    )
                }
            questionsAnswered++
            if (result.conceptCorrect) conceptCorrectCount++
            if (result.answerCorrect) answerCorrectCount++
            // Fire-and-forget push to Firestore; mark synced locally if it succeeds.
            lifecycleScope.launch(Dispatchers.IO) {
                if (SpeedrunFirestoreSync.push(result.record)) {
                    SpeedrunDb.markSynced(this@StudyLoopActivity, result.record.syncKey)
                }
            }
            showReveal(question, result)
        }
    }

    /** Reveals the concept and answer results simultaneously. */
    private fun showReveal(
        question: SpeedrunQuestion,
        result: SpeedrunAnswerResult,
    ) {
        val column = column()
        column.addView(heading("Results"))
        column.addView(
            body(
                if (result.conceptCorrect) {
                    "Concept: Correct"
                } else {
                    "Concept: Incorrect — the concept was \"${question.concept}\""
                },
            ),
        )
        column.addView(
            body(
                if (result.answerCorrect) {
                    "Answer: Correct"
                } else {
                    "Answer: Incorrect — the answer was \"${question.correctAnswer}\""
                },
            ),
        )
        column.addView(
            primaryButton("Continue") {
                questionIndex++
                if (questionIndex < blockQuestions.size) showConceptStep() else advanceBlock()
            },
        )
        setScreen(scroll(column))
    }

    // endregion

    private fun advanceBlock() {
        currentBlock++
        if (currentBlock >= blockTypes.size) showSummary() else showTransition()
    }

    private fun showSummary() {
        val column = column()
        column.addView(heading("Session complete"))
        column.addView(body("Concepts identified correctly: $conceptCorrectCount of $questionsAnswered"))
        column.addView(body("Answers correct: $answerCorrectCount of $questionsAnswered"))
        column.addView(
            primaryButton("View readiness") {
                startActivity(SpeedrunScoreActivity.getIntent(this))
            },
        )
        column.addView(
            primaryButton("Performance") {
                startActivity(SpeedrunScoreActivity.getIntent(this, SpeedrunScoreActivity.Mode.PERFORMANCE))
            },
        )
        column.addView(primaryButton("Finish") { finish() })
        setScreen(scroll(column))
    }

    // endregion

    // region collection helpers (public backend API only)

    private suspend fun createFilteredDeck(): DeckId =
        withCol {
            sched.customStudy(
                customStudyRequest {
                    deckId = homeDeckId
                    cram =
                        cram.copy {
                            // All cards in random order → topics interleaved, not blocked by topic.
                            kind = CramKind.CRAM_KIND_ALL
                            cardLimit = CARDS_PER_FLASHCARD_BLOCK
                        }
                },
            )
            // customStudy creates the filtered deck and makes it the current deck.
            decks.getCurrentId()
        }

    private suspend fun cleanupFilteredDeck(id: DeckId) =
        withCol {
            if (decks.isFiltered(id)) {
                // Return the borrowed cards to their home decks, then delete the temporary deck.
                sched.emptyFilteredDeck(id)
                decks.remove(listOf(id))
                decks.select(homeDeckId)
            }
        }

    // endregion

    // region question selection

    /** Takes the next [count] questions from the shuffled queue, wrapping around if exhausted. */
    private fun nextPracticeQuestions(count: Int): List<SpeedrunQuestion> {
        val picked =
            (0 until count).map { offset ->
                practiceQueue[(practiceCursor + offset) % practiceQueue.size]
            }
        practiceCursor += count
        return picked
    }

    /** Four concept options for a question: the correct concept plus distractors from the same topic. */
    private fun conceptOptionsFor(question: SpeedrunQuestion): List<String> {
        val sameTopicConcepts =
            questions
                .filter { it.topic == question.topic }
                .map { it.concept }
                .distinct()
        val distractors = sameTopicConcepts.filter { it != question.concept }.toMutableList()
        if (distractors.size < CONCEPT_OPTION_COUNT - 1) {
            val extra =
                questions
                    .map { it.concept }
                    .distinct()
                    .filter { it != question.concept && it !in distractors }
            distractors.addAll(extra)
        }
        return (listOf(question.concept) + distractors.take(CONCEPT_OPTION_COUNT - 1)).shuffled()
    }

    // endregion

    // region minimal programmatic UI

    private fun showChoiceScreen(
        question: SpeedrunQuestion,
        prompt: String,
        options: List<String>,
        buttonLabel: String,
        onSubmit: (String) -> Unit,
    ) {
        val column = column()
        column.addView(body(question.passage))
        column.addView(body(question.question))
        column.addView(heading(prompt))

        val group = RadioGroup(this)
        options.forEachIndexed { index, option ->
            group.addView(
                RadioButton(this).apply {
                    text = option
                    id = index + 1
                },
            )
        }
        column.addView(group)

        val submit = primaryButton(buttonLabel) { }
        submit.isEnabled = false
        group.setOnCheckedChangeListener { _, _ -> submit.isEnabled = group.checkedRadioButtonId != -1 }
        submit.setOnClickListener {
            val index = group.checkedRadioButtonId - 1
            if (index in options.indices) onSubmit(options[index])
        }
        column.addView(submit)
        setScreen(scroll(column))
    }

    /** Replaces the current session screen inside the content frame (keeping the toolbar/drawer). */
    private fun setScreen(view: View) {
        contentContainer.removeAllViews()
        contentContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun scroll(child: LinearLayout): ScrollView =
        ScrollView(this).apply {
            addView(
                child,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    private fun column(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

    private fun heading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 20f
            setPadding(0, dp(16), 0, dp(8))
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun primaryButton(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            layoutParams =
                LinearLayout
                    .LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                        gravity = Gravity.END
                    }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val BlockType.displayName: String
        get() =
            when (this) {
                BlockType.FLASHCARDS -> "Flashcards"
                BlockType.QUESTIONS -> "Practice questions"
            }

    // endregion

    companion object {
        /** Cards reviewed per flashcard block (8–10). */
        const val CARDS_PER_FLASHCARD_BLOCK = 10

        /** Practice questions asked per question block (4–5). */
        const val QUESTIONS_PER_BLOCK = 5

        private const val CONCEPT_OPTION_COUNT = 4

        const val EXTRA_DECK_ID = "deckId"

        /** Top-level deck name that triggers the speedrun study loop. */
        const val TRIGGER_DECK_NAME = "AnKing-MCAT"

        fun getIntent(
            context: Context,
            deckId: DeckId,
        ): Intent =
            Intent(context, StudyLoopActivity::class.java).apply {
                putExtra(EXTRA_DECK_ID, deckId)
            }
    }
}
