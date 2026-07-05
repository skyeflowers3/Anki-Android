# MCAT Speedrun — AnkiDroid

An Android flashcard app built on [AnkiDroid](https://github.com/ankidroid/Anki-Android) and extended with a structured MCAT study mode designed to improve performance on MCAT-style questions.

---

## What This App Does

Standard flashcard apps train recognition — you see a card and recall the answer. The MCAT requires more: you need to know **when** to apply a concept, **how** it connects to other ideas, and how to reason under pressure with unfamiliar question formats.

This app adds a **Speedrun Study Mode** on top of AnkiDroid that trains all three:

- **Interleaved study blocks** — Questions from different MCAT sections (B/B, C/P, P/S, CARS) are mixed together within each session, rather than studied one topic at a time. Interleaving forces your brain to retrieve the right framework for each question from scratch, which builds the kind of flexible recall the MCAT actually tests.
- **Concept application grading** — After each question, you identify which concept applies and explain how. The app checks both whether you got the answer right *and* whether you recognized the underlying principle, so you build pattern recognition alongside factual knowledge.
- **Section-level performance tracking** — Three score screens (Memory, Performance, Readiness) break down your accuracy by MCAT section and topic, showing exactly where you're strong and where you need more reps.

---

## Installing the App

A pre-built debug APK (arm64, for most modern Android phones and emulators) is available at:

```
release/AnkiDroid-arm64-debug.apk
```

**Steps:**
1. Download the APK from this repo (`release/AnkiDroid-arm64-debug.apk`)
2. Transfer it to your Android device
3. Tap the file to install — you may need to enable **"Install from unknown sources"** in Settings → Security
4. Open the app — the MCAT deck imports automatically on first launch

> The bundled deck covers all four MCAT sections: Biology/Biochemistry (B/B), Chemistry/Physics (C/P), Psychology/Sociology (P/S), and CARS.

---

## Score Screens

Access via the hamburger menu (☰):

| Screen | What it shows |
|---|---|
| **Memory** | Raw recall accuracy per section — how often you remember the correct answer |
| **Performance** | Concept application accuracy — how often you correctly identify *which* concept applies and *how* |
| **Readiness** | Combined score across both dimensions, with a per-section breakdown and overall readiness estimate |

Each section requires a minimum number of answered questions before showing a score (to avoid misleading early percentages).

---

## Sync

The app syncs study data across devices via Firestore. Records are pushed automatically when you reconnect to Wi-Fi after a session. AnkiWeb sync (for the card deck itself) also triggers automatically on reconnect.

---

## Built On

This is a fork of [AnkiDroid](https://github.com/ankidroid/Anki-Android) (GPL-3.0). The Speedrun Study Mode is original work layered on top of the existing AnkiDroid infrastructure.
