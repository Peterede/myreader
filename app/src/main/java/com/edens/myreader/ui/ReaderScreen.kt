package com.edens.myreader.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edens.myreader.tts.CastMember
import com.edens.myreader.tts.EngineOption
import com.edens.myreader.tts.Player
import com.edens.myreader.tts.VoiceOption
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    bookId: String,
    player: Player,
    onBack: () -> Unit,
) {
    val state by player.state.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showVoiceDialog by rememberSaveable { mutableStateOf(false) }
    // The sentence whose speaker the reader is correcting, if any.
    var speakerTarget by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(bookId) { player.open(bookId) }

    // Keep the screen on while the reader is open.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Follow the sentence being spoken.
    LaunchedEffect(state.chapterIndex, state.sentenceIndex) {
        if (state.isReady) {
            listState.animateScrollToItem(state.sentenceIndex)
        }
    }

    if (!state.isReady) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.error ?: "Loading…")
        }
        return
    }

    val sentences = player.sentencesFor(state.chapterIndex)
    val speakers = remember(state.chapterIndex, state.narrationRevision) {
        player.speakersFor(state.chapterIndex)
    }

    if (showVoiceDialog) {
        VoiceDialog(
            voices = state.voices,
            selected = state.voiceName,
            onSelect = { name -> player.setVoice(name) },
            multiVoice = state.multiVoice,
            onMultiVoice = { enabled -> player.setMultiVoice(enabled) },
            cast = state.cast,
            onSelectCastVoice = { name, voice -> player.setCastVoice(name, voice) },
            engines = state.engines,
            selectedEngine = state.enginePackage,
            onSelectEngine = { name -> player.setEngine(name) },
            onDismiss = { showVoiceDialog = false },
        )
    }

    if (state.multiVoice) {
        val target = speakerTarget
        if (target != null) {
            SpeakerDialog(
                sentence = sentences.getOrNull(target).orEmpty(),
                current = speakers.getOrNull(target),
                hasOverride = player.hasSpeakerOverride(state.chapterIndex, target),
                narratorForced = player.isNarratorOverride(state.chapterIndex, target),
                cast = state.cast,
                onSelect = { name ->
                    player.setSentenceSpeaker(state.chapterIndex, target, name)
                    speakerTarget = null
                },
                onNarrator = {
                    player.setSentenceSpeaker(state.chapterIndex, target, null)
                    speakerTarget = null
                },
                onAutomatic = {
                    player.clearSentenceSpeaker(state.chapterIndex, target)
                    speakerTarget = null
                },
                onDismiss = { speakerTarget = null },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            state.chapterTitles.getOrNull(state.chapterIndex).orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showVoiceDialog = true }) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = "Narration voice")
                    }
                    IconButton(onClick = { player.cycleSpeed() }) {
                        Text(
                            "${state.speed}×",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.semantics {
                                contentDescription = "Playback speed"
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            PlayerControls(player, state)
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            itemsIndexed(sentences) { index, sentence ->
                val current = index == state.sentenceIndex
                val speaker = if (state.multiVoice) speakers.getOrNull(index) else null
                val accent = speaker?.let { castColor(state.cast, it) }
                val text = if (speaker != null) {
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = accent ?: Color.Unspecified,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append(speaker)
                            append("  ")
                        }
                        append(sentence)
                    }
                } else {
                    buildAnnotatedString { append(sentence) }
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            when {
                                current ->
                                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)

                                accent != null -> Modifier.background(accent.copy(alpha = 0.10f))
                                else -> Modifier
                            },
                        )
                        .combinedClickable(
                            onClick = { player.seekToSentence(index) },
                            onLongClick = { if (state.multiVoice) speakerTarget = index },
                        )
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun VoiceDialog(
    voices: List<VoiceOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    multiVoice: Boolean,
    onMultiVoice: (Boolean) -> Unit,
    cast: List<CastMember>,
    onSelectCastVoice: (String, String?) -> Unit,
    engines: List<EngineOption>,
    selectedEngine: String?,
    onSelectEngine: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The role whose voice the list below is editing; null is the narrator.
    var role by rememberSaveable { mutableStateOf<String?>(null) }
    val member = if (multiVoice) cast.firstOrNull { it.name == role } else null
    val activeVoice = if (member != null) member.voiceName else selected

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Narration") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item { SectionHeader("Engine") }
                item {
                    VoiceRow(
                        label = "System default",
                        detail = engines.firstOrNull { it.isSystemDefault }?.label
                            ?: "The engine Android is set to use",
                        checked = selectedEngine == null,
                        onClick = { onSelectEngine(null) },
                    )
                }
                items(engines, key = { it.packageName }) { engine ->
                    VoiceRow(
                        label = engine.label,
                        detail = engine.packageName,
                        checked = engine.packageName == selectedEngine,
                        onClick = { onSelectEngine(engine.packageName) },
                    )
                }

                item { SectionHeader("Voices") }
                item {
                    SwitchRow(
                        label = "A different voice for each character",
                        detail = "Quoted dialogue in the character's voice, everything " +
                            "else in the narrator's",
                        checked = multiVoice,
                        onCheckedChange = { enabled ->
                            onMultiVoice(enabled)
                            if (!enabled) role = null
                        },
                    )
                }

                if (multiVoice) {
                    item {
                        Text(
                            "Long-press a line in the reader to change who speaks it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    item { SectionHeader("Speaking as") }
                    item {
                        VoiceRow(
                            label = "Narrator",
                            detail = voiceLabel(voices, selected, "Default"),
                            checked = role == null,
                            onClick = { role = null },
                        )
                    }
                    if (cast.isEmpty()) {
                        item {
                            Text(
                                "No character dialogue was found in this book.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(cast, key = { it.name }) { character ->
                        VoiceRow(
                            label = character.name,
                            detail = "${character.lineCount} " +
                                (if (character.lineCount == 1) "line" else "lines") +
                                " · " + voiceLabel(voices, character.voiceName, "Automatic"),
                            checked = role == character.name,
                            onClick = { role = character.name },
                        )
                    }
                }

                item {
                    SectionHeader(
                        if (member != null) "Voice for ${member.name}" else "Narrator voice",
                    )
                }
                if (voices.isEmpty()) {
                    item {
                        Text(
                            "No voices are available yet. Install a speech engine or " +
                                "language pack in system settings, then try again.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    item {
                        VoiceRow(
                            label = if (member != null) "Automatic" else "Default",
                            detail = if (member != null) {
                                "Let the reader choose a voice"
                            } else {
                                "Best installed voice for this language"
                            },
                            checked = activeVoice == null,
                            onClick = {
                                if (member != null) onSelectCastVoice(member.name, null)
                                else onSelect(null)
                            },
                        )
                    }
                    items(voices, key = { it.name }) { voice ->
                        VoiceRow(
                            label = voice.label,
                            detail = voice.name,
                            checked = voice.name == activeVoice,
                            onClick = {
                                if (member != null) onSelectCastVoice(member.name, voice.name)
                                else onSelect(voice.name)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SpeakerDialog(
    sentence: String,
    current: String?,
    hasOverride: Boolean,
    narratorForced: Boolean,
    cast: List<CastMember>,
    onSelect: (String) -> Unit,
    onNarrator: () -> Unit,
    onAutomatic: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is speaking?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    Text(
                        sentence,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                item {
                    VoiceRow(
                        label = "Automatic",
                        detail = "Use the speaker the reader detected",
                        checked = !hasOverride,
                        onClick = onAutomatic,
                    )
                }
                item {
                    VoiceRow(
                        label = "Narrator",
                        detail = "Read this line in the narrator's voice",
                        checked = narratorForced,
                        onClick = onNarrator,
                    )
                }
                items(cast, key = { it.name }) { character ->
                    VoiceRow(
                        label = character.name,
                        detail = "${character.lineCount} " +
                            (if (character.lineCount == 1) "line" else "lines"),
                        checked = hasOverride && current == character.name,
                        onClick = { onSelect(character.name) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Colours for dialogue, picked by position in the cast so they stay apart. */
private val CAST_COLORS = listOf(
    Color(0xFF1565C0),
    Color(0xFF2E7D32),
    Color(0xFFAD1457),
    Color(0xFF6A1B9A),
    Color(0xFF00838F),
    Color(0xFFEF6C00),
    Color(0xFF5D4037),
    Color(0xFF3949AB),
)

private fun castColor(cast: List<CastMember>, name: String): Color {
    val index = cast.indexOfFirst { it.name == name }
    val slot = if (index >= 0) index else name.hashCode() and Int.MAX_VALUE
    return CAST_COLORS[slot % CAST_COLORS.size]
}

private fun voiceLabel(voices: List<VoiceOption>, name: String?, fallback: String): String =
    name?.let { wanted -> voices.firstOrNull { it.name == wanted }?.label ?: wanted } ?: fallback

@Composable
private fun SwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun VoiceRow(
    label: String,
    detail: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = checked, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlayerControls(player: Player, state: Player.PlayerState) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Slider(
            value = if (state.sentenceCount > 1) {
                state.sentenceIndex.toFloat() / (state.sentenceCount - 1)
            } else 0f,
            onValueChange = { fraction ->
                player.seekToSentence((fraction * (state.sentenceCount - 1)).roundToInt())
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Chapter ${state.chapterIndex + 1} of ${state.chapterTitles.size} • " +
                "sentence ${state.sentenceIndex + 1} of ${state.sentenceCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        if (state.finished) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = { player.seekToSentence(0); player.play() }) {
                Text("Finished — read again")
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = { player.prevChapter() },
                enabled = state.chapterIndex > 0,
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter")
            }
            IconButton(onClick = { player.previousSentence() }) {
                Icon(Icons.Default.Replay, contentDescription = "Previous sentence")
            }
            FilledIconButton(
                onClick = { player.toggle() },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(onClick = { player.nextSentence() }) {
                Icon(Icons.Default.Forward10, contentDescription = "Next sentence")
            }
            IconButton(
                onClick = { player.nextChapter() },
                enabled = state.chapterIndex + 1 < state.chapterTitles.size,
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next chapter")
            }
        }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
