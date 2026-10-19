package com.myreader.app.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * Minimal EPUB (2 and 3) reader: locates the OPF through META-INF/container.xml,
 * walks the spine in reading order and converts every content document to text.
 * Binary assets (images, fonts, css, audio) are skipped.
 */
object EpubParser {

    data class ParsedEpub(
        val title: String?,
        val author: String?,
        val language: String?,
        val chapters: List<Chapter>,
    )

    private val SKIP_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "svg", "webp", "bmp",
        "css", "js", "woff", "woff2", "ttf", "otf", "eot",
        "mp3", "mp4", "ogg", "pdf",
    )

    fun parse(input: InputStream): ParsedEpub {
        val files = readZip(input)
        val container = files["META-INF/container.xml"]
            ?: throw IllegalArgumentException("Not a valid EPUB (no container.xml)")
        val opfPath = parseRootfilePath(container)
            ?: throw IllegalArgumentException("Not a valid EPUB (no OPF reference)")
        val opf = files[opfPath]
            ?: throw IllegalArgumentException("Not a valid EPUB (missing $opfPath)")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val (title, author, language) = parseOpfMetadata(opf)
        val (manifest, spine) = parseOpfBody(opf)

        val chapters = mutableListOf<Chapter>()
        for (idRef in spine) {
            val href = manifest[idRef] ?: continue
            val path = resolvePath(opfDir, href)
            val bytes = files[path] ?: continue
            val raw = bytes.toString(Charsets.UTF_8)
            val text = HtmlText.toText(raw)
            if (text.isBlank()) continue
            val docTitle = extractDocTitle(raw)
            val heading = extractFirstHeading(raw)
            val chapterTitle = heading ?: docTitle ?: "Chapter ${chapters.size + 1}"
            chapters += Chapter(chapterTitle.take(120), text)
        }
        if (chapters.isEmpty()) throw IllegalArgumentException("No readable text found in EPUB")
        return ParsedEpub(title, author, language, chapters)
    }

    private fun readZip(input: InputStream): Map<String, ByteArray> {
        val result = HashMap<String, ByteArray>()
        val zis = ZipInputStream(input.buffered())
        while (true) {
            val entry = zis.nextEntry ?: break
            if (entry.isDirectory) { zis.closeEntry(); continue }
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext in SKIP_EXTENSIONS) { zis.closeEntry(); continue }
            // Cap individual documents; text content is far smaller in practice.
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = zis.read(buf)
                if (n < 0) break
                total += n
                if (total > 4 * 1024 * 1024) break
                out.write(buf, 0, n)
            }
            result[entry.name.removePrefix("/")] = out.toByteArray()
            zis.closeEntry()
        }
        return result
    }

    private fun parseRootfilePath(container: ByteArray): String? {
        val parser = newParser(container)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            parser.next()
        }
        return null
    }

    private fun parseOpfMetadata(opf: ByteArray): Triple<String?, String?, String?> {
        val parser = newParser(opf)
        var title: String? = null
        var author: String? = null
        var language: String? = null
        var inMetadata = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "metadata" -> inMetadata = true
                        "title" -> if (inMetadata && title == null) title = readText(parser)
                        "creator" -> if (inMetadata && author == null) author = readText(parser)
                        "language" -> if (inMetadata && language == null) language = readText(parser)
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "metadata") inMetadata = false
            }
            parser.next()
        }
        return Triple(title?.trim(), author?.trim(), language?.trim())
    }

    private fun parseOpfBody(opf: ByteArray): Pair<Map<String, String>, List<String>> {
        val parser = newParser(opf)
        val manifest = HashMap<String, String>()
        val spine = mutableListOf<String>()
        var inManifest = false
        var inSpine = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "manifest" -> inManifest = true
                    "spine" -> inSpine = true
                    "item" -> if (inManifest) {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                        if (id != null && href != null && isTextContent(mediaType, href)) {
                            manifest[id] = href
                        }
                    }
                    "itemref" -> if (inSpine) {
                        parser.getAttributeValue(null, "idref")?.let { spine += it }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "manifest" -> inManifest = false
                    "spine" -> inSpine = false
                }
            }
            parser.next()
        }
        return manifest to spine
    }

    private fun isTextContent(mediaType: String, href: String): Boolean {
        val ext = href.substringAfterLast('.', "").lowercase()
        return mediaType.contains("html") || mediaType.contains("xml") ||
            ext in setOf("html", "xhtml", "htm")
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(bytes), null)
        return parser
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        parser.next()
        while (parser.eventType != XmlPullParser.END_TAG) {
            if (parser.eventType == XmlPullParser.TEXT) sb.append(parser.text)
            parser.next()
        }
        return sb.toString()
    }

    private fun extractDocTitle(html: String): String? =
        Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)
            ?.groupValues?.get(1)?.let { HtmlText.toText(it) }?.takeIf { it.isNotBlank() }

    private fun extractFirstHeading(html: String): String? =
        Regex("""(?is)<h[1-3][^>]*>(.*?)</h[1-3]>""").find(html)
            ?.groupValues?.get(1)?.let { HtmlText.toText(it) }
            ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() }

    private fun resolvePath(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        val abs = if (baseDir.isEmpty()) cleanHref else "$baseDir/$cleanHref"
        val normalized = URI.create(URI(null, null, "/$abs", null).toString()).path
        return normalized.removePrefix("/").removePrefix("../").replace("/../", "/")
    }
}
