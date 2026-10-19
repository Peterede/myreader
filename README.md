# MyReader

A natural-language audio ebook reader for Android. Import an EPUB or plain-text
book and have it read aloud, sentence by sentence, with the current sentence
highlighted on screen — like a human narrator pausing at punctuation, not a
robot reading a wall of text.

## Features

- **EPUB & TXT import** — pick any file via the system file picker; EPUB 2/3
  spine order is respected and chapter titles are extracted from real headings.
- **Natural sentence-by-sentence speech** — text is split with an abbreviation-
  and decimal-aware sentence tokenizer, so `Dr. Smith paid 3.50.` is read
  correctly. One utterance per sentence keeps the engine's prosody natural and
  lets the UI highlight exactly what is being spoken.
- **Follow-along highlighting** — the reader scrolls and highlights the
  sentence currently being spoken; tap any sentence to jump there.
- **Full transport controls** — play/pause, previous/next sentence, jump
  chapters, six playback speeds (0.75×–2×), position slider per chapter.
- **Background reading** — a foreground service with a media-style notification
  (prev / play-pause / next / stop) keeps narration running when you leave the
  app, with audio-focus handling (pauses for calls and other apps).
- **Library with progress** — imported books are listed with saved position;
  playback resumes exactly where you stopped, even after the process is killed.
- **Language-aware** — if the EPUB declares a language the device TTS supports,
  that locale is selected automatically.

## Building

Requirements: Android Studio Ladybug+ (or any environment with JDK 17+ and the
Android SDK 35), an Android device/emulator with a text-to-speech engine
installed (most devices ship one; Google Text-to-speech is the default).

```bash
cd myreader
./gradlew assembleDebug          # or open the folder in Android Studio and Run
./gradlew installDebug           # install on a connected device
```

minSdk 26 (Android 8.0), targetSdk 35, Kotlin + Jetpack Compose (Material 3).

## Architecture

```
app/src/main/java/com/myreader/app/
├── MyReaderApp.kt            # Application scope; wires Player -> PlayerService
├── data/
│   ├── Models.kt             # Book / Chapter / BookMeta (+ progress estimate)
│   ├── BookStore.kt          # import, JSON persistence, reading-position store
│   ├── EpubParser.kt         # container.xml -> OPF -> spine -> text
│   └── HtmlText.kt           # XHTML + entity stripping to clean prose
├── tts/
│   ├── SentenceSplitter.kt   # natural sentence tokenizer for speech
│   └── Player.kt             # TextToSpeech engine, state flow, audio focus
├── service/
│   └── PlayerService.kt      # foreground service + media notification
└── ui/
    ├── MainActivity.kt       # library <-> reader navigation
    ├── LibraryScreen.kt      # book list, import picker, progress
    └── ReaderScreen.kt       # highlighted text, transport controls
```

Design notes:

- `Player` lives in the `Application` object, not an Activity, so narration
  survives navigation; activities just observe its `StateFlow`.
- The TTS engine is driven one sentence per utterance (`UtteranceProgressListener`
  chains the next sentence on completion), which also gives exact progress.
- Books are stored as JSON under `files/books/` (`{id}.book.json` content,
  `{id}.meta.json` position/metadata), so the library screen stays cheap.
# myreader
