package com.edens.myreader.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edens.myreader.data.BookMeta
import com.edens.myreader.data.BookStore
import com.edens.myreader.tts.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    store: BookStore,
    player: Player,
    refreshKey: Int,
    onOpenBook: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(listOf<BookMeta>()) }
    var importing by remember { mutableStateOf(false) }
    val live by player.state.collectAsState()

    fun reload() {
        scope.launch {
            books = withContext(Dispatchers.IO) { store.list() }
        }
    }

    LaunchedEffect(refreshKey, live.bookId) { reload() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importing = true
                runCatching { withContext(Dispatchers.IO) { store.import(uri) } }
                    .onSuccess {
                        reload()
                        Toast.makeText(context, "Imported “${it.title}”", Toast.LENGTH_SHORT).show()
                    }
                    .onFailure {
                        Toast.makeText(
                            context,
                            it.message ?: "Import failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                importing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("My Library") })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    picker.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/zip",
                            "text/plain",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Import book")
            }
        },
    ) { padding ->
        if (books.isEmpty() && !importing) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No books yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to import an EPUB or text file",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.id }) { meta ->
                    val progress = if (live.bookId == meta.id) {
                        val perChapter = if (live.sentenceCount > 0) {
                            live.sentenceIndex.toFloat() / live.sentenceCount
                        } else 0f
                        ((live.chapterIndex + perChapter) / live.chapterTitles.size.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    } else {
                        meta.progress
                    }
                    BookCard(
                        meta = meta,
                        progress = progress,
                        onOpen = { onOpenBook(meta.id) },
                        onDelete = {
                            scope.launch(Dispatchers.IO) {
                                store.delete(meta.id)
                                reload()
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }

        if (importing) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Card {
                    Row(
                        Modifier.padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Importing…")
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    meta: BookMeta,
    progress: Float,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onOpen) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        meta.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    meta.author?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Chapter ${meta.chapterIndex + 1} of ${meta.chapterCount} • ${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
