package com.example.readaloud

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest

/** A book is read one page at a time; page text (including OCR results) is cached on disk. */
interface Book {
    val id: String
    val name: String
    val pageCount: Int
    /** Blocking. Reads from cache, or extracts / OCRs the page and caches it. */
    fun pageText(i: Int): String
    fun close()
}

private const val MIN_LETTERS_PER_PAGE = 20   // fewer letters than this => page is treated as scanned

private fun cached(dir: File, prefix: String, i: Int, compute: () -> String): String {
    val f = File(dir, "$prefix$i.txt")
    if (f.exists()) return f.readText()
    val t = compute()
    val tmp = File(dir, "$prefix$i.tmp")
    tmp.writeText(t)
    tmp.renameTo(f)
    return t
}

class PdfBook(
    ctx: Context,
    private val dir: File,
    override val name: String,
    forceOcr: Boolean
) : Book {
    override val id: String = dir.name
    override val pageCount: Int

    private var doc: PDDocument? = null
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val stripper by lazy { PDFTextStripper() }
    private val prefix = if (forceOcr) "o" else "t"   // OCR and text-layer caches never mix

    init {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val f = File(dir, "book.pdf")
        if (!forceOcr) {
            doc = try {
                PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly())
            } catch (e: Exception) {
                try { PDDocument.load(f) } catch (e2: Exception) { null }
            }
        }
        renderer = try {
            val p = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = p
            PdfRenderer(p)
        } catch (e: Exception) { null }   // e.g. password-protected
        pageCount = renderer?.pageCount ?: doc?.numberOfPages
            ?: throw IllegalArgumentException("Could not open this PDF (password-protected or damaged).")
    }

    @Synchronized
    override fun pageText(i: Int): String = cached(dir, prefix, i) {
        var t = ""
        val d = doc
        if (d != null) {
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            t = stripper.getText(d).trim()
        }
        val r = renderer
        if (t.count { it.isLetterOrDigit() } < MIN_LETTERS_PER_PAGE && r != null) {
            t = Ocr.renderAndRecognize(r, i)
        }
        t
    }

    @Synchronized
    override fun close() {
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        try { doc?.close() } catch (_: Exception) {}
    }
}

class TextBook(
    private val dir: File,
    override val name: String,
    override val pageCount: Int
) : Book {
    override val id: String = dir.name
    override fun pageText(i: Int): String = File(dir, "p$i.txt").readText()
    override fun close() {}
}

class ImageBook(private val dir: File, override val name: String) : Book {
    override val id: String = dir.name
    override val pageCount = 1
    @Synchronized
    override fun pageText(i: Int): String =
        cached(dir, "o", 0) { Ocr.recognizeImageFile(File(dir, "book.img")) }
    override fun close() {}
}

object BookStore {
    private const val KEEP_BOOKS = 3
    private const val PAGE_CHARS = 2500        // target size of a "page" for DOCX / TXT
    private const val MAX_PAGE_CHARS = 4000

    fun root(ctx: Context): File = File(ctx.filesDir, "books").also { it.mkdirs() }

    private fun typeOf(name: String, mime: String?): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".pdf") || mime == "application/pdf" -> "pdf"
            n.endsWith(".docx") || mime?.contains("wordprocessingml") == true -> "docx"
            mime?.startsWith("image/") == true || n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".bmp") -> "img"
            n.endsWith(".txt") || mime?.startsWith("text/") == true -> "txt"
            else -> throw IllegalArgumentException("Unsupported file type. Use PDF, DOCX, TXT or an image.")
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Copies the file into app storage (once per book). Blocking; call off the main thread. */
    fun import(
        ctx: Context, uri: Uri, name: String, size: Long, mime: String?, progress: (String) -> Unit
    ): File {
        val type = typeOf(name, mime)
        val dir = File(root(ctx), sha1("$name|$size"))
        if (File(dir, "ok").exists()) {
            dir.setLastModified(System.currentTimeMillis())
            return dir
        }
        dir.deleteRecursively()
        dir.mkdirs()

        progress("Copying file…")
        val target = File(dir, when (type) { "pdf" -> "book.pdf"; "img" -> "book.img"; else -> "book.src" })
        ctx.contentResolver.openInputStream(uri)!!.use { i -> target.outputStream().use { o -> i.copyTo(o) } }

        var pages = 0
        if (type == "txt" || type == "docx") {
            progress("Extracting text…")
            val list = paginate(TextExtractor.read(target, type))
            list.forEachIndexed { i, t -> File(dir, "p$i.txt").writeText(t) }
            pages = list.size
            target.delete()
        }
        File(dir, "meta.txt").writeText("$name\n$type\n$pages")
        File(dir, "ok").createNewFile()
        prune(ctx, keep = dir.name)
        return dir
    }

    fun open(ctx: Context, dir: File, forceOcr: Boolean): Book {
        val lines = File(dir, "meta.txt").readLines()
        val name = lines[0]
        dir.setLastModified(System.currentTimeMillis())
        return when (lines[1]) {
            "pdf" -> PdfBook(ctx, dir, name, forceOcr)
            "img" -> ImageBook(dir, name)
            else -> TextBook(dir, name, lines.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }

    /** Keeps only the few most recently opened books so the cache doesn't fill the phone. */
    private fun prune(ctx: Context, keep: String) {
        val dirs = root(ctx).listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        dirs.filter { it.name != keep }.drop(KEEP_BOOKS - 1).forEach { it.deleteRecursively() }
    }

    private fun paginate(text: String): List<String> {
        val pages = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) pages.add(t)
            sb.setLength(0)
        }
        for (para in text.replace("\r", "").split("\n")) {
            var p = para.trim()
            if (p.isEmpty()) continue
            while (p.length > MAX_PAGE_CHARS) {
                flush()
                var cut = p.lastIndexOf(' ', MAX_PAGE_CHARS)
                if (cut <= 0) cut = MAX_PAGE_CHARS
                pages.add(p.substring(0, cut).trim())
                p = p.substring(cut).trim()
            }
            sb.append(p).append('\n')
            if (sb.length >= PAGE_CHARS) flush()
        }
        flush()
        return pages
    }
}
