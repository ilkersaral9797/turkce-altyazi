package com.altyazi.canli

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Oyunun sesini mikrofondan dinler, İngilizce konuşmayı yazıya döker
 * ve telefonun içinde Türkçeye çevirip altyazı gibi gösterir.
 */
class MainActivity : Activity(), RecognitionListener {

    private lateinit var tvStatus: TextView
    private lateinit var tvPrev: TextView
    private lateinit var tvNow: TextView
    private lateinit var tvEnglish: TextView
    private lateinit var btnStart: Button
    private lateinit var btnEn: Button
    private lateinit var tvDiag: TextView
    private lateinit var level: ProgressBar

    // Teşhis bilgileri
    private var serviceName = "?"
    private var translatorState = "hazırlanıyor"
    private var heard = 0
    private var translatedCount = 0
    private var lastError = "yok"
    private var readyCount = 0
    private var beginCount = 0
    private var partialCount = 0
    private var resultCount = 0
    private val errorCounts = LinkedHashMap<Int, Int>()

    /** Telefonda bulunan ses tanıma servisleri; null = telefonun varsayılanı */
    private val services = ArrayList<ComponentName?>()
    private var serviceIndex = 0

    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var modelReady = false

    /** Kullanıcı dinlemenin açık olmasını istiyor mu */
    private var listening = false

    private val handler = Handler(Looper.getMainLooper())
    private val restartTask = Runnable { listenOnce() }

    /** Tamamlanmış Türkçe satırlar (en yenisi en sonda) */
    private val finals = ArrayList<String>()

    /** Konuşma sürerken anlık çeviri */
    private var livePartial: String? = null
    private var seq = 0
    private var lastPartialAt = 0L

    /** Uzun konuşmayı beklemeden parça parça altyazıya çevirmek için */
    private var committedWords = 0
    private val chunkWords = 8

    private var fontSp = 30f
    private var showEnglish = true

    private lateinit var audio: AudioManager
    private val mutedStreams = ArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        tvStatus = findViewById(R.id.status)
        tvPrev = findViewById(R.id.prev)
        tvNow = findViewById(R.id.now)
        tvEnglish = findViewById(R.id.english)
        btnStart = findViewById(R.id.start)
        btnEn = findViewById(R.id.toggleEn)
        tvDiag = findViewById(R.id.diag)
        level = findViewById(R.id.level)
        loadServices()
        findViewById<Button>(R.id.switchService).setOnClickListener { switchService() }

        val prefs = getSharedPreferences("ayarlar", MODE_PRIVATE)
        fontSp = prefs.getFloat("yazi", 30f)
        showEnglish = prefs.getBoolean("ingilizce", true)
        applyFont()
        updateEnButton()

        btnStart.setOnClickListener { if (listening) stopListening("Durduruldu.") else startIfAllowed() }
        findViewById<Button>(R.id.smaller).setOnClickListener { changeFont(-3f) }
        findViewById<Button>(R.id.bigger).setOnClickListener { changeFont(3f) }
        btnEn.setOnClickListener {
            showEnglish = !showEnglish
            prefs.edit().putBoolean("ingilizce", showEnglish).apply()
            tvEnglish.visibility = if (showEnglish) View.VISIBLE else View.GONE
            updateEnButton()
        }

        setupTranslator()
        updateDiag()
    }

    // ---------- Çeviri (ML Kit, telefonun içinde) ----------

    private fun setupTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val t = Translation.getClient(options)
        translator = t
        status("Çeviri modeli hazırlanıyor… (ilk açılışta yaklaşık 30 MB indirilir)")
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                modelReady = true
                translatorState = "hazır"
                updateDiag()
                if (!listening) status("Hazır. Başlat'a dokun.")
            }
            .addOnFailureListener { e ->
                translatorState = "indirilemedi"
                updateDiag()
                status("Çeviri modeli indirilemedi (" + (e.message ?: "bilinmeyen hata") + "). İnternete bağlanıp uygulamayı yeniden aç.")
            }
    }

    private fun translate(english: String, isFinal: Boolean) {
        val t = translator ?: return
        if (!modelReady) {
            if (isFinal) commit("(Çeviri modeli henüz hazır değil)")
            return
        }
        val mySeq = seq
        t.translate(english)
            .addOnSuccessListener { turkish ->
                if (isFinal) {
                    translatedCount++
                    updateDiag()
                    commit(turkish)
                } else if (mySeq == seq) {
                    livePartial = turkish
                    render()
                }
            }
            .addOnFailureListener { e ->
                lastError = "çeviri: " + (e.message ?: "?")
                updateDiag()
                if (isFinal) commit("(Çevrilemedi)")
            }
    }

    private fun commit(turkish: String) {
        val clean = turkish.trim()
        if (clean.isEmpty()) return
        finals.add(clean)
        while (finals.size > 30) finals.removeAt(0)
        render()
    }

    private fun render() {
        val partial = livePartial
        if (partial != null) {
            tvNow.text = partial
            tvPrev.text = finals.takeLast(2).joinToString("\n")
        } else if (finals.isNotEmpty()) {
            tvNow.text = finals.last()
            tvPrev.text = finals.dropLast(1).takeLast(2).joinToString("\n")
        }
    }

    // ---------- Dinleme (telefonun konuşma tanıma hizmeti) ----------

    private fun startIfAllowed() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else {
            startListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            status("Mikrofon izni olmadan dinleyemem. Ayarlar > Uygulamalar > Türkçe Altyazı > İzinler'den mikrofonu aç.")
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status("Telefonda konuşma tanıma hizmeti bulunamadı. Play Store'dan \"Speech Recognition & Synthesis from Google\" uygulamasını kur ya da güncelle.")
            return
        }
        listening = true
        muteBeeps()
        createRecognizer()
        listenOnce()
        btnStart.text = "Durdur"
        status("Dinliyor…")
    }

    private fun stopListening(message: String) {
        listening = false
        handler.removeCallbacks(restartTask)
        recognizer?.let {
            try { it.cancel() } catch (e: Exception) {}
            try { it.destroy() } catch (e: Exception) {}
        }
        recognizer = null
        livePartial = null
        render()
        level.progress = 0
        unmuteBeeps()
        btnStart.text = "Başlat"
        status(message)
    }

    private fun createRecognizer() {
        recognizer?.let { try { it.destroy() } catch (e: Exception) {} }
        val comp = services.getOrNull(serviceIndex)
        serviceName = serviceLabel(comp)
        recognizer = try {
            if (comp != null) SpeechRecognizer.createSpeechRecognizer(this, comp)
            else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            lastError = "servis açılamadı"
            null
        }
        recognizer?.setRecognitionListener(this)
        updateDiag()
    }

    /** Telefondaki ses tanıma servislerini bulur; Google'ınkiler öne alınır. */
    private fun loadServices() {
        services.clear()
        val found = try {
            packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        } catch (e: Exception) {
            emptyList()
        }
        val comps = found.mapNotNull { it.serviceInfo }.map { ComponentName(it.packageName, it.name) }
        val order = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        val sorted = comps.sortedBy { c -> order.indexOf(c.packageName).let { if (it < 0) 99 else it } }
        services.addAll(sorted)
        services.add(null)
        val saved = getSharedPreferences("ayarlar", MODE_PRIVATE).getString("servis", null)
        val idx = services.indexOfFirst { (it?.flattenToString() ?: "varsayilan") == saved }
        serviceIndex = if (idx >= 0) idx else 0
        serviceName = serviceLabel(services.getOrNull(serviceIndex))
    }

    private fun serviceLabel(c: ComponentName?): String {
        if (c == null) return "Telefonun varsayılanı"
        val base = when {
            c.packageName == "com.google.android.googlequicksearchbox" -> "Google uygulaması"
            c.packageName == "com.google.android.tts" -> "Google Konuşma Hizmetleri"
            c.packageName == "com.google.android.as" -> "Android Sistem Zekası"
            c.packageName.startsWith("com.samsung") -> "Samsung"
            else -> c.packageName
        }
        return base + " (" + c.className.substringAfterLast('.') + ")"
    }

    private fun switchService() {
        val wasListening = listening
        if (wasListening) stopListening("")
        serviceIndex = (serviceIndex + 1) % services.size
        val comp = services[serviceIndex]
        getSharedPreferences("ayarlar", MODE_PRIVATE).edit()
            .putString("servis", comp?.flattenToString() ?: "varsayilan").apply()
        serviceName = serviceLabel(comp)
        readyCount = 0; beginCount = 0; partialCount = 0; resultCount = 0; heard = 0; translatedCount = 0
        errorCounts.clear(); lastError = "yok"
        updateDiag()
        status("Servis: $serviceName (" + (serviceIndex + 1) + "/" + services.size + ")")
        if (wasListening) startListening()
    }

    private fun listenOnce() {
        if (!listening) return
        committedWords = 0
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // Cümle sonunu daha kısa sessizlikte kabul et (bazı servisler yok sayabilir)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restartSoon(500, recreate = true)
        }
    }

    private fun restartSoon(delayMs: Long, recreate: Boolean) {
        handler.removeCallbacks(restartTask)
        if (!listening) return
        if (recreate) createRecognizer()
        handler.postDelayed(restartTask, delayMs)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialCount++
        updateDiag()
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return
        showEnglishText(text)

        val words = text.split(Regex("\\s+"))
        if (committedWords > words.size) committedWords = 0 // servis metni baştan düzelttiyse
        val pending = words.drop(committedWords)

        // Konuşma uzadıysa cümle bitmesini bekleme: ilk kısmı hemen altyazı yap.
        // Son 2 kelimeyi bekletiyoruz çünkü servis onları hâlâ değiştirebilir.
        if (pending.size >= chunkWords) {
            val take = pending.size - 2
            val chunk = pending.take(take).joinToString(" ")
            committedWords += take
            seq++
            livePartial = null
            heard++
            updateDiag()
            translate(chunk, isFinal = true)
            val rest = pending.drop(take).joinToString(" ")
            if (rest.isNotBlank()) translate(rest, isFinal = false)
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastPartialAt < 120) return
        lastPartialAt = now
        translate(pending.joinToString(" "), isFinal = false)
    }

    override fun onResults(results: Bundle?) {
        resultCount++
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        val words = if (text.isEmpty()) emptyList() else text.split(Regex("\\s+"))
        val remainder = if (committedWords < words.size) words.drop(committedWords).joinToString(" ") else ""
        committedWords = 0
        seq++ // bu cümleye ait geç gelen anlık çevirileri yok say
        livePartial = null
        if (remainder.isNotBlank()) {
            heard++
            updateDiag()
            showEnglishText(text)
            translate(remainder, isFinal = true)
        } else {
            render()
        }
        restartSoon(60, recreate = false)
    }

    override fun onError(error: Int) {
        if (!listening) return
        errorCounts[error] = (errorCounts[error] ?: 0) + 1
        updateDiag()
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            lastError = errorName(error)
            updateDiag()
        }
        when (error) {
            12, 13 -> { // dil desteklenmiyor / dil paketi yok
                stopListening("Ses tanıma İngilizceyi desteklemiyor. Google uygulamasını güncelle; Ayarlar > Genel yönetim > Dil'den İngilizce ses tanımayı indir.")
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                stopListening("Mikrofon izni yok. Ayarlardan izin verip tekrar Başlat'a dokun.")
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                status("İnternet bağlantısı sorunlu, tekrar deneniyor…")
                restartSoon(1500, recreate = true)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> restartSoon(400, recreate = true)
            else -> {
                // Sessizlik ya da anlaşılmayan ses: hemen yeniden dinle
                if (tvStatus.text != "Dinliyor…") status("Dinliyor…")
                restartSoon(100, recreate = false)
            }
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        readyCount++
        updateDiag()
    }

    override fun onBeginningOfSpeech() {
        beginCount++
        updateDiag()
    }
    override fun onRmsChanged(rmsdB: Float) {
        level.progress = ((rmsdB + 2f) * 8f).toInt().coerceIn(0, 100)
    }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ---------- Bip sesini kapatma ----------
    // Konuşma tanıma her yeniden başladığında bip çalabilir; dinlerken bu sesleri kısarız.

    private fun muteBeeps() {
        val streams = intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)
        for (s in streams) {
            try {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams.add(s)
                }
            } catch (e: Exception) {
                // Bazı telefonlarda "Rahatsız etmeyin" izni olmadan bildirim sesi kısılamaz; sorun değil.
            }
        }
    }

    private fun unmuteBeeps() {
        for (s in mutedStreams) {
            try { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) {}
        }
        mutedStreams.clear()
    }

    // ---------- Yardımcılar ----------

    private fun showEnglishText(text: String) {
        tvEnglish.text = text
        tvEnglish.visibility = if (showEnglish) View.VISIBLE else View.GONE
    }

    private fun changeFont(delta: Float) {
        fontSp = (fontSp + delta).coerceIn(18f, 60f)
        getSharedPreferences("ayarlar", MODE_PRIVATE).edit().putFloat("yazi", fontSp).apply()
        applyFont()
    }

    private fun applyFont() {
        tvNow.textSize = fontSp
        tvPrev.textSize = fontSp * 0.6f
    }

    private fun updateEnButton() {
        btnEn.text = if (showEnglish) "EN gizle" else "EN göster"
    }

    private fun errorName(code: Int): String = when (code) {
        1 -> "ağ zaman aşımı (1)"
        2 -> "ağ hatası (2)"
        3 -> "mikrofon/ses kaydı (3)"
        4 -> "sunucu (4)"
        5 -> "istemci (5)"
        6 -> "konuşma yok (6)"
        7 -> "anlaşılmadı (7)"
        8 -> "tanıyıcı meşgul (8)"
        9 -> "izin yok (9)"
        10 -> "çok fazla istek (10)"
        11 -> "sunucu bağlantısı koptu (11)"
        12 -> "dil desteklenmiyor (12)"
        13 -> "dil paketi yok (13)"
        else -> "kod $code"
    }

    private fun updateDiag() {
        val errs = if (errorCounts.isEmpty()) "yok" else errorCounts.entries.joinToString(", ") { "${it.key}×${it.value}" }
        tvDiag.text = "Ses tanıma: $serviceName\nÇeviri: $translatorState · Duyulan: $heard · Çevrilen: $translatedCount\n" +
            "Hazır: $readyCount · Konuşma: $beginCount · Ara sonuç: $partialCount · Sonuç: $resultCount\nHata kodları: $errs · Son hata: $lastError"
    }

    private fun status(message: String) {
        tvStatus.text = message
    }

    override fun onStop() {
        super.onStop()
        // Android, arka plandaki uygulamaların mikrofonu kullanmasına izin vermez.
        if (listening) stopListening("Uygulamadan çıkıldığı için dinleme durdu. Devam etmek için Başlat'a dokun.")
    }

    override fun onDestroy() {
        if (listening) stopListening("")
        translator?.close()
        super.onDestroy()
    }
}
