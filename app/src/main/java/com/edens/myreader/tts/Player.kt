package com.edens.myreader.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.edens.myreader.data.Book
import com.edens.myreader.data.BookStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/** Gender of an installed voice, inferred from the engine's voice name. */
enum class VoiceGender { MALE, FEMALE, UNKNOWN }

/**
 * A speech engine installed on the device: any app that registers an Android
 * text-to-speech service, such as Google's engine, Maise or Supertonic.
 */
data class EngineOption(
    val packageName: String,
    val label: String,
    /** True for the engine Android is configured to use by default. */
    val isSystemDefault: Boolean,
)

/** A voice offered by the current TTS engine. */
data class VoiceOption(
    val name: String,
    val locale: String,
    val gender: VoiceGender,
    /** Human-readable accent (e.g. "American English"), when the locale is known. */
    val accent: String?,
    val label: String,
    /** True for voices synthesised by the engine's server; needs a connection. */
    val online: Boolean = false,
)

/** A character speaking in the book, and the voice reading their lines. */
data class CastMember(
    /** The name as it appears in the book. */
    val name: String,
    /** Engine voice name, or null when no voice could be assigned. */
    val voiceName: String?,
    /** Number of quoted lines, used to rank the cast. */
    val lineCount: Int = 0,
)

/**
 * Owns the [TextToSpeech] engine and all playback state.
 *
 * Speech is produced in paragraph-sized chunks (several sentences per
 * utterance) so the engine keeps continuous, natural prosody instead of
 * restarting its intonation for every sentence. Chunks never span a paragraph
 * break, so the engine only applies its emphatic utterance-final contour where
 * the book itself ends a paragraph — not at arbitrary full stops mid-paragraph.
 * [UtteranceProgressListener.onRangeStart] reports the character position inside
 * the utterance as it is spoken, which is mapped back to the current sentence so
 * the UI stays in sync. After a seek or pause/resume the chunk is rebuilt
 * starting at the selected sentence. The chunk after the one playing is queued
 * behind it, with a short beat of engine-rendered silence at paragraph breaks
 * and a longer one between chapters, so paragraphs are paced like an audiobook
 * while the engine synthesises the next one ahead of time instead of leaving a
 * gap in between.
 */
class Player(context: Context, private val store: BookStore) {

    data class PlayerState(
        val bookId: String? = null,
        val title: String = "",
        val chapterTitles: List<String> = emptyList(),
        val chapterIndex: Int = 0,
        val sentenceIndex: Int = 0,
        val sentenceCount: Int = 0,
        val isPlaying: Boolean = false,
        val isReady: Boolean = false,
        val speed: Float = 1.0f,
        val finished: Boolean = false,
        val error: String? = null,
        /** Name of the explicitly chosen voice, or null for the engine default. */
        val voiceName: String? = null,
        /** Voices available for the book's language (all voices if none match). */
        val voices: List<VoiceOption> = emptyList(),
        /** Package of the speech engine in use, or null for the system default. */
        val enginePackage: String? = null,
        /** Speech engines installed on the device. */
        val engines: List<EngineOption> = emptyList(),
        /** True when each character speaks in their own voice. */
        val multiVoice: Boolean = false,
        /** Characters detected in the book, with their assigned voices. */
        val cast: List<CastMember> = emptyList(),
        /**
         * Changes whenever the spoken layout does (a book opens, multi-voice
         * is toggled, a line is reattributed), so the reader can refresh the
         * speakers it shows against each line.
         */
        val narrationRevision: Int = 0,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    /**
     * Held for as long as narration is playing. The engine's audio pipeline
     * only keeps the device awake while sound is actually rendering, so with
     * the screen off the device suspended in the silent gap between chunks and
     * the delayed hand-off to the next paragraph fired late — or only once an
     * unlock or notification woke the device. A partial wake lock covers those
     * gaps.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(
        PlayerState(
            voiceName = prefs.getString(KEY_VOICE, null),
            multiVoice = prefs.getBoolean(KEY_MULTI_VOICE, false),
        )
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsCreated = false

    /** Set when a chosen engine failed to start, so this session uses the default. */
    private var forceDefaultEngine = false
    private var book: Book? = null
    private var sentences: List<List<String>> = emptyList()

    /** Per chapter: true where a sentence begins a new paragraph. */
    private var sentenceStartsParagraph: List<BooleanArray> = emptyList()

    /** Characters found while analysing the book's dialogue. */
    private var detectedCast: List<DialogueSpeaker> = emptyList()

    /** The book laid out for multi-voice narration, and for a single narrator. */
    private var dialogueParts: List<List<SpeechPart>> = emptyList()
    private var plainParts: List<List<SpeechPart>> = emptyList()

    /** The analysed dialogue, before the reader's speaker corrections. */
    private var analyzedParts: List<List<SpeechPart>> = emptyList()

    /** Reader corrections, keyed "chapter:sentence". */
    private var speakerOverrides: Map<String, String> = emptyMap()

    /** The layout currently being spoken, per chapter. */
    private var spoken: List<SpokenChapter> = emptyList()

    private var currentUtterance: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    /**
     * One utterance handed to the engine. The chunk it speaks is planned when it
     * is queued, so playback never depends on the main looper keeping up while
     * the screen is off, where in doze both delayed messages and wake locks are
     * unreliable.
     */
    private data class Utterance(
        val id: String,
        val chapter: Int,
        val start: Int,
        val size: Int,
        val offsets: IntArray,
    )

    /** A chunk planned for a chapter, ready to be spoken. */
    private data class Planned(val chapter: Int, val chunk: SpeechChunk)

    /**
     * A chapter laid out as the pieces the engine will speak, in order. A part
     * is a sentence, or a slice of one when narration and dialogue share it.
     */
    private class SpokenChapter(parts: List<SpeechPart>) {
        val texts: List<String> = parts.map { it.text }
        val speakers: Array<String?> = parts.map { it.speaker }.toTypedArray()
        val startsParagraph: BooleanArray = parts.map { it.startsParagraph }.toBooleanArray()
        val sentences: IntArray = parts.map { it.sentence }.toIntArray()
        val size: Int get() = texts.size

        /** Index of the first piece in [sentence] or a later one. */
        fun firstPartAtOrAfter(sentence: Int): Int {
            val index = sentences.indexOfFirst { it >= sentence }
            return if (index < 0) size else index
        }
    }

    /** Utterances the engine still has to play, the one being spoken first. */
    private val utteranceQueue = ArrayDeque<Utterance>()
    private val utteranceById = HashMap<String, Utterance>()

    // ---------------------------------------------------------------- lifecycle

    fun open(bookId: String) {
        if (_state.value.bookId == bookId && _state.value.isReady) return
        pause()
        val loaded = store.load(bookId)
        if (loaded == null) {
            _state.update { it.copy(error = "Book not found") }
            return
        }
        book = loaded
        val paragraphs = loaded.chapters.map { chapter ->
            chapter.text.split(PARAGRAPH_BREAK)
                .map { SentenceSplitter.split(it) }
                .filter { it.isNotEmpty() }
        }
        sentences = paragraphs.map { chapter -> chapter.flatten() }
        sentenceStartsParagraph = paragraphs.map { chapter -> paragraphStarts(chapter) }
        layOutNarration()
        val ci = loaded.meta.chapterIndex.coerceIn(0, loaded.chapters.lastIndex)
        val si = loaded.meta.sentenceIndex.coerceIn(
            0,
            (sentences.getOrNull(ci)?.lastIndex) ?: 0,
        )
        _state.value = PlayerState(
            bookId = bookId,
            title = loaded.meta.title,
            chapterTitles = loaded.chapters.map { it.title },
            chapterIndex = ci,
            sentenceIndex = si,
            sentenceCount = sentences[ci].size,
            isReady = true,
            speed = _state.value.speed,
            voiceName = _state.value.voiceName,
            voices = _state.value.voices,
            enginePackage = _state.value.enginePackage,
            engines = _state.value.engines,
            multiVoice = _state.value.multiVoice,
            narrationRevision = _state.value.narrationRevision + 1,
        )
        ensureTts()
        applySpeechSetup()
    }

    /**
     * Works out who speaks each sentence and prepares both layouts: the plain
     * one used for single-voice narration and the split one used when every
     * character has their own voice.
     */
    private fun layOutNarration() {
        val analysis = sentences.mapIndexed { index, chapterSentences ->
            DialogueAnalyzer.analyze(
                chapterSentences,
                sentenceStartsParagraph.getOrNull(index),
            )
        }
        detectedCast = analysis
            .flatMap { it.speakers }
            .groupBy { it.name }
            .map { (name, lines) -> DialogueSpeaker(name, lines.sumOf { it.lineCount }) }
            .sortedByDescending { it.lineCount }
        analyzedParts = analysis.map { it.parts }
        speakerOverrides = book?.meta?.id?.let { loadSpeakerOverrides(it) }.orEmpty()
        dialogueParts = analyzedParts.mapIndexed { chapter, parts ->
            withOverrides(chapter, parts)
        }
        plainParts = sentences.mapIndexed { index, chapterSentences ->
            val starts = sentenceStartsParagraph.getOrNull(index)
            chapterSentences.mapIndexed { sentence, text ->
                SpeechPart(
                    sentence = sentence,
                    speaker = null,
                    text = text,
                    startsParagraph = starts?.getOrNull(sentence) == true,
                )
            }
        }
        applyParts()
    }

    /** Selects the layout matching the current multi-voice setting. */
    private fun applyParts() {
        val source = if (_state.value.multiVoice) dialogueParts else plainParts
        spoken = source.map { SpokenChapter(it) }
    }

    fun play() {
        val st = _state.value
        if (!st.isReady || st.isPlaying) return
        ensureTts()
        requestAudioFocus()
        acquireWakeLock()
        _state.update { it.copy(isPlaying = true, finished = false) }
        restartSpeech()
    }

    fun pause() = pauseInternal(abandonFocus = true)

    private fun pauseInternal(abandonFocus: Boolean) {
        releaseWakeLock()
        clearSpeechQueue()
        if (abandonFocus) {
            resumeOnFocusGain = false
            abandonAudioFocus()
        }
        if (!_state.value.isPlaying) return
        tts?.stop()
        _state.update { it.copy(isPlaying = false) }
        persist()
    }

    fun toggle() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun stop() {
        persist()
        pause()
        tts?.shutdown()
        tts = null
        ttsCreated = false
        book = null
        sentences = emptyList()
        sentenceStartsParagraph = emptyList()
        detectedCast = emptyList()
        dialogueParts = emptyList()
        plainParts = emptyList()
        analyzedParts = emptyList()
        speakerOverrides = emptyMap()
        spoken = emptyList()
        _state.value = PlayerState(
            speed = _state.value.speed,
            voiceName = _state.value.voiceName,
            voices = _state.value.voices,
            enginePackage = _state.value.enginePackage,
            engines = _state.value.engines,
            multiVoice = _state.value.multiVoice,
        )
    }

    fun nextSentence() {
        val st = _state.value
        if (!st.isReady) return
        if (st.sentenceIndex + 1 < st.sentenceCount) {
            moveTo(st.chapterIndex, st.sentenceIndex + 1)
        } else {
            nextChapter()
        }
    }

    fun previousSentence() {
        val st = _state.value
        if (!st.isReady) return
        if (st.sentenceIndex > 0) {
            moveTo(st.chapterIndex, st.sentenceIndex - 1)
        } else {
            prevChapter()
        }
    }

    fun nextChapter() {
        val st = _state.value
        if (!st.isReady) return
        if (st.chapterIndex + 1 < st.chapterTitles.size) {
            moveTo(st.chapterIndex + 1, 0)
        } else if (st.isPlaying) {
            endOfBook()
        }
    }

    fun prevChapter() {
        val st = _state.value
        if (!st.isReady) return
        if (st.chapterIndex == 0) {
            moveTo(0, 0)
        } else {
            moveTo(st.chapterIndex - 1, 0)
        }
    }

    fun seekToSentence(index: Int) {
        val st = _state.value
        if (!st.isReady) return
        moveTo(st.chapterIndex, index.coerceIn(0, st.sentenceCount - 1))
    }

    fun cycleSpeed() {
        val current = _state.value.speed
        val next = speeds[((speeds.indexOf(current) + 1).mod(speeds.size))]
        setSpeed(next)
    }

    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
        _state.update { it.copy(speed = speed) }
    }

    /**
     * Selects the speech engine by its package name ([packageName]), or null
     * for the system default, and restarts narration with it so the change is
     * heard immediately.
     */
    fun setEngine(packageName: String?) {
        prefs.edit()
            .putString(KEY_ENGINE, packageName ?: SYSTEM_ENGINE)
            .apply()
        forceDefaultEngine = false
        if (_state.value.enginePackage == packageName) return

        val playing = _state.value.isPlaying
        clearSpeechQueue()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsCreated = false
        // Voices belong to the engine, so the old list is stale; the new one
        // arrives once the engine is connected.
        _state.update { it.copy(enginePackage = packageName, voices = emptyList()) }
        ensureTts()
        if (playing) {
            requestAudioFocus()
            restartSpeech()
        }
    }

    /**
     * Selects a narration voice by its engine name; null restores the default
     * voice for the book's language.
     */
    fun setVoice(name: String?) {
        prefs.edit().apply {
            if (name == null) remove(KEY_VOICE) else putString(KEY_VOICE, name)
        }.apply()
        _state.update { it.copy(voiceName = name) }
        refreshCast()

        val tts = tts ?: return
        val voice = name?.let { n -> availableVoices().firstOrNull { it.name == n } }
        if (voice != null) {
            runCatching { tts.voice = voice }
        } else {
            applyLanguage()
            applyVoice()
        }

        // Restart the current chunk so the change is heard immediately.
        if (_state.value.isPlaying) restartSpeech()
    }

    /**
     * Turns multi-voice narration on or off. With it on, quoted dialogue is
     * read by each character's own voice and narration by the narrator's.
     */
    fun setMultiVoice(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MULTI_VOICE, enabled).apply()
        if (_state.value.multiVoice == enabled) return
        _state.update {
            it.copy(multiVoice = enabled, narrationRevision = it.narrationRevision + 1)
        }
        applyParts()
        refreshCast()
        if (_state.value.isPlaying) restartSpeech()
    }

    /**
     * Assigns [voiceName] to [name] in this book, or null to let the player
     * choose a voice for that character automatically.
     */
    fun setCastVoice(name: String, voiceName: String?) {
        val bookId = book?.meta?.id ?: return
        val saved = loadCastVoices(bookId).toMutableMap()
        if (voiceName == null) saved.remove(name) else saved[name] = voiceName
        prefs.edit()
            .putString(KEY_CAST_PREFIX + bookId, json.encodeToString(saved.toMap()))
            .apply()
        refreshCast()
        if (_state.value.isPlaying) restartSpeech()
    }

    /**
     * Forces [speaker] to read every line of one sentence; null makes the
     * narrator read it. The correction is stored for this book.
     */
    fun setSentenceSpeaker(chapter: Int, sentence: Int, speaker: String?) {
        saveSpeakerOverride(overrideKey(chapter, sentence), speaker ?: NARRATOR_OVERRIDE)
    }

    /** Drops the correction for one sentence, restoring the detected speaker. */
    fun clearSentenceSpeaker(chapter: Int, sentence: Int) {
        saveSpeakerOverride(overrideKey(chapter, sentence), null)
    }

    /** True when the reader has set who speaks this sentence. */
    fun hasSpeakerOverride(chapter: Int, sentence: Int): Boolean =
        speakerOverrides.containsKey(overrideKey(chapter, sentence))

    /** True when the reader forced the narrator onto this sentence. */
    fun isNarratorOverride(chapter: Int, sentence: Int): Boolean =
        speakerOverrides[overrideKey(chapter, sentence)] == NARRATOR_OVERRIDE

    /**
     * The character reading each sentence of [chapterIndex], or null for the
     * narrator. The first dialogue piece of a sentence is what shows.
     */
    fun speakersFor(chapterIndex: Int): List<String?> {
        val chapter = spoken.getOrNull(chapterIndex) ?: return emptyList()
        val result = arrayOfNulls<String>(sentences.getOrNull(chapterIndex)?.size ?: 0)
        for (part in chapter.texts.indices) {
            val speaker = chapter.speakers[part] ?: continue
            val sentence = chapter.sentences[part]
            if (sentence in result.indices && result[sentence] == null) result[sentence] = speaker
        }
        return result.toList()
    }

    private fun overrideKey(chapter: Int, sentence: Int): String = "$chapter:$sentence"

    private fun saveSpeakerOverride(key: String, speaker: String?) {
        val bookId = book?.meta?.id ?: return
        val saved = loadSpeakerOverrides(bookId).toMutableMap()
        if (speaker == null) saved.remove(key) else saved[key] = speaker
        prefs.edit()
            .putString(KEY_SPEAKER_PREFIX + bookId, json.encodeToString(saved.toMap()))
            .apply()
        speakerOverrides = saved
        dialogueParts = analyzedParts.mapIndexed { chapter, parts ->
            withOverrides(chapter, parts)
        }
        applyParts()
        _state.update { it.copy(narrationRevision = it.narrationRevision + 1) }
        if (_state.value.isPlaying) restartSpeech()
    }

    /** Reapplies the reader's corrections over one chapter's analysed parts. */
    private fun withOverrides(chapter: Int, parts: List<SpeechPart>): List<SpeechPart> {
        if (speakerOverrides.isEmpty()) return parts
        val dialogueSentences = parts.filter { it.isDialogue }
            .mapTo(HashSet()) { it.sentence }
        return parts.map { part ->
            val choice = speakerOverrides[overrideKey(chapter, part.sentence)]
                ?: return@map part
            // A correction is about who said the line, so the narration tag
            // around a quote keeps the narrator's voice.
            if (!part.isDialogue && part.sentence in dialogueSentences) return@map part
            part.copy(speaker = if (choice == NARRATOR_OVERRIDE) null else choice)
        }
    }

    private fun loadSpeakerOverrides(bookId: String): Map<String, String> =
        prefs.getString(KEY_SPEAKER_PREFIX + bookId, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty()

    /** Recomputes the cast's voices from the saved choices and the live list. */
    private fun refreshCast() {
        if (detectedCast.isEmpty()) {
            _state.update { it.copy(cast = emptyList()) }
            return
        }
        val voices = _state.value.voices
        val saved = book?.meta?.id?.let { loadCastVoices(it) }.orEmpty()
        val auto = autoVoices(detectedCast, voices, _state.value.voiceName)
        _state.update { state ->
            state.copy(
                cast = detectedCast.map { member ->
                    val chosen = saved[member.name]
                        ?.takeIf { name -> voices.any { it.name == name } }
                    CastMember(member.name, chosen ?: auto[member.name], member.lineCount)
                },
            )
        }
    }

    /**
     * Spreads the installed voices over the cast, keeping the narrator's voice
     * for narration and alternating genders so two characters rarely sound the
     * same. The result is deterministic, so a book sounds the same each time.
     */
    private fun autoVoices(
        cast: List<DialogueSpeaker>,
        voices: List<VoiceOption>,
        narrator: String?,
    ): Map<String, String> {
        val usable = voices.filter { it.name != narrator }
            .ifEmpty { voices }
            .sortedBy { it.online }
        if (usable.isEmpty()) return emptyMap()
        val female = usable.filter { it.gender == VoiceGender.FEMALE }
        val male = usable.filter { it.gender == VoiceGender.MALE }
        val other = usable.filter { it.gender == VoiceGender.UNKNOWN }
        val ordered = buildList {
            for (index in 0 until maxOf(female.size, male.size)) {
                female.getOrNull(index)?.let { add(it.name) }
                male.getOrNull(index)?.let { add(it.name) }
            }
            other.forEach { add(it.name) }
        }
        return cast.mapIndexed { index, member ->
            member.name to ordered[index % ordered.size]
        }.toMap()
    }

    private fun loadCastVoices(bookId: String): Map<String, String> =
        prefs.getString(KEY_CAST_PREFIX + bookId, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty()

    /** The narrator's voice: the reader's pick, or the best installed one. */
    private fun narratorVoiceName(): String? =
        _state.value.voiceName
            ?: recommendedVoice()?.name
            ?: _state.value.voices.firstOrNull()?.name

    /** The voice that should read [speaker]'s lines right now. */
    private fun voiceFor(speaker: String?): String? {
        if (!_state.value.multiVoice || speaker == null) return narratorVoiceName()
        val member = _state.value.cast.firstOrNull { it.name == speaker }
        return member?.voiceName ?: narratorVoiceName()
    }

    private fun selectEngineVoice(name: String?) {
        val tts = tts ?: return
        val voice = name
            ?.let { wanted -> availableVoices().firstOrNull { it.name == wanted } }
            ?: return
        runCatching { tts.voice = voice }
    }

    fun sentencesFor(chapterIndex: Int): List<String> =
        sentences.getOrNull(chapterIndex).orEmpty()

    fun endOfBook() {
        releaseWakeLock()
        clearSpeechQueue()
        resumeOnFocusGain = false
        tts?.stop()
        _state.update { st ->
            st.copy(
                isPlaying = false,
                finished = true,
                sentenceIndex = sentences.getOrNull(st.chapterIndex)?.lastIndex ?: 0,
            )
        }
        abandonAudioFocus()
        persist()
    }

    // ------------------------------------------------------------------- speech

    private fun ensureTts() {
        if (ttsCreated) return
        ttsCreated = true
        val enginePackage = resolveEnginePackage()
        _state.update { it.copy(enginePackage = enginePackage) }
        val listener = TextToSpeech.OnInitListener { status ->
            mainHandler.post { onTtsInit(enginePackage, status) }
        }
        tts = if (enginePackage != null) {
            TextToSpeech(appContext, listener, enginePackage)
        } else {
            TextToSpeech(appContext, listener)
        }
    }

    /**
     * Runs once the engine is connected. A chosen engine can still fail to
     * start (uninstalled, model missing, crashed), which must not leave
     * narration permanently broken: fall back to the system default and store
     * that choice so the next launch starts cleanly.
     */
    private fun onTtsInit(enginePackage: String?, status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            if (enginePackage != null) {
                forceDefaultEngine = true
                tts?.shutdown()
                tts = null
                ttsCreated = false
                if (prefs.getString(KEY_ENGINE, null) == enginePackage) {
                    prefs.edit().putString(KEY_ENGINE, SYSTEM_ENGINE).apply()
                }
                ensureTts()
                return
            }
            ttsCreated = false
            releaseWakeLock()
            _state.update {
                it.copy(
                    isPlaying = false,
                    error = "Text-to-speech engine is unavailable. Install/enable a TTS engine in system settings.",
                )
            }
            return
        }
        tts?.setOnUtteranceProgressListener(utteranceListener)
        applySpeechSetup()
        if (_state.value.isPlaying) restartSpeech()
    }

    /**
     * The engine to speak with: the stored choice while it is still installed,
     * otherwise the first preferred engine that is installed, otherwise the
     * system default.
     */
    private fun resolveEnginePackage(): String? {
        if (forceDefaultEngine) return null
        val installed = installedEngines().map { it.packageName }
        return when (val pref = prefs.getString(KEY_ENGINE, null)) {
            SYSTEM_ENGINE -> null
            null -> PREFERRED_ENGINES.firstOrNull { it in installed }
            else -> pref.takeIf { it in installed }
        }
    }

    /**
     * Speech engines installed on the device. Seeing anything other than the
     * default engine relies on the TTS_SERVICE `<queries>` entry in the
     * manifest (Android 11 package visibility).
     */
    private fun installedEngines(): List<EngineOption> {
        val pm = appContext.packageManager
        val defaultEngine = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        }.getOrNull()
        return runCatching {
            pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .mapNotNull { it.serviceInfo }
                .distinctBy { it.packageName }
                .map { service ->
                    EngineOption(
                        packageName = service.packageName,
                        label = runCatching { service.loadLabel(pm).toString() }
                            .getOrDefault(service.packageName),
                        isSystemDefault = service.packageName == defaultEngine,
                    )
                }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
        }.getOrDefault(emptyList())
    }

    /** Applies language, saved voice and speed, then refreshes the pickers. */
    private fun applySpeechSetup() {
        applyLanguage()
        applyVoice()
        tts?.setSpeechRate(_state.value.speed)
        refreshVoices()
        refreshCast()
        _state.update { it.copy(engines = installedEngines()) }
    }

    private fun applyLanguage() {
        val language = book?.meta?.language ?: return
        val tts = tts ?: return
        runCatching {
            val locale = Locale.forLanguageTag(language)
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
            }
        }
    }

    /**
     * Applies the chosen voice, or the best installed one when none is chosen,
     * dropping a persisted voice that is no longer usable.
     */
    private fun applyVoice() {
        val tts = tts ?: return
        // Before the engine is connected it reports no voices; applying the
        // stored one then would drop a perfectly good choice.
        val voices = availableVoices()
        if (voices.isEmpty()) return
        val stored = _state.value.voiceName
        if (stored == null) {
            recommendedVoice()?.let { voice -> runCatching { tts.voice = voice } }
            return
        }
        val voice = voices.firstOrNull { it.name == stored }
        val bookLocale = book?.meta?.language?.let { languageOf(it) }
        val mismatched = voice != null && bookLocale != null &&
            !sameLanguage(spokenLocale(voice), bookLocale)
        if (voice == null || mismatched) {
            prefs.edit().remove(KEY_VOICE).apply()
            _state.update { it.copy(voiceName = null) }
            recommendedVoice()?.let { fallback -> runCatching { tts.voice = fallback } }
            return
        }
        runCatching { tts.voice = voice }
    }

    /**
     * Picks the most natural installed voice for the book's language.
     *
     * Engines are free to default to a tiny "embedded" voice, whose prosody is
     * crude — words before punctuation get a clipped, over-stressed contour.
     * The highest-quality installed voice that also works offline (no network
     * round trip, no text leaving the device) is a much better default; the
     * user can still pick any other voice explicitly.
     */
    private fun recommendedVoice(): Voice? {
        val deviceLocale = Locale.getDefault()
        val bookLocale = book?.meta?.language?.let { languageOf(it) }
        val candidates = availableVoices()
            .filter { sameLanguage(spokenLocale(it), bookLocale ?: deviceLocale) }
            .filter { !it.isNetworkConnectionRequired }
        return candidates.sortedWith(
            compareByDescending<Voice> {
                localeScore(spokenLocale(it), bookLocale?.country.orEmpty(), deviceLocale.country)
            }
                .thenByDescending { it.quality }
                .thenBy { it.name },
        ).firstOrNull()
    }

    /**
     * The book's own region wins, then the device's, so an `en` book on a US
     * phone gets an American voice rather than whichever English variant
     * happens to sort first.
     */
    private fun localeScore(locale: Locale, bookCountry: String, deviceCountry: String): Int =
        when {
            bookCountry.isNotEmpty() && locale.country.equals(bookCountry, ignoreCase = true) -> 2
            deviceCountry.isNotEmpty() &&
                locale.country.equals(deviceCountry, ignoreCase = true) -> 1

            else -> 0
        }

    private fun availableVoices(): List<Voice> =
        runCatching { tts?.voices }.getOrNull().orEmpty().toList()

    /**
     * The Maise voice id split into the language it was trained on and a
     * readable name. Other engines' ids do not end in `-kokoro`, so they do
     * not match.
     */
    private fun maiseVoice(voice: Voice): MaiseVoices.Parsed? =
        MaiseVoices.parse(voice.name)

    /**
     * The language a voice really speaks. Maise reports every voice as en-US
     * and encodes the real language only in its id, so a German or British
     * voice would not match the book's language without this.
     */
    private fun spokenLocale(voice: Voice): Locale {
        val tag = maiseVoice(voice)?.localeTag ?: return voice.locale
        return languageOf(tag) ?: voice.locale
    }

    /**
     * Android does not expose a voice's gender, but engines name their voices
     * accordingly: Google's `#female_1`, Supertonic's `en-supertonic-F3` and
     * `...-M1`. Both spellings are recognised here.
     */
    private fun genderOf(voice: Voice): VoiceGender {
        val name = voice.name.lowercase(Locale.ROOT)
        return when {
            name.contains("female") -> VoiceGender.FEMALE
            name.contains("male") -> VoiceGender.MALE
            else -> nameNumber(name)?.first ?: VoiceGender.UNKNOWN
        }
    }

    /**
     * The gender and number a short voice token carries (`F3`, `M1`), so an
     * engine like Supertonic can be listed as "Female 3" / "Male 1" instead of
     * ten raw ids that mean nothing to a reader.
     */
    private fun nameNumber(lowercaseName: String): Pair<VoiceGender, Int>? {
        val match = VOICE_TOKEN.find(lowercaseName) ?: return null
        val number = match.groupValues[2].toIntOrNull() ?: return null
        val gender = if (match.groupValues[1] == "f") VoiceGender.FEMALE else VoiceGender.MALE
        return gender to number
    }

    private fun accentOf(locale: Locale): String? {
        if (!locale.language.equals("en", ignoreCase = true)) return null
        return when (locale.country.uppercase(Locale.ROOT)) {
            "US" -> "American English"
            "GB" -> "British English"
            "AU" -> "Australian English"
            "CA" -> "Canadian English"
            "IN" -> "Indian English"
            "IE" -> "Irish English"
            "NZ" -> "New Zealand English"
            "ZA" -> "South African English"
            "SG" -> "Singapore English"
            else -> null
        }
    }

    private fun refreshVoices() {
        val all = availableVoices().filter { it.locale.language.isNotBlank() }
        if (all.isEmpty()) {
            _state.update { it.copy(voices = emptyList()) }
            return
        }
        val target = book?.meta?.language?.let { languageOf(it) } ?: Locale.getDefault()
        val matching = all.filter { sameLanguage(spokenLocale(it), target) }
        val usable = (if (matching.isNotEmpty()) matching else all)
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    .thenBy { spokenLocale(it).toLanguageTag() }
                    .thenBy { it.name },
            )

        var female = 0
        var male = 0
        val options = usable.map { voice ->
            val gender = genderOf(voice)
            val accent = accentOf(spokenLocale(voice))
            val maise = maiseVoice(voice)
            // Keep the engine's own numbering (Supertonic's F3 stays "Female 3")
            // and count only the voices that do not carry one.
            if (gender == VoiceGender.FEMALE) female++
            if (gender == VoiceGender.MALE) male++
            val engineNumber = nameNumber(voice.name.lowercase(Locale.ROOT))?.second
            val base = when {
                maise != null -> maise.name
                gender == VoiceGender.FEMALE -> "Female ${engineNumber ?: female}"
                gender == VoiceGender.MALE -> "Male ${engineNumber ?: male}"
                else -> voice.name
            }
            val parts = listOfNotNull(
                base,
                accent,
                if (voice.isNetworkConnectionRequired) "online" else null,
            )
            VoiceOption(
                name = voice.name,
                locale = voice.locale.toLanguageTag(),
                gender = gender,
                accent = accent,
                label = parts.joinToString(" · "),
                online = voice.isNetworkConnectionRequired,
            )
        }
        _state.update { it.copy(voices = options) }
    }

    private fun languageOf(tag: String): Locale? =
        runCatching { Locale.forLanguageTag(tag) }.getOrNull()
            ?.takeIf { it.language.isNotBlank() }

    private fun sameLanguage(a: Locale, b: Locale): Boolean {
        if (a.language.equals(b.language, ignoreCase = true)) return true
        val a3 = runCatching { a.isO3Language }.getOrNull()
        val b3 = runCatching { b.isO3Language }.getOrNull()
        return a3 != null && a3.equals(b3, ignoreCase = true)
    }

    // ------------------------------------------------------------ speech queue

    /** Drops everything handed to the engine, without stopping the engine. */
    private fun clearSpeechQueue() {
        currentUtterance = null
        utteranceQueue.clear()
        utteranceById.clear()
    }

    /**
     * Restarts narration at the current sentence, replacing whatever the engine
     * still had queued.
     */
    private fun restartSpeech() {
        clearSpeechQueue()
        tts?.stop()
        if (!_state.value.isPlaying) return
        if (!enqueueNext(flush = true)) return
        fillQueue()
    }

    /**
     * Keeps one whole chunk queued behind the one being spoken, so the engine
     * synthesises the next paragraph while the current one plays. The hand-off
     * then costs only the beat itself instead of the engine's start-up.
     */
    private fun fillQueue() {
        while (utteranceQueue.size < QUEUE_DEPTH) {
            if (!enqueueNext(flush = false)) return
        }
    }

    /**
     * Hands the next chunk to the engine. [flush] replaces the queue and starts
     * narration; otherwise the chunk is appended behind what is already playing.
     * A paragraph or chapter break is rendered as engine-side silence first, so
     * the break cannot stretch into dead air.
     *
     * Returns false when there is nothing left to speak.
     */
    private fun enqueueNext(flush: Boolean): Boolean {
        val tts = tts ?: return false
        val position = nextPosition() ?: return false
        val planned = planFrom(position.first, position.second) ?: return false
        val chapter = planned.chapter
        val chunk = planned.chunk

        if (!flush) {
            val beat = beatBefore(chapter, chunk.start)
            if (beat > 0) {
                runCatching {
                    tts.playSilentUtterance(
                        beat,
                        TextToSpeech.QUEUE_ADD,
                        "$SILENCE_PREFIX$chapter:${chunk.start}",
                    )
                }
            }
        }

        val utterance = Utterance(
            id = "$chapter:${chunk.start}",
            chapter = chapter,
            start = chunk.start,
            size = chunk.sentenceCount,
            offsets = chunk.sentenceOffsets,
        )
        val params = Bundle()
        val voiceName = voiceFor(chunk.speaker)
        if (voiceName != null) {
            params.putString(VOICE_PARAM, voiceName)
            // Engines that ignore the per-utterance parameter still pick up the
            // voice set here, because they synthesise queued text in order.
            selectEngineVoice(voiceName)
        }
        val queued = runCatching {
            tts.speak(
                chunk.text,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                params,
                utterance.id,
            ) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!queued) return false

        utteranceById[utterance.id] = utterance
        utteranceQueue.addLast(utterance)
        if (flush) {
            currentUtterance = utterance.id
            val startSentence = spoken.getOrNull(chapter)
                ?.sentences?.getOrNull(chunk.start) ?: 0
            if (chapter != _state.value.chapterIndex ||
                startSentence != _state.value.sentenceIndex
            ) {
                _state.update {
                    it.copy(
                        chapterIndex = chapter,
                        sentenceIndex = startSentence,
                        sentenceCount = sentences[chapter].size,
                    )
                }
            }
        }
        return true
    }

    /** Where the next chunk starts: after what is queued, or at the cursor. */
    private fun nextPosition(): Pair<Int, Int>? {
        val tail = utteranceQueue.lastOrNull()
        if (tail == null) {
            val st = _state.value
            val chapter = spoken.getOrNull(st.chapterIndex) ?: return null
            val at = chapter.firstPartAtOrAfter(st.sentenceIndex)
            return if (at < chapter.size) {
                st.chapterIndex to at
            } else {
                nextChapterPosition(st.chapterIndex)
            }
        }
        val after = tail.start + tail.size
        return if (after < (spoken.getOrNull(tail.chapter)?.size ?: 0)) {
            tail.chapter to after
        } else {
            nextChapterPosition(tail.chapter)
        }
    }

    private fun nextChapterPosition(chapter: Int): Pair<Int, Int>? =
        if (chapter + 1 < sentences.size) (chapter + 1) to 0 else null

    /**
     * Plans the chunk starting at [from] in [chapter], stepping over sentences
     * that are silent once normalized and rolling on into the next chapter when
     * this one runs out. Null at the end of the book.
     */
    private fun planFrom(chapter: Int, from: Int): Planned? {
        var ch = chapter
        var at = from
        while (true) {
            val chapterParts = spoken.getOrNull(ch) ?: return null
            if (at >= chapterParts.size) {
                val next = nextChapterPosition(ch) ?: return null
                ch = next.first
                at = next.second
                continue
            }
            val chunk = SpeechChunker.plan(
                chapterParts.texts,
                chapterParts.startsParagraph,
                from = at,
                speakers = chapterParts.speakers,
            )
            if (chunk.text.isNotBlank()) return Planned(ch, chunk)
            at = chunk.start + chunk.sentenceCount
        }
    }

    /**
     * Silence to render before a chunk: the longer beat between chapters, a
     * short one at a paragraph break, and none where a long paragraph was cut by
     * the size cap so it runs straight on.
     */
    private fun beatBefore(chapter: Int, from: Int): Long {
        if (from == 0 && chapter > 0) return CHAPTER_PAUSE_MS
        val startsParagraph = spoken.getOrNull(chapter)?.startsParagraph ?: return 0
        return if (startsParagraph.getOrNull(from) == true) PARAGRAPH_PAUSE_MS else 0
    }

    /** Marks the sentences that open a paragraph. */
    private fun paragraphStarts(paragraphs: List<List<String>>): BooleanArray {
        val starts = BooleanArray(paragraphs.sumOf { it.size })
        var idx = 0
        for (paragraph in paragraphs) {
            if (paragraph.isNotEmpty()) starts[idx] = true
            idx += paragraph.size
        }
        return starts
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId ?: return
            mainHandler.post { onUtteranceFinished(utteranceId) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            utteranceId ?: return
            mainHandler.post { onRangeStarted(utteranceId, start) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId ?: return
            mainHandler.post { onUtteranceFinished(utteranceId) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId ?: return
            mainHandler.post { onUtteranceFinished(utteranceId) }
        }
    }

    /** The engine started speaking inside [start]; find the sentence it maps to. */
    private fun onRangeStarted(id: String, start: Int) {
        val utterance = utteranceById[id] ?: return
        if (!_state.value.isPlaying) return
        var part = 0
        for (i in utterance.offsets.indices) {
            if (utterance.offsets[i] <= start) part = i else break
        }
        val sentence = spoken.getOrNull(utterance.chapter)
            ?.sentences?.getOrNull(utterance.start + part)
            ?: return
        _state.update { st ->
            st.copy(
                chapterIndex = utterance.chapter,
                sentenceIndex = sentence,
                sentenceCount = sentences.getOrNull(utterance.chapter)?.size ?: st.sentenceCount,
            )
        }
    }

    private fun onUtteranceFinished(id: String) {
        if (!_state.value.isPlaying) return
        // The beat between paragraphs carries no position of its own.
        if (id.startsWith(SILENCE_PREFIX)) return
        if (id != currentUtterance) return
        val utterance = utteranceById.remove(id) ?: return
        if (utteranceQueue.firstOrNull()?.id == id) utteranceQueue.removeFirst()

        val chapterParts = spoken.getOrNull(utterance.chapter)
        val chapterSize = chapterParts?.size ?: 0
        val end = utterance.start + utterance.size
        val following = if (end >= chapterSize) nextChapterPosition(utterance.chapter) else null
        _state.update { st ->
            if (following != null) {
                st.copy(
                    chapterIndex = following.first,
                    sentenceIndex = 0,
                    sentenceCount = sentences[following.first].size,
                    finished = false,
                )
            } else {
                val next = chapterParts?.sentences?.getOrNull(end)
                st.copy(sentenceIndex = next ?: st.sentenceIndex)
            }
        }
        persist()

        // The next chunk is already playing; keep the look-ahead topped up.
        currentUtterance = utteranceQueue.firstOrNull()?.id
        if (utteranceQueue.isEmpty() && nextPosition() == null) {
            endOfBook()
            return
        }
        fillQueue()
    }

    private fun moveTo(chapterIndex: Int, sentenceIndex: Int) {
        val playing = _state.value.isPlaying
        clearSpeechQueue()
        tts?.stop()
        _state.update {
            it.copy(
                chapterIndex = chapterIndex,
                sentenceIndex = sentenceIndex,
                sentenceCount = sentences[chapterIndex].size,
                finished = false,
            )
        }
        persist()
        if (playing) {
            requestAudioFocus()
            restartSpeech()
        }
    }

    private fun persist() {
        val st = _state.value
        val book = book ?: return
        if (!st.isReady) return
        book.meta.chapterIndex = st.chapterIndex
        book.meta.sentenceIndex = st.sentenceIndex
        book.meta.sentenceCount = st.sentenceCount
        store.saveProgress(book.meta)
    }

    // -------------------------------------------------------------- audio focus

    /**
     * Set when playback was paused by a short interruption (a notification
     * sound, a call ringing, ...) and narration should continue once that
     * interruption releases audio focus.
     */
    private var resumeOnFocusGain = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> mainHandler.post {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    play()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> mainHandler.post {
                // Pause for the interruption, but keep the focus request alive
                // so the system reports AUDIOFOCUS_GAIN when it ends.
                resumeOnFocusGain = _state.value.isPlaying
                pauseInternal(abandonFocus = false)
            }

            // Another app took over playback for good: stay paused.
            AudioManager.AUDIOFOCUS_LOSS -> mainHandler.post {
                resumeOnFocusGain = false
                pause()
            }
        }
    }

    private fun requestAudioFocus() {
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        }
        focusRequest?.let { audioManager?.requestAudioFocus(it) }
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    // ---------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = powerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    private companion object {
        /** Blank line in the chapter text; a single newline is just wrapping. */
        val PARAGRAPH_BREAK = Regex("""\n[ \t]*\n""")

        /** Silence between paragraphs and between chapters, in milliseconds. */
        const val PARAGRAPH_PAUSE_MS = 400L
        const val CHAPTER_PAUSE_MS = 900L

        /** Only ever held while narration plays; shows up in battery reports. */
        const val WAKE_LOCK_TAG = "myreader:narration"

        /** Marks the silent utterances that produce the paragraph/chapter beat. */
        const val SILENCE_PREFIX = "silence:"

        /** Utterances kept queued: the one playing plus the one after it. */
        const val QUEUE_DEPTH = 2

        const val PREFS = "reader_settings"
        const val KEY_VOICE = "voice_name"
        const val KEY_ENGINE = "engine_package"
        const val KEY_MULTI_VOICE = "multi_voice"

        /** Per book: the reader's voice choices for the cast, as a JSON map. */
        const val KEY_CAST_PREFIX = "cast_voices_"

        /** Per book: the reader's line corrections, as a JSON map. */
        const val KEY_SPEAKER_PREFIX = "sentence_speakers_"

        /** Stored correction meaning "the narrator reads this sentence". */
        const val NARRATOR_OVERRIDE = "*narrator*"

        /**
         * The engine reads the voice to use from this entry of the speak()
         * parameters. Not a public SDK constant, so it is spelled out here.
         */
        const val VOICE_PARAM = "voiceName"

        /** Stored engine preference meaning "whatever Android is set to use". */
        const val SYSTEM_ENGINE = "system"

        /**
         * Maise (com.danemadsen.maise) and Supertonic register themselves as
         * Android speech engines. The first installed narrates automatically,
         * until another engine is chosen in the voice picker.
         */
        val PREFERRED_ENGINES = listOf(MAISE_PACKAGE, SUPERTONIC_PACKAGE)

        const val MAISE_PACKAGE = "com.danemadsen.maise"
        const val SUPERTONIC_PACKAGE = "com.brahmadeo.supertonic.tts"

        /** Short gender marker inside a voice name, e.g. Supertonic's `F3`/`M1`. */
        private val VOICE_TOKEN = Regex("""(?<![a-z0-9])([fm])(\d{1,2})(?![a-z0-9])""")
    }
}

/**
 * Maise voice ids look like `en-GB-alice-kokoro` or `el-GR-alpha-f-kokoro`: the
 * language the voice was trained on, its name, and the model, joined by dashes.
 * The engine reports every one of them as en-US, so the id is the only place
 * the real language and a name a reader can recognise appear.
 */
internal object MaiseVoices {

    /** A parsed voice id: its language tag and a human-readable voice name. */
    data class Parsed(val localeTag: String, val name: String)

    fun parse(id: String): Parsed? {
        val match = ID.matchEntire(id) ?: return null
        val name = match.groupValues[2]
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        return Parsed(match.groupValues[1], name)
    }

    private val ID = Regex(
        """([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,4})?)-([A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)-kokoro"""
    )
}
