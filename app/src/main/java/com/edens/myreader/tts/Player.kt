package com.edens.myreader.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.edens.myreader.data.Book
import com.edens.myreader.data.BookStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Gender of an installed voice, inferred from the engine's voice name. */
enum class VoiceGender { MALE, FEMALE, UNKNOWN }

/** A voice offered by the current TTS engine. */
data class VoiceOption(
    val name: String,
    val locale: String,
    val gender: VoiceGender,
    /** Human-readable accent (e.g. "American English"), when the locale is known. */
    val accent: String?,
    val label: String,
)

/**
 * Owns the [TextToSpeech] engine and all playback state.
 *
 * Speech is produced in small paragraph-sized chunks (several sentences per
 * utterance) so the engine keeps continuous, natural prosody instead of
 * restarting its intonation for every sentence. [UtteranceProgressListener.onRangeStart]
 * reports the character position inside the utterance as it is spoken, which is
 * mapped back to the current sentence so the UI stays in sync. After a seek or
 * pause/resume the chunk is rebuilt starting at the selected sentence.
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
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        PlayerState(voiceName = prefs.getString(KEY_VOICE, null))
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsCreated = false
    private var book: Book? = null
    private var sentences: List<List<String>> = emptyList()
    private var currentUtterance: String? = null
    private var focusRequest: AudioFocusRequest? = null
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

    // Current speech chunk: sentences [chunkStart, chunkStart + chunkSize).
    private var chunkStart = 0
    private var chunkSize = 1
    private var chunkOffsets = intArrayOf(0)

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
        sentences = loaded.chapters.map { SentenceSplitter.split(it.text) }
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
        )
        ensureTts()
        applySpeechSetup()
    }

    fun play() {
        val st = _state.value
        if (!st.isReady || st.isPlaying) return
        ensureTts()
        requestAudioFocus()
        _state.update { it.copy(isPlaying = true, finished = false) }
        mainHandler.post { speakCurrent() }
    }

    fun pause() = pauseInternal(abandonFocus = true)

    private fun pauseInternal(abandonFocus: Boolean) {
        if (abandonFocus) {
            resumeOnFocusGain = false
            abandonAudioFocus()
        }
        if (!_state.value.isPlaying) return
        currentUtterance = null
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
        _state.value = PlayerState(
            speed = _state.value.speed,
            voiceName = _state.value.voiceName,
            voices = _state.value.voices,
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
     * Selects a narration voice by its engine name; null restores the default
     * voice for the book's language.
     */
    fun setVoice(name: String?) {
        prefs.edit().apply {
            if (name == null) remove(KEY_VOICE) else putString(KEY_VOICE, name)
        }.apply()
        _state.update { it.copy(voiceName = name) }

        val tts = tts ?: return
        val voice = name?.let { n -> availableVoices().firstOrNull { it.name == n } }
        if (voice != null) {
            runCatching { tts.voice = voice }
        } else {
            applyLanguage()
            runCatching { tts.voice = tts.defaultVoice }
        }

        // Restart the current chunk so the change is heard immediately.
        if (_state.value.isPlaying) {
            currentUtterance = null
            tts.stop()
            mainHandler.post { speakCurrent() }
        }
    }

    fun sentencesFor(chapterIndex: Int): List<String> =
        sentences.getOrNull(chapterIndex).orEmpty()

    fun endOfBook() {
        currentUtterance = null
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
        tts = TextToSpeech(appContext) { status ->
            mainHandler.post {
                if (status != TextToSpeech.SUCCESS) {
                    ttsCreated = false
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            error = "Text-to-speech engine is unavailable. Install/enable a TTS engine in system settings.",
                        )
                    }
                    return@post
                }
                tts?.setOnUtteranceProgressListener(utteranceListener)
                applySpeechSetup()
                if (_state.value.isPlaying) speakCurrent()
            }
        }
    }

    /** Applies language, saved voice and speed, then refreshes the voice list. */
    private fun applySpeechSetup() {
        applyLanguage()
        applyStoredVoice()
        tts?.setSpeechRate(_state.value.speed)
        refreshVoices()
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

    /** Re-applies the persisted voice, dropping it if it is no longer usable. */
    private fun applyStoredVoice() {
        val tts = tts ?: return
        val stored = _state.value.voiceName ?: return
        val voice = availableVoices().firstOrNull { it.name == stored }
        val bookLocale = book?.meta?.language?.let { languageOf(it) }
        val mismatched = voice != null && bookLocale != null &&
            !sameLanguage(voice.locale, bookLocale)
        if (voice == null || mismatched) {
            prefs.edit().remove(KEY_VOICE).apply()
            _state.update { it.copy(voiceName = null) }
            return
        }
        runCatching { tts.voice = voice }
    }

    private fun availableVoices(): List<Voice> =
        runCatching { tts?.voices }.getOrNull().orEmpty().toList()

    /**
     * Android does not expose a voice's gender, but most engines include
     * "male"/"female" in the voice name (e.g. Google's `#female_1`), so it can
     * be inferred from there.
     */
    private fun genderOf(voice: Voice): VoiceGender {
        val name = voice.name.lowercase(Locale.ROOT)
        return when {
            name.contains("female") -> VoiceGender.FEMALE
            name.contains("male") -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
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
        val matching = all.filter { sameLanguage(it.locale, target) }
        val usable = (if (matching.isNotEmpty()) matching else all)
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))

        var female = 0
        var male = 0
        val options = usable.map { voice ->
            val gender = genderOf(voice)
            val accent = accentOf(voice.locale)
            val base = when (gender) {
                VoiceGender.FEMALE -> "Female ${++female}"
                VoiceGender.MALE -> "Male ${++male}"
                VoiceGender.UNKNOWN -> voice.name
            }
            VoiceOption(
                name = voice.name,
                locale = voice.locale.toLanguageTag(),
                gender = gender,
                accent = accent,
                label = if (accent != null) "$base · $accent" else base,
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

    /**
     * Builds a paragraph-sized chunk starting at the current sentence and
     * speaks it as one utterance, keeping prosody continuous across sentence
     * boundaries.
     */
    private fun speakCurrent() {
        val st = _state.value
        val tts = tts ?: return
        if (!st.isPlaying) return

        val sents = sentences.getOrNull(st.chapterIndex)
        if (sents == null || st.sentenceIndex >= sents.size) {
            endOfBook()
            return
        }

        val text = StringBuilder()
        val offsets = ArrayList<Int>()
        var count = 0
        var idx = st.sentenceIndex
        while (idx < sents.size && count < MAX_SENTENCES_PER_CHUNK) {
            val s = sents[idx]
            if (count > 0 && text.length + 1 + s.length > MAX_CHUNK_CHARS && text.length >= MIN_CHUNK_CHARS) {
                break
            }
            if (count > 0) text.append(' ')
            offsets += text.length
            text.append(s)
            count++
            idx++
            if (text.length >= MAX_CHUNK_CHARS) break
        }

        chunkStart = st.sentenceIndex
        chunkSize = count
        chunkOffsets = offsets.toIntArray()

        val id = "${st.chapterIndex}:${st.sentenceIndex}"
        currentUtterance = id
        tts.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, id)
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
        if (id != currentUtterance || !_state.value.isPlaying) return
        var sentence = 0
        for (i in chunkOffsets.indices) {
            if (chunkOffsets[i] <= start) sentence = i else break
        }
        _state.update { it.copy(sentenceIndex = chunkStart + sentence) }
    }

    private fun onUtteranceFinished(id: String) {
        val st = _state.value
        if (id != currentUtterance || !st.isPlaying) return
        currentUtterance = null

        val nextStart = chunkStart + chunkSize
        if (nextStart < st.sentenceCount) {
            _state.update { it.copy(sentenceIndex = nextStart) }
            persist()
            speakCurrent()
        } else if (st.chapterIndex + 1 < st.chapterTitles.size) {
            _state.update {
                it.copy(
                    chapterIndex = it.chapterIndex + 1,
                    sentenceIndex = 0,
                    sentenceCount = sentences[it.chapterIndex + 1].size,
                    finished = false,
                )
            }
            persist()
            speakCurrent()
        } else {
            endOfBook()
        }
    }

    private fun moveTo(chapterIndex: Int, sentenceIndex: Int) {
        val playing = _state.value.isPlaying
        currentUtterance = null
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
            mainHandler.post { speakCurrent() }
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

    private companion object {
        /** Roughly a paragraph of speech per utterance. */
        const val MAX_CHUNK_CHARS = 260
        const val MIN_CHUNK_CHARS = 80
        const val MAX_SENTENCES_PER_CHUNK = 8

        const val PREFS = "reader_settings"
        const val KEY_VOICE = "voice_name"
    }
}
