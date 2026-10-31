package com.example.readaloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owns the speech engine and the open book, so reading continues with the screen off
 * (foreground service + notification). Pages are loaded one at a time; the next pages are
 * prepared (text extraction / OCR) in the background while the current one is spoken.
 */
class ReaderService : Service(), TextToSpeech.OnInitListener {

    enum class State { IDLE, PLAYING, PAUSED }

    interface Listener {
        fun onTtsReady()
        fun onBookLoaded(name: String, pages: Int)
        fun onPage(page: Int, total: Int, text: String)
        fun onState(state: State)
        fun onStatus(msg: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReaderService = this@ReaderService
    }

    companion object {
        const val ACTION_PLAY = "com.example.readaloud.PLAY"
        const val ACTION_PAUSE = "com.example.readaloud.PAUSE"
        const val ACTION_STOP = "com.example.readaloud.STOP"
        private const val CHANNEL = "reading"
        private const val NOTIF_ID = 1
        private const val MAX_CHUNK = 800
        private const val READ_AHEAD = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("pos", MODE_PRIVATE) }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listener: Listener? = null

    @Volatile private var book: Book? = null
    private var bookDir: File? = null
    private var page = 0
    private var pageText = ""
    private var chunks: List<String>? = null
    private var chunkIdx = 0
    @Volatile private var gen = 0

    var state = State.IDLE
        private set
    private var isFg = false
    private var wake: PowerManager.WakeLock? = null

    // settings (set by the activity)
    private var langMode = 0            // 0 auto, 1 Hindi, 2 English
    private var hiVoice: String? = null
    private var enVoice: String? = null
    private var rate = 1f

    // ---------- lifecycle ----------

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Reading", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        tts = TextToSpeech(this, this)
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause()
            ACTION_PLAY -> play()
            ACTION_STOP -> stopReading()
            else -> startFg()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gen++
        tts?.stop()
        tts?.shutdown()
        releaseWake()
        worker.execute { book?.close() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            listener?.onStatus("Text-to-speech engine not available on this device.")
            return
        }
        ttsReady = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                val parts = id?.removePrefix("g")?.split("_") ?: return
                val g = parts[0].toIntOrNull() ?: return
                val idx = parts.getOrNull(1)?.toIntOrNull() ?: return
                main.post {
                    if (g == gen && idx == chunkIdx && state == State.PLAYING) {
                        chunkIdx++
                        savePos()
                        speakCurrent()
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                main.post {
                    if (state == State.PLAYING) {
                        pause()
                        status("Speech error. Check voice data (\"Voice data\" button).")
                    }
                }
            }
        })
        listener?.onTtsReady()
    }

    // ---------- API for the activity ----------

    fun attach(l: Listener) {
        listener = l
        if (ttsReady) l.onTtsReady()
        val b = book
        if (b != null) {
            l.onBookLoaded(b.name, b.pageCount)
            l.onPage(page, b.pageCount, pageText)
        }
        l.onState(state)
    }

    fun detach() { listener = null }

    fun canPlay(): Boolean = book != null && ttsReady

    fun voices(lang: String): List<Voice> = try {
        tts?.voices?.filter { it.locale.language == lang && !it.isNetworkConnectionRequired }
            ?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    fun setSettings(mode: Int, hi: String?, en: String?, speed: Float) {
        langMode = mode; hiVoice = hi; enVoice = en; rate = speed
    }

    fun importAndOpen(uri: Uri, name: String, size: Long, mime: String?, forceOcr: Boolean) {
        haltPlayback()
        status("Opening $name…")
        worker.execute {
            try {
                val dir = BookStore.import(this, uri, name, size, mime) { m -> main.post { status(m) } }
                doOpen(dir, forceOcr)
            } catch (e: Throwable) {
                main.post { status("Could not open file: ${e.message}") }
            }
        }
    }

    /** Re-opens the last book (with the saved position) if nothing is open yet. */
    fun restoreLast(forceOcr: Boolean) {
        if (book != null) return
        val id = prefs.getString("last", null) ?: return
        val dir = File(BookStore.root(this), id)
        if (!File(dir, "ok").exists()) return
        worker.execute {
            try { doOpen(dir, forceOcr) } catch (e: Throwable) {
                main.post { status("Could not reopen last book: ${e.message}") }
            }
        }
    }

    /** Re-reads the current book, e.g. after toggling "Always use OCR". */
    fun reopen(forceOcr: Boolean) {
        val dir = bookDir ?: return
        haltPlayback()
        worker.execute {
            try { doOpen(dir, forceOcr) } catch (e: Throwable) {
                main.post { status("Could not open file: ${e.message}") }
            }
        }
    }

    fun play() {
        if (book == null) { status("Open a file first."); return }
        if (!ttsReady) { status("Speech engine still starting…"); return }
        if (state == State.PLAYING) return
        acquireWake()
        setState(State.PLAYING)
        if (chunks != null) speakCurrent()   // otherwise loadPage() starts speaking when ready
    }

    fun pause() {
        if (state != State.PLAYING) return
        tts?.stop()
        releaseWake()
        setState(State.PAUSED)
        status("Paused at page ${page + 1}")
    }

    /** Stops reading and removes the notification. The position is kept. */
    fun stopReading() {
        haltPlayback()
        stopSelf()
    }

    fun goToPage(p: Int) {
        val b = book ?: return
        loadPage(p.coerceIn(0, b.pageCount - 1), 0)
    }

    fun currentPage(): Int = page

    // ---------- opening and paging ----------

    private fun doOpen(dir: File, forceOcr: Boolean) {   // worker thread
        book?.close()
        book = null
        val b = BookStore.open(this, dir, forceOcr)
        if (b.pageCount == 0) {
            main.post { status("No readable text found in this file.") }
            return
        }
        book = b
        bookDir = dir
        val pos = prefs.getString("pos_${dir.name}", "0,0")!!.split(",")
        val p = (pos[0].toIntOrNull() ?: 0).coerceIn(0, b.pageCount - 1)
        val c = pos.getOrNull(1)?.toIntOrNull() ?: 0
        prefs.edit().putString("last", dir.name).apply()
        main.post {
            listener?.onBookLoaded(b.name, b.pageCount)
            loadPage(p, c)
        }
    }

    private fun loadPage(p: Int, startChunk: Int) {   // main thread
        val b = book ?: return
        val myGen = ++gen
        tts?.stop()
        page = p
        chunks = null
        status("Preparing page ${p + 1}…")
        worker.execute {
            val text = try {
                b.pageText(p)
            } catch (e: Throwable) {
                main.post { if (myGen == gen) status("Page ${p + 1}: ${e.message}") }
                ""
            }
            main.post {
                if (myGen == gen) {
                    pageText = text
                    val cs = splitChunks(text)
                    chunks = cs
                    chunkIdx = startChunk.coerceIn(0, cs.size)
                    listener?.onPage(p, b.pageCount, text)
                    updateNotification()
                    savePos()
                    if (cs.isEmpty()) status("Page ${p + 1} has no text.")
                    if (state == State.PLAYING) speakCurrent()
                }
            }
            // read ahead (extraction / OCR) while this page is being spoken
            for (k in 1..READ_AHEAD) {
                if (myGen != gen) break
                if (p + k < b.pageCount) try { b.pageText(p + k) } catch (_: Throwable) {}
            }
        }
    }

    private fun savePos() {
        val d = bookDir ?: return
        prefs.edit().putString("pos_${d.name}", "$page,$chunkIdx").apply()
    }

    // ---------- speaking ----------

    private fun speakCurrent() {   // main thread
        val engine = tts ?: return
        val b = book ?: return
        if (state != State.PLAYING) return
        val cs = chunks ?: return
        if (chunkIdx >= cs.size) {
            if (page + 1 >= b.pageCount) finishBook() else loadPage(page + 1, 0)
            return
        }
        val chunk = cs[chunkIdx]
        val lang = when (langMode) { 1 -> "hi"; 2 -> "en"; else -> detectLang(chunk) }
        if (!applyLanguage(engine, lang)) {
            pause()
            status(
                if (lang == "hi") "Hindi voice data missing. Tap \"Voice data\" to install it."
                else "English voice data missing. Tap \"Voice data\" to install it."
            )
            return
        }
        engine.setSpeechRate(rate)
        status("Page ${page + 1} of ${b.pageCount} · ${if (lang == "hi") "हिंदी" else "English"}")
        engine.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "g${gen}_$chunkIdx")
    }

    private fun finishBook() {
        haltPlayback()
        status("Finished the book.")
        loadPage(0, 0)
    }

    private fun applyLanguage(engine: TextToSpeech, lang: String): Boolean {
        val candidates = if (lang == "hi") listOf(Locale("hi", "IN")) else listOf(Locale("en", "IN"), Locale.US)
        var ok = false
        for (loc in candidates) {
            val r = engine.setLanguage(loc)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) { ok = true; break }
        }
        if (!ok) return false
        val name = if (lang == "hi") hiVoice else enVoice
        if (name != null) {
            val v = try { engine.voices?.firstOrNull { it.name == name } } catch (e: Exception) { null }
            if (v != null) try { engine.voice = v } catch (_: Exception) {}
        }
        return true
    }

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

    /** Splits on sentence ends (. ! ? । and newlines) into pieces of at most MAX_CHUNK characters. */
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

    // ---------- state, notification, wake lock ----------

    private fun status(msg: String) { listener?.onStatus(msg) }

    private fun setState(s: State) {
        state = s
        listener?.onState(s)
        updateNotification()
    }

    private fun haltPlayback() {
        gen++
        tts?.stop()
        releaseWake()
        setState(State.IDLE)
        if (isFg) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            isFg = false
        }
    }

    private fun acquireWake() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val w = wake ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "readaloud:reading").also {
            it.setReferenceCounted(false)
            wake = it
        }
        w.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWake() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
    }

    private fun startFg() {
        startForeground(NOTIF_ID, buildNotification())
        isFg = true
    }

    private fun updateNotification() {
        if (!isFg) return
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun actionPi(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, ReaderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val total = book?.pageCount ?: 0
        val label = when (state) { State.PLAYING -> "Reading"; State.PAUSED -> "Paused"; State.IDLE -> "Ready" }
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(book?.name ?: "Read Aloud")
            .setContentText("Page ${page + 1} of $total · $label")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        if (state == State.PLAYING) {
            b.addAction(android.R.drawable.ic_media_pause, "Pause", actionPi(ACTION_PAUSE, 1))
        } else {
            b.addAction(android.R.drawable.ic_media_play, "Play", actionPi(ACTION_PLAY, 2))
        }
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionPi(ACTION_STOP, 3))
        return b.build()
    }
}
