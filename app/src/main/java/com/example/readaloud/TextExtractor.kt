package com.example.readaloud

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

/** Pulls plain text out of PDF, DOCX and TXT files. Runs on a background thread. */
object TextExtractor {

    private var pdfInitDone = false

    fun extract(ctx: Context, uri: Uri, name: String, mime: String?): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".pdf") || mime == "application/pdf" -> pdf(ctx, uri)
            n.endsWith(".docx") || mime?.contains("wordprocessingml") == true -> docx(ctx, uri)
            n.endsWith(".txt") || mime?.startsWith("text/") == true -> txt(ctx, uri)
            else -> throw IllegalArgumentException("Unsupported file type. Use PDF, DOCX or TXT.")
        }
    }

    private fun txt(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun pdf(ctx: Context, uri: Uri): String {
        if (!pdfInitDone) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            pdfInitDone = true
        }
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            PDDocument.load(input).use { doc ->
                return PDFTextStripper().getText(doc).trim()
            }
        }
    }

    private fun docx(ctx: Context, uri: Uri): String {
        ctx.contentResolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") return parseDocxXml(zip)
                    entry = zip.nextEntry
                }
            }
        }
        throw IllegalArgumentException("Not a valid .docx file (old .doc is not supported).")
    }

    private fun parseDocxXml(stream: java.io.InputStream): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, "UTF-8")
        val sb = StringBuilder()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> sb.append(parser.nextText())
                    "tab" -> sb.append(' ')
                    "br" -> sb.append('\n')
                }
                XmlPullParser.END_TAG -> if (parser.name == "p") sb.append('\n')
            }
            ev = parser.next()
        }
        return sb.toString().trim()
    }
}
