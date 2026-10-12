package com.example.readaloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private enum class State { IDLE, PLAYING, PAUSED }

    private lateinit var tvName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var etText: EditText
    private lateinit var rgLang: RadioGroup
    private lateinit var spHi: Spinner
    private lateinit var spEn: Spinner
    private lateinit var sbSpeed: SeekBar

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val io = Executors.newSingleThreadExecutor()

    private var hiVoices: List<Voice> = emptyList()
    private var enVoices: List<Voice> = emptyList()

    private var state = State.IDLE
    private var chunks: List<String> = emptyList()
    private var current = 0

    private val REQ_OPEN = 1001
    private val MAX_CHUNK = 800

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvName = findViewById(R.id.tvName)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        etText = findViewById(R.id.etText)
        rgLang = findViewById(R.id.rgLang)
        spHi = findViewById(R.id.spHi)
        spEn = findViewById(R.id.spEn)
        sbSpeed = findViewById(R.id.sbSpeed)

        findViewById<Button>(R.id.btnOpen).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnVoiceData).setOnClickListener { openVoiceSettings() }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { play() }
        findViewById<Button>(R.id.btnPause).setOnClickListener { pause() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stop() }

        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvSpeed.text = String.format(Locale.US, "%.1fx", rate())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // Any edit to the text invalidates a paused position.
        etText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (state == State.PAUSED) state = State.IDLE
            }
        })

        tts = TextToSpeech(this, this)
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { loadFile(it) }
    }

    // ---------- TTS setup ----------

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            tvStatus.text = "Text-to-speech engine not available on this device."
            return
        }
        ttsReady = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                val idx = id?.removePrefix("c")?.toIntOrNull() ?: return
                runOnUiThread {
                    if (state == State.PLAYING && idx == current) {
                        current++
                        speakCurrent()
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                runOnUiThread { fail("Speech error. Check voice data (button above).") }
            }
        })
        loadVoices()
    }

    private fun loadVoices() {
        val all = try { tts?.voices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        hiVoices = all.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
        enVoices = all.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .sortedBy { it.name }
        fill(spHi, hiVoices)
        fill(spEn, enVoices)
        if (hiVoices.isEmpty()) {
            tvStatus.text = "No Hindi voice installed. Tap \"Voice data\" to download one."
        }
    }

    private fun fill(sp: Spinner, voices: List<Voice>) {
        val labels = mutableListOf("Default")
        voices.forEach { labels.add("${it.name}  (${it.locale.displayCountry})") }
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e2: Exception) {
                Toast.makeText(this, "Open Settings > Language > Text-to-speech", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh voice list after returning from voice settings.
        if (ttsReady) loadVoices()
    }

    // ---------- File loading ----------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/plain"
                )
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { loadFile(it) }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) return c.getString(col) ?: ""
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun loadFile(uri: Uri) {
        stop()
        val name = displayName(uri)
        val mime = contentResolver.getType(uri)
        tvName.text = name
        tvStatus.text = "Reading file…"
        io.execute {
            try {
                val text = TextExtractor.extract(this, uri, name, mime)
                runOnUiThread {
                    etText.setText(text)
                    state = State.IDLE
                    tvStatus.text = if (text.isBlank())
                        "No readable text found (scanned PDFs need OCR)."
                    else "Loaded ${text.length} characters."
                }
            } catch (e: Throwable) {
                runOnUiThread { fail("Could not read file: ${e.message}") }
            }
        }
    }

    // ---------- Playback ----------

    private fun rate(): Float = 0.5f + sbSpeed.progress / 100f * 1.5f

    private fun play() {
        if (!ttsReady) { toast("Speech engine still starting…"); return }
        if (state == State.PAUSED) {
            state = State.PLAYING
            keepAwake(true)
            speakCurrent()
            return
        }
        val text = etText.text.toString()
        if (text.isBlank()) { toast("Open a file or paste some text first"); return }
        chunks = splitChunks(text)
        current = 0
        state = State.PLAYING
        keepAwake(true)
        speakCurrent()
    }

    private fun pause() {
        if (state != State.PLAYING) return
        state = State.PAUSED
        tts?.stop()
        keepAwake(false)
        tvStatus.text = "Paused at part ${current + 1} of ${chunks.size}"
    }

    private fun stop() {
        state = State.IDLE
        tts?.stop()
        current = 0
        keepAwake(false)
        if (ttsReady) tvStatus.text = ""
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        if (state != State.PLAYING) return
        if (current >= chunks.size) {
            state = State.IDLE
            current = 0
            keepAwake(false)
            tvStatus.text = "Finished."
            return
        }
        val chunk = chunks[current]
        val lang = when (rgLang.checkedRadioButtonId) {
            R.id.rbHi -> "hi"
            R.id.rbEn -> "en"
            else -> detectLang(chunk)
        }
        if (!applyLanguage(engine, lang)) {
            fail(
                if (lang == "hi") "Hindi voice data missing. Tap \"Voice data\" to install it."
                else "English voice data missing. Tap \"Voice data\" to install it."
            )
            return
        }
        engine.setSpeechRate(rate())
        tvStatus.text = "Reading part ${current + 1} of ${chunks.size} (${if (lang == "hi") "हिंदी" else "English"})"
        engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "c$current")
    }

    /** Sets locale, then the user's chosen voice for that language if any. */
    private fun applyLanguage(engine: TextToSpeech, lang: String): Boolean {
        val candidates = if (lang == "hi") listOf(Locale("hi", "IN"))
        else listOf(Locale("en", "IN"), Locale.US)
        var ok = false
        for (loc in candidates) {
            val r = engine.setLanguage(loc)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                ok = true
                break
            }
        }
        if (!ok) return false
        val sp = if (lang == "hi") spHi else spEn
        val list = if (lang == "hi") hiVoices else enVoices
        val pos = sp.selectedItemPosition
        if (pos > 0 && pos - 1 < list.size) {
            try { engine.voice = list[pos - 1] } catch (_: Exception) {}
        }
        return true
    }

    // ---------- Text helpers ----------

    private fun detectLang(s: String): String {
        var dev = 0
        var lat = 0
        for (ch in s) {
            when {
                ch in 'ऀ'..'ॿ' -> dev++
                ch in 'A'..'Z' || ch in 'a'..'z' -> lat++
            }
        }
        return if (dev > lat) "hi" else "en"
    }

    /** Splits on sentence ends (. ! ? । and newlines) into pieces of <= MAX_CHUNK chars. */
    private fun splitChunks(text: String): List<String> {
        val clean = text.replace("\r", "").replace(Regex("[ \\t]+"), " ")
        val sentences = clean.split(Regex("(?<=[.!?।\\n])\\s+"))
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().trim()
            if (t.isNotEmpty()) out.add(t)
            sb.setLength(0)
        }
        for (s in sentences) {
            var piece = s
            while (piece.length > MAX_CHUNK) {
                flush()
                var cut = piece.lastIndexOf(' ', MAX_CHUNK)
                if (cut <= 0) cut = MAX_CHUNK
                out.add(piece.substring(0, cut).trim())
                piece = piece.substring(cut)
            }
            if (sb.length + piece.length + 1 > MAX_CHUNK) flush()
            sb.append(piece).append(' ')
        }
        flush()
        return out.filter { it.any { c -> c.isLetterOrDigit() } }
    }

    // ---------- Misc ----------

    private fun fail(msg: String) {
        state = State.IDLE
        tts?.stop()
        keepAwake(false)
        tvStatus.text = msg
    }

    private fun keepAwake(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        io.shutdown()
        super.onDestroy()
    }
}
