package com.example.readaloud

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), ReaderService.Listener {

    private lateinit var tvName: TextView
    private lateinit var tvPage: TextView
    private lateinit var tvBody: TextView
    private lateinit var scroll: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var etGo: EditText
    private lateinit var rgLang: RadioGroup
    private lateinit var spHi: Spinner
    private lateinit var spEn: Spinner
    private lateinit var sbSpeed: SeekBar
    private lateinit var cbOcr: CheckBox
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button

    private var service: ReaderService? = null
    private var pendingUri: Uri? = null
    private var hiVoices: List<Voice> = emptyList()
    private var enVoices: List<Voice> = emptyList()
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private val REQ_OPEN = 1001

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            val s = (b as ReaderService.LocalBinder).getService()
            service = s
            s.attach(this@MainActivity)
            pushSettings()
            val u = pendingUri
            if (u != null) {
                pendingUri = null
                openUri(u)
            } else {
                s.restoreLast(cbOcr.isChecked)
            }
        }

        override fun onServiceDisconnected(n: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvName = findViewById(R.id.tvName)
        tvPage = findViewById(R.id.tvPage)
        tvBody = findViewById(R.id.tvBody)
        scroll = findViewById(R.id.scroll)
        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        etGo = findViewById(R.id.etGo)
        rgLang = findViewById(R.id.rgLang)
        spHi = findViewById(R.id.spHi)
        spEn = findViewById(R.id.spEn)
        sbSpeed = findViewById(R.id.sbSpeed)
        cbOcr = findViewById(R.id.cbOcr)
        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)

        // restore saved UI settings
        sbSpeed.progress = prefs.getInt("speed", 33)
        tvSpeed.text = String.format(Locale.US, "%.1fx", rate())
        rgLang.check(
            when (prefs.getInt("lang", 0)) { 1 -> R.id.rbHi; 2 -> R.id.rbEn; else -> R.id.rbAuto }
        )
        cbOcr.isChecked = prefs.getBoolean("ocr", false)

        findViewById<Button>(R.id.btnOpen).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnVoiceData).setOnClickListener { openVoiceSettings() }
        findViewById<Button>(R.id.btnPlay).setOnClickListener { play() }
        btnPause.setOnClickListener { service?.pause() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { service?.stopReading() }
        findViewById<Button>(R.id.btnPrev).setOnClickListener { service?.let { it.goToPage(it.currentPage() - 1) } }
        findViewById<Button>(R.id.btnNext).setOnClickListener { service?.let { it.goToPage(it.currentPage() + 1) } }
        findViewById<Button>(R.id.btnGo).setOnClickListener { goToTypedPage() }

        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvSpeed.text = String.format(Locale.US, "%.1fx", rate())
                if (fromUser) pushSettings()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        rgLang.setOnCheckedChangeListener { _, _ -> pushSettings() }
        cbOcr.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("ocr", checked).apply()
            service?.reopen(checked)
        }

        if (intent?.action == Intent.ACTION_VIEW) pendingUri = intent.data
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { openUri(it) }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ReaderService::class.java), conn, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.detach()
        service = null
        try { unbindService(conn) } catch (_: Exception) {}
        super.onStop()
    }

    // ---------- file opening ----------

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "text/plain",
                    "image/*"
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
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) data?.data?.let { openUri(it) }
    }

    private fun openUri(uri: Uri) {
        val s = service
        if (s == null) { pendingUri = uri; return }
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val z = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (z >= 0 && !c.isNull(z)) size = c.getLong(z)
            }
        }
        s.importAndOpen(uri, name, size, contentResolver.getType(uri), cbOcr.isChecked)
    }

    // ---------- playback controls ----------

    private fun play() {
        val s = service ?: return
        if (!s.canPlay()) { toast("Open a file first (and wait for the speech engine)"); return }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        pushSettings()
        val i = Intent(this, ReaderService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        s.play()
    }

    private fun goToTypedPage() {
        val n = etGo.text.toString().trim().toIntOrNull()
        if (n == null || n < 1) { toast("Enter a page number"); return }
        service?.goToPage(n - 1)
        etGo.text.clear()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(etGo.windowToken, 0)
    }

    private fun rate(): Float = 0.5f + sbSpeed.progress / 100f * 1.5f

    private fun voiceName(sp: Spinner, list: List<Voice>): String? {
        val pos = sp.selectedItemPosition
        return if (pos > 0 && pos - 1 < list.size) list[pos - 1].name else null
    }

    private fun pushSettings() {
        val mode = when (rgLang.checkedRadioButtonId) { R.id.rbHi -> 1; R.id.rbEn -> 2; else -> 0 }
        val hi = voiceName(spHi, hiVoices)
        val en = voiceName(spEn, enVoices)
        service?.setSettings(mode, hi, en, rate())
        if (hiVoices.isNotEmpty() || enVoices.isNotEmpty()) {
            prefs.edit().putInt("speed", sbSpeed.progress).putInt("lang", mode)
                .putString("hi", hi).putString("en", en).apply()
        } else {
            prefs.edit().putInt("speed", sbSpeed.progress).putInt("lang", mode).apply()
        }
    }

    private fun openVoiceSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (e: Exception) {
            try {
                startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            } catch (e2: Exception) {
                toast("Open Settings > Language > Text-to-speech")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // refresh the voice list after returning from voice settings
        service?.let { if (it.canPlay()) fillVoices(it) }
    }

    // ---------- ReaderService.Listener ----------

    override fun onTtsReady() {
        service?.let { fillVoices(it) }
    }

    private fun fillVoices(s: ReaderService) {
        hiVoices = s.voices("hi")
        enVoices = s.voices("en")
        fill(spHi, hiVoices, prefs.getString("hi", null))
        fill(spEn, enVoices, prefs.getString("en", null))
        if (hiVoices.isEmpty()) tvStatus.text = "No Hindi voice installed. Tap \"Voice data\" to download one."
        val l = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = pushSettings()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spHi.onItemSelectedListener = l
        spEn.onItemSelectedListener = l
        pushSettings()
    }

    private fun fill(sp: Spinner, voices: List<Voice>, saved: String?) {
        val labels = mutableListOf("Default")
        voices.forEach { labels.add("${it.name}  (${it.locale.displayCountry})") }
        sp.onItemSelectedListener = null
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = voices.indexOfFirst { it.name == saved }
        if (idx >= 0) sp.setSelection(idx + 1)
    }

    override fun onBookLoaded(name: String, pages: Int) {
        tvName.text = name
    }

    override fun onPage(page: Int, total: Int, text: String) {
        tvPage.text = "Page ${page + 1} / $total"
        tvBody.text = text
        scroll.scrollTo(0, 0)
    }

    override fun onState(state: ReaderService.State) {
        btnPlay.isEnabled = state != ReaderService.State.PLAYING
        btnPause.isEnabled = state == ReaderService.State.PLAYING
    }

    override fun onStatus(msg: String) {
        tvStatus.text = msg
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
