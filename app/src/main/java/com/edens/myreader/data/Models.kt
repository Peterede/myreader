package com.edens.myreader.data

import kotlinx.serialization.Serializable

@Serializable
data class BookMeta(
    val id: String,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    val addedAt: Long = 0L,
    val chapterCount: Int = 1,
    var chapterIndex: Int = 0,
    var sentenceIndex: Int = 0,
    var sentenceCount: Int = 0,
) {
    /** Rough 0..1 reading progress estimate. */
    val progress: Float
        get() {
        val perChapter = if (sentenceCount > 0) sentenceIndex.toFloat() / sentenceCount else 0f
        val total = chapterIndex + perChapter
        return (total / chapterCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
}

@Serializable
data class Chapter(
    val title: String,
    val text: String,
)

@Serializable
data class Book(
    val meta: BookMeta,
    val chapters: List<Chapter>,
)
