package com.edens.myreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PushbackInputStream
import java.util.UUID

/**
 * Persists imported books under files/books/ as `{id}.book.json` (full content)
 * plus a lightweight `{id}.meta.json` for the library listing.
 */
class BookStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "books").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun list(): List<BookMeta> =
        dir.listFiles { f -> f.name.endsWith(".meta.json") }
            ?.mapNotNull { f ->
                runCatching { json.decodeFromString<BookMeta>(f.readText()) }.getOrNull()
            }
            ?.sortedByDescending { it.addedAt }
            ?: emptyList()

    fun load(id: String): Book? {
        val metaFile = File(dir, "$id.meta.json")
        val bookFile = File(dir, "$id.book.json")
        if (!metaFile.exists() || !bookFile.exists()) return null
        return runCatching {
            val book = json.decodeFromString<Book>(bookFile.readText())
            val meta = runCatching { json.decodeFromString<BookMeta>(metaFile.readText()) }.getOrNull()
            if (meta != null) book.copy(meta = meta) else book
        }.getOrNull()
    }

    fun saveProgress(meta: BookMeta) {
        runCatching { File(dir, "${meta.id}.meta.json").writeText(json.encodeToString(meta)) }
    }

    fun delete(id: String) {
        File(dir, "$id.meta.json").delete()
        File(dir, "$id.book.json").delete()
    }

    /**
     * Imports a book from a content Uri (EPUB or plain text) and returns its
     * metadata, or throws [IllegalArgumentException] with a user-facing message.
     */
    suspend fun import(uri: Uri): BookMeta {
        val resolver = appContext.contentResolver
        val displayName = queryDisplayName(uri)
        val mime = resolver.getType(uri)
        val input = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open file")

        val parsed: ImportedContent = input.use { raw ->
            // Content streams do not support mark/reset; peek the magic bytes
            // with a pushback buffer instead.
            val stream = PushbackInputStream(raw, 2)
            val b1 = stream.read()
            val b2 = stream.read()
            if (b2 >= 0) stream.unread(b2)
            if (b1 >= 0) stream.unread(b1)
            val isZip = b1 == 0x50 && b2 == 0x4B
            val looksLikeEpub = displayName.endsWith(".epub", ignoreCase = true) ||
                mime == "application/epub+zip" || mime == "application/zip"
            if (isZip || looksLikeEpub) {
                val epub = EpubParser.parse(stream)
                ImportedContent(epub.title, epub.author, epub.language, epub.chapters)
            } else {
                val text = stream.readBytes().toString(Charsets.UTF_8)
                if (text.isBlank()) throw IllegalArgumentException("File is empty")
                val fallbackTitle = displayName.removeSuffix(".txt").ifBlank { "Untitled" }
                ImportedContent(fallbackTitle, null, null, textToChapters(text))
            }
        }

        val cleanTitle = (parsed.title ?: displayName.substringBeforeLast('.'))
            .trim().ifBlank { "Untitled" }
        val meta = BookMeta(
            id = UUID.randomUUID().toString().take(8),
            title = cleanTitle,
            author = parsed.author?.trim()?.ifBlank { null },
            language = parsed.language?.trim()?.ifBlank { null },
            addedAt = System.currentTimeMillis(),
            chapterCount = parsed.chapters.size.coerceAtLeast(1),
        )
        val book = Book(meta, parsed.chapters)
        runCatching {
            File(dir, "${meta.id}.book.json").writeText(json.encodeToString(book))
            saveProgress(meta)
        }.onFailure { throw IllegalArgumentException("Could not save book: ${it.message}") }
        return meta
    }

    private data class ImportedContent(
        val title: String?,
        val author: String?,
        val language: String?,
        val chapters: List<Chapter>,
    )

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { return it }
                    }
                }
        }
        return uri.lastPathSegment ?: "book"
    }

    companion object {
        private val CHAPTER_HEADING = Regex(
            """^\s*(chapter|chap\.|part|book|section|prologue|epilogue|introduction|afterword)\b.{0,60}$""",
            RegexOption.IGNORE_CASE,
        )

        fun textToChapters(text: String): List<Chapter> {
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val chapters = mutableListOf<Chapter>()
            var title: String? = null
            val body = StringBuilder()
            fun flush() {
                val content = body.toString().trim()
                if (content.isNotEmpty()) {
                    chapters += Chapter(title ?: "Full text", content)
                }
                title = null
                body.setLength(0)
            }
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && trimmed.length <= 80 && CHAPTER_HEADING.matches(trimmed)) {
                    flush()
                    title = trimmed
                } else {
                    body.append(line).append('\n')
                }
            }
            flush()
            return chapters.ifEmpty { listOf(Chapter("Full text", text.trim())) }
        }
    }
}
