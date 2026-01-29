package com.nuzzi.montheflow

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale
import kotlin.system.exitProcess
import java.io.File
import android.content.ActivityNotFoundException
import android.provider.Settings

class MainActivity : AppCompatActivity() {

    private var speechClient: AndroidSpeechClient? = null
    private var translationManager: TranslationManager? = null
    private var ttsManager: TTSManager? = null
    private var transcriptSaver: TranscriptSaver? = null
    
    // UI elements
    private var statusTextView: TextView? = null
    private var progressBar: ProgressBar? = null // NUOVO
    private var languageRadioGroup: RadioGroup? = null
    
    private var btnPlay: ImageButton? = null
    private var btnTranslateNow: ImageButton? = null
    private var btnReset: ImageButton? = null
    private var btnPause: ImageButton? = null
    private var btnStop: ImageButton? = null
    private var btnSettings: ImageButton? = null
    
    private var silenceThresholdSeekBar: android.widget.SeekBar? = null
    private var lblThresholdValue: TextView? = null
    private var currentSilenceThreshold = 500
    
    private var radioItalian: RadioButton? = null
    private var radioPortuguese: RadioButton? = null

    // Timer per gestire il silenzio manualmente (Client-Side)
    private var silenceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var silenceRunnable: Runnable? = null
    private var textBuffer = StringBuilder()
    private var lastPartialText: String = ""

    private val segmentHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var segmentRunnable: Runnable? = null
    private val maxSegmentMs = 6000
    private var lastResultAt: Long = 0L
    private var hasOpenedSpeechSettings = false
    
    // True quando streaming + recorder sono attivi (equivalente a "sessione in andamento")
    private var isStreamingActive: Boolean = false
    
    private fun logDebug(message: String, data: String = "{}") {
        try {
            val file = File("c:\\Users\\Antonio Nuzzi\\montheflow\\.cursor\\debug.log")
            val timestamp = System.currentTimeMillis()
            val json = "{\"id\":\"log_${timestamp}\",\"timestamp\":$timestamp,\"location\":\"MainActivity.kt\",\"message\":\"$message\",\"data\":$data,\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"C,D\"}\n"
            file.appendText(json)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                checkKeyAndStart()
            } else {
                Toast.makeText(this, "Permission required to record audio", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { transcriptSaver?.endSession("Session ended (CRASH)") } catch (_: Exception) {}
            Log.e("CRASH", "Uncaught exception", throwable)
            runOnUiThread {
                try {
                    statusTextView?.text = "CRASH: ${throwable.message}\n${throwable.stackTraceToString()}"
                    statusTextView?.setTextColor(android.graphics.Color.RED)
                } catch (e: Exception) {}
            }
        }
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        statusTextView = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        languageRadioGroup = findViewById(R.id.languageRadioGroup)
        
        btnPlay = findViewById(R.id.btnPlayAction)
        btnTranslateNow = findViewById(R.id.btnTranslateNow)
        btnReset = findViewById(R.id.btnReset)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        btnSettings = findViewById(R.id.btnSettings)
        
        silenceThresholdSeekBar = findViewById(R.id.silenceThresholdSeekBar)
        lblThresholdValue = findViewById(R.id.lblThresholdValue)
        
        radioItalian = findViewById(R.id.radioItalian)
        radioPortuguese = findViewById(R.id.radioPortuguese)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusTextView?.text = "Speech recognition not available on this device."
            btnPlay?.isEnabled = false
        }
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Check Google Play Services
        val googleApiAvailability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        val status = googleApiAvailability.isGooglePlayServicesAvailable(this)
        if (status != com.google.android.gms.common.ConnectionResult.SUCCESS) {
             googleApiAvailability.getErrorDialog(this, status, 9000)?.show()
        }

        transcriptSaver = TranscriptSaver(this)
        ttsManager = TTSManager(this)

        // Inizializza Traduzione con gestione stato visuale
        translationManager = TranslationManager { statusMsg, isError ->
            Log.d("MainActivity", "Translation Status: $statusMsg")
            runOnUiThread {
                if (isError) {
                    statusTextView?.text = "ERROR: $statusMsg"
                    statusTextView?.setTextColor(android.graphics.Color.RED)
                    progressBar?.visibility = View.INVISIBLE
                } else {
                    if (statusTextView?.text?.startsWith("En:") == false) {
                        statusTextView?.text = statusMsg
                        statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
                    }
                    
                    // Gestione ProgressBar
                    if (statusMsg.contains("Downloading", ignoreCase = true)) {
                        progressBar?.visibility = View.VISIBLE
                    } else if (statusMsg.contains("Ready", ignoreCase = true)) {
                        progressBar?.visibility = View.INVISIBLE
                    }
                }
            }
        }

        setupLanguageControls()
        setupButtons()
        
        // Controlla se Ã¨ il primo avvio per chiedere la lingua
        checkFirstRunAndLanguage()
        
        setupSilenceControl()
    }

    private fun setupSilenceControl() {
        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        currentSilenceThreshold = prefs.getInt("SILENCE_THRESHOLD", 500)
        
        // Converti ms a progress (0-10)
        // 500ms -> 0
        // 3000ms -> 10
        // Step 250ms
        val progress = (currentSilenceThreshold - 500) / 250
        silenceThresholdSeekBar?.max = 10 // Max 3 secondi
        silenceThresholdSeekBar?.progress = progress
        updateThresholdLabel(currentSilenceThreshold)

        silenceThresholdSeekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val newThreshold = 500 + (progress * 250)
                updateThresholdLabel(newThreshold)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                seekBar?.let {
                    val newThreshold = 500 + (it.progress * 250)
                    // #region agent log
                    logDebug("SeekBar Stop Tracking", "{\"newThreshold\":$newThreshold, \"oldThreshold\":$currentSilenceThreshold}")
                    // #endregion
                    if (newThreshold != currentSilenceThreshold) {
                        val wasActive = isStreamingActive

                        currentSilenceThreshold = newThreshold
                        prefs.edit().putInt("SILENCE_THRESHOLD", currentSilenceThreshold).apply()

                        Toast.makeText(
                            this@MainActivity,
                            "Threshold set to ${newThreshold}ms. Restarting...",
                            Toast.LENGTH_SHORT
                        ).show()

                        restartAfterThresholdChange(wasActive)
                    }
                }
            }
        })
    }

    private fun updateThresholdLabel(thresholdMs: Int) {
        lblThresholdValue?.text = String.format("%.1fs", thresholdMs / 1000f)
    }

    private fun checkFirstRunAndLanguage() {
        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean("FIRST_RUN_LANG", true)

        if (isFirstRun) {
            showLanguageSelectionDialog(prefs)
        }
    }

    private fun showLanguageSelectionDialog(prefs: android.content.SharedPreferences) {
        val languages = arrayOf("Italiano ðŸ‡®ðŸ‡¹", "PortuguÃªs ðŸ‡§ðŸ‡·")
        AlertDialog.Builder(this)
            .setTitle("Select Target Language")
            .setItems(languages) { _, which ->
                when (which) {
                    0 -> { // Italiano
                        languageRadioGroup?.check(R.id.radioItalian)
                    }
                    1 -> { // Portoghese
                        languageRadioGroup?.check(R.id.radioPortuguese)
                    }
                }
                prefs.edit().putBoolean("FIRST_RUN_LANG", false).apply()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndStart()
    }

    private fun setupLanguageControls() {
        languageRadioGroup?.setOnCheckedChangeListener { _, checkedId ->
            radioItalian?.alpha = if (checkedId == R.id.radioItalian) 1.0f else 0.4f
            radioPortuguese?.alpha = if (checkedId == R.id.radioPortuguese) 1.0f else 0.4f

            when (checkedId) {
                R.id.radioItalian -> {
                    changeTargetLanguage(TranslateLanguage.ITALIAN, Locale.ITALIAN)
                }
                R.id.radioPortuguese -> {
                    changeTargetLanguage(TranslateLanguage.PORTUGUESE, Locale("pt", "BR"))
                }
            }
        }
    }

    private fun setupButtons() {
        Log.d("MainActivity", "DEBUG: setupButtons called")
        if (btnPlay == null) {
            Log.e("MainActivity", "CRITICAL: btnPlay is NULL! Check layout ID.")
            Toast.makeText(this, "Error: Play button missing", Toast.LENGTH_LONG).show()
        } else {
            Log.d("MainActivity", "DEBUG: btnPlay found, attaching listener")
        }

        btnSettings?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        btnPlay?.setOnClickListener {
            Log.d("MainActivity", "DEBUG: User triggered PLAY (Listener OK)")
            Toast.makeText(this, "Play Clicked!", Toast.LENGTH_SHORT).show() 

            if (isStreamingActive) {
                Toast.makeText(this, "Already listening...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Session TXT starts on first Play after app launch (and continues across Pause/threshold/background)
            transcriptSaver?.ensureSessionStarted()

            isStreamingActive = true
            startTranslationFlow()
        }

        btnPause?.setOnClickListener {
            Log.d("MainActivity", "User triggered PAUSE/STOP")
            isStreamingActive = false
            
            // Ferma tutto ma non chiude l'app
            ttsManager?.interrupt()
            speechClient?.stop()
            
            // Ferma il timer del silenzio
            silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
            segmentRunnable?.let { segmentHandler.removeCallbacks(it) }
            segmentRunnable = null
            
            statusTextView?.text = "Paused. Press Play to resume."
            statusTextView?.setTextColor(android.graphics.Color.YELLOW)
        }

        btnStop?.setOnClickListener {
            Log.d("MainActivity", "User triggered STOP (End session & exit)")
            isStreamingActive = false
            endSessionAndExitApp()
        }

        btnReset?.setOnClickListener {
            Log.d("MainActivity", "User triggered RESET")
            isStreamingActive = false
            resetAppFlow()
        }
        
        btnReset?.setOnLongClickListener {
            Toast.makeText(this, "Hard Reset: Cleaning translation models...", Toast.LENGTH_LONG).show()
            translationManager?.deleteModelsAndReset()
            true
        }

        btnTranslateNow?.setOnClickListener {
            Log.d("MainActivity", "User triggered TRANSLATE NOW")
            statusTextView?.append("\n[FORCING CUT...]")
            
            // Forza il timer locale a scattare subito se c'Ã¨ roba nel buffer
            silenceRunnable?.let {
                silenceHandler.removeCallbacks(it)
                it.run() // Esegue immediatamente la logica di traduzione
            }
            
            speechClient?.forceEndTurn()
        }
    }

    private fun restartAfterThresholdChange(wasActive: Boolean) {
        runOnUiThread {
            // Stop clean (senza chiudere sessione txt)
            try { ttsManager?.interrupt() } catch (_: Exception) {}
            try { speechClient?.stop() } catch (_: Exception) {}

            // Stop timer silenzio
            silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
            silenceRunnable = null
            segmentRunnable?.let { segmentHandler.removeCallbacks(it) }
            segmentRunnable = null
            textBuffer.clear()

            // Se era attivo, riparte automaticamente come se avessi premuto Play
            if (wasActive) {
                statusTextView?.text = "Restarting with new threshold..."
                statusTextView?.setTextColor(android.graphics.Color.YELLOW)

                // Piccolo delay per evitare race tra stop/start
                statusTextView?.postDelayed({
                    startTranslationFlow()
                }, 250)
            } else {
                // Se non era attivo, torna allo stato "Ready. Press Play..."
                isStreamingActive = false
                checkPermissionsAndStart()
            }
        }
    }

    private fun resetAppFlow() {
        isStreamingActive = false
        // #region agent log
        logDebug("resetAppFlow called")
        // #endregion
        runOnUiThread {
            ttsManager?.interrupt()
            speechClient?.stop()
            
            statusTextView?.text = "Resetting..."
            
            statusTextView?.postDelayed({
                statusTextView?.text = "Restarting..."
                checkPermissionsAndStart()
            }, 500)
        }
    }

    private fun endSessionAndExitApp() {
        // 1) Ferma timer silenzio
        silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
        silenceRunnable = null
        segmentRunnable?.let { segmentHandler.removeCallbacks(it) }
        segmentRunnable = null

        // 2) Ferma audio + websocket + TTS
        try { ttsManager?.interrupt() } catch (_: Exception) {}
        try { ttsManager?.stop() } catch (_: Exception) {}
        try { speechClient?.stop() } catch (_: Exception) {}

        // Close transcript session explicitly
        try { transcriptSaver?.endSession("Session ended by STOP") } catch (_: Exception) {}

        // 3) Chiudi translator (rilascia risorse)
        try { translationManager?.close() } catch (_: Exception) {}

        // 4) UI feedback minimo
        runOnUiThread {
            statusTextView?.text = "Session ended."
            statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
        }

        // 5) Esci dall'app (chiusura vera, non pausa)
        try {
            finishAffinity() // chiude tutte le Activity dell'app
        } catch (_: Exception) {}

        // Forza terminazione processo per "uscita netta"
        kotlin.system.exitProcess(0)
    }

    private fun changeTargetLanguage(mlKitLangCode: String, ttsLocale: Locale) {
        // Avvia download modello
        translationManager?.setTargetLanguage(mlKitLangCode)
        
        val isTTSReady = ttsManager?.setLanguage(ttsLocale) ?: false
        if (!isTTSReady) {
            val msg = "Voice data for ${ttsLocale.displayLanguage} missing!"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            openTTSCheck()
        }
    }

    private fun openTTSCheck() {
        try {
            val intent = Intent()
            intent.action = android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open TTS settings", e)
        }
    }

    private fun setupSpeechRecognizer() {
        speechClient = AndroidSpeechClient(this, object : AndroidSpeechClient.TranscriptionListener {
            override fun onConnected() {
                runOnUiThread {
                    statusTextView?.append("\nListening... (Silence: ${currentSilenceThreshold}ms)\nSpeak now...")
                }
            }

            override fun onSpeechStart() {
                startSegmentTimer()
            }

            override fun onSpeechEnd() {
                // End-of-speech may still deliver results; keep segment timer for forced cut
            }

            override fun onTranscription(text: String, isFinal: Boolean) {
                if (text.isNotBlank()) {
                    lastResultAt = System.currentTimeMillis()
                }
                handleTranscription(text, isFinal)

                if (isFinal && isStreamingActive) {
                    statusTextView?.postDelayed({
                        if (isStreamingActive) {
                            speechClient?.start()
                        }
                    }, 250)
                }
            }

            override fun onError(errorCode: Int, error: String) {
                Log.e("MainActivity", "Error: $error (code=$errorCode)")

                val isLanguageError =
                    errorCode == android.speech.SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        errorCode == android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

                if (isLanguageError) {
                    isStreamingActive = false
                    try { speechClient?.stop() } catch (_: Exception) {}
                    runOnUiThread {
                        statusTextView?.text =
                            "English speech recognition not available.\n" +
                                "Install English (US) speech pack for the active recognizer " +
                                "(Android System Intelligence or Google), then press Play."
                        statusTextView?.setTextColor(android.graphics.Color.RED)
                    }
                    if (!hasOpenedSpeechSettings) {
                        hasOpenedSpeechSettings = true
                        openSpeechLanguageSettings()
                    }
                    return
                }

                val now = System.currentTimeMillis()
                val recentlyHadResult = (now - lastResultAt) < 1500

                val isBenign = errorCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                    errorCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                if (!recentlyHadResult && !isBenign) {
                    runOnUiThread {
                        statusTextView?.text = "Error: $error"
                    }
                }

                if (isStreamingActive) {
                    val delayMs = when (errorCode) {
                        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        android.speech.SpeechRecognizer.ERROR_CLIENT,
                        android.speech.SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 1000L
                        else -> 500L
                    }
                    statusTextView?.postDelayed({
                        if (isStreamingActive) {
                            speechClient?.destroy()
                            setupSpeechRecognizer()
                            speechClient?.start()
                        }
                    }, delayMs)
                }
            }
        })
    }

    private fun openSpeechLanguageSettings() {
        val intents = listOf(
            Intent().setClassName(
                "com.google.android.as",
                "com.google.android.apps.miphone.aiai.speech.languagepacks.settings.ui.SettingsActivity"
            ),
            Intent("com.google.android.speech.embedded.MANAGE_LANGUAGES")
                .setPackage("com.google.android.googlequicksearchbox"),
            Intent().setClassName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.libraries.speech.modelmanager.languagepack.settings.SettingsActivity"
            ),
            Intent().setClassName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.libraries.speech.modelmanager.languagepack.settings.AddLanguagesActivity"
            ),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        )

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // Try next
            } catch (_: Exception) {
                // Ignore and continue
            }
        }

        Toast.makeText(
            this,
            "Unable to open speech language settings. Please install English (US) voice input.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun startSegmentTimer() {
        if (segmentRunnable != null) return

        segmentRunnable = Runnable {
            val textToTranslate = textBuffer.toString().trim().ifEmpty { lastPartialText.trim() }
            if (textToTranslate.isNotEmpty()) {
                processTranslation(textToTranslate)
                textBuffer.clear()
                lastPartialText = ""
            }
            silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
            segmentRunnable = null
            if (isStreamingActive) {
                speechClient?.forceEndTurn()
            }
        }
        segmentHandler.postDelayed(segmentRunnable!!, maxSegmentMs.toLong())
    }

    private fun clearSegmentTimer() {
        segmentRunnable?.let { segmentHandler.removeCallbacks(it) }
        segmentRunnable = null
    }

    private fun handleTranscription(text: String, isFinal: Boolean) {
        // LOGICA CLIENT-SIDE SILENCE:
        // Ignoriamo parzialmente isFinal. Accumuliamo tutto e usiamo il nostro timer.
        runOnUiThread {
            // Se arriva nuovo testo, resettiamo il timer
            silenceRunnable?.let { silenceHandler.removeCallbacks(it) }

            // Se Ã¨ un parziale, aggiorniamo solo la UI "En: ..."
            // Se Ã¨ Final, lo aggiungiamo al nostro buffer
            if (isFinal) {
                if (textBuffer.isNotEmpty()) textBuffer.append(" ")
                textBuffer.append(text)
                lastPartialText = ""

                // Aggiorniamo UI provvisoria con tutto il buffer
                statusTextView?.text = "En: $textBuffer..."
            } else {
                // Per i parziali, mostriamo Buffer confermato + Parziale corrente
                lastPartialText = text
                val currentView = if (textBuffer.isNotEmpty()) "$textBuffer $text" else text
                statusTextView?.text = "En: $currentView..."
            }

            // Avviamo il timer di silenzio
            silenceRunnable = Runnable {
                // IL TIMER ? SCATTATO!
                val textToTranslate = textBuffer.toString().trim().ifEmpty { lastPartialText.trim() }
                if (textToTranslate.isNotEmpty()) {
                    processTranslation(textToTranslate)
                    textBuffer.clear()
                    lastPartialText = ""
                }
                clearSegmentTimer()
            }

            // Usiamo il valore della levetta come ritardo
            silenceHandler.postDelayed(silenceRunnable!!, currentSilenceThreshold.toLong())

            startSegmentTimer()
        }
    }

    private fun processTranslation(text: String) {
        val debugInfo = " [Timer: ${currentSilenceThreshold}ms]"
        
        translationManager?.translate(text) { translatedText ->
            Log.d("MainActivity", "Translated: $translatedText")
            runOnUiThread {
                statusTextView?.text = "En: $text$debugInfo\nTarget: $translatedText"
            }
            
            val logLine = "[EN]: $text$debugInfo\n[TR]: $translatedText\n"
            transcriptSaver?.append(logLine)
            
            ttsManager?.speak(translatedText)
        }
    }

    private fun checkPermissionsAndStart() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusTextView?.text = "Speech recognition not available on this device."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            checkKeyAndStart()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkKeyAndStart() {
        // Non avviamo automaticamente. Aspettiamo il Play.
        statusTextView?.text = "Ready. Press Play to start."
    }

    private fun startTranslationFlow() {
        Log.d("MainActivity", "DEBUG: Inside startTranslationFlow")
        try {
            statusTextView?.text = "Connecting..."
            statusTextView?.setTextColor(android.graphics.Color.YELLOW)

            // Cleanup preventivo
            speechClient?.destroy()

            Log.d("MainActivity", "DEBUG: Calling setupSpeechRecognizer")
            setupSpeechRecognizer()
            
            // #region agent log
            logDebug("Starting translation flow with threshold", "$currentSilenceThreshold")
            // #endregion
            Log.d("MainActivity", "DEBUG: Starting SpeechRecognizer")
            speechClient?.start()
            isStreamingActive = true
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting translation flow", e)
            statusTextView?.text = "Error starting: ${e.message}"
            statusTextView?.setTextColor(android.graphics.Color.RED)
            isStreamingActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreamingActive = false
        speechClient?.destroy()
        translationManager?.close()
        ttsManager?.stop()
        try { transcriptSaver?.endSession("Session ended (onDestroy)") } catch (_: Exception) {}
    }
}
