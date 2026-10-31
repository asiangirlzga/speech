package com.example.readaloud

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Reads the full text of DOCX and TXT files (PDFs and images are handled page by page in Book.kt). */
object TextExtractor {

    fun read(file: File, type: String): String = when (type) {
        "txt" -> file.readBytes().toString(Charsets.UTF_8)
        "docx" -> docx(file)
        else -> throw IllegalArgumentException("Unsupported file type.")
    }

    private fun docx(file: File): String {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") return parseDocxXml(zip)
                entry = zip.nextEntry
            }
        }
        throw IllegalArgumentException("Not a valid .docx file (old .doc is not supported).")
    }

    private fun parseDocxXml(stream: InputStream): String {
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
