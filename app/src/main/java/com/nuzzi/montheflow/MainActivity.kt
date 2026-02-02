package com.nuzzi.montheflow

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
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

class MainActivity : AppCompatActivity() {

    private lateinit var assemblyAIClient: AssemblyAIClient
    private var audioRecorderManager: AudioRecorderManager? = null
    private var translationManager: TranslationManager? = null
    private var ttsManager: TTSManager? = null
    private var transcriptSaver: TranscriptSaver? = null
    private var openAIClient: OpenAIRealtimeClient? = null
    private var openAIAudioPlayer: OpenAIAudioPlayer? = null
    
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

    private var currentApiKey: String = ""
    private var openAIApiKey: String = ""
    private var useOpenAI: Boolean = false
    
    // Timer per gestire il silenzio manualmente (Client-Side)
    private var silenceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var silenceRunnable: Runnable? = null
    private var textBuffer = StringBuilder()
    private var openAIInputText: String = ""
    private var openAIOutputText: String = ""
    private var openAILastInputFinal: String = ""
    
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

        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        useOpenAI = prefs.getBoolean("USE_OPENAI", false)
        AppLogger.init(this)

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
            if (useOpenAI) return@TranslationManager
            Log.d("MainActivity", "Translation Status: $statusMsg")
            runOnUiThread {
                if (isError) {
                    statusTextView?.text = "ERROR: $statusMsg"
                    statusTextView?.setTextColor(android.graphics.Color.RED)
                    progressBar?.visibility = View.INVISIBLE
                    btnPlay?.isEnabled = true
                    btnPlay?.alpha = 1.0f
                } else {
                    // Gestione ProgressBar e Play Button
                    if (statusMsg.contains("Downloading", ignoreCase = true)) {
                        progressBar?.visibility = View.VISIBLE
                        btnPlay?.isEnabled = false
                        btnPlay?.alpha = 0.5f // Visivamente disabilitato
                        statusTextView?.text = "Preparing language... Please wait."
                        statusTextView?.setTextColor(android.graphics.Color.YELLOW)
                    } else if (statusMsg.contains("Ready", ignoreCase = true)) {
                        progressBar?.visibility = View.INVISIBLE
                        btnPlay?.isEnabled = true
                        btnPlay?.alpha = 1.0f
                        if (statusTextView?.text?.contains("Preparing") == true) {
                             statusTextView?.text = "Ready. Press Play to start."
                             statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
                        }
                    }
                    
                    if (statusTextView?.text?.startsWith("En:") == false && !statusMsg.contains("Downloading") && !statusMsg.contains("Ready")) {
                        statusTextView?.text = statusMsg
                        statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
                    }
                }
            }
        }

        setupLanguageControls()
        setupButtons()
        
        // Controlla se è il primo avvio per chiedere la lingua
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
        val languages = arrayOf("Italiano 🇮🇹", "Português 🇧🇷")
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
        validateApiKeyAndInit()
    }

    private fun validateApiKeyAndInit() {
        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        useOpenAI = prefs.getBoolean("USE_OPENAI", false)

        if (useOpenAI) {
            val storedKey = prefs.getString("OPENAI_KEY", "")
            if (storedKey.isNullOrEmpty()) {
                statusTextView?.text = "OpenAI API Key Missing!\nPlease configure settings."
                startSettingsBlink()
                return
            }
            progressBar?.visibility = View.INVISIBLE
            stopSettingsBlink()
            openAIApiKey = storedKey
        } else {
            val storedKey = prefs.getString("ASSEMBLYAI_KEY", "")
            if (storedKey.isNullOrEmpty()) {
                statusTextView?.text = "API Key Missing!\nPlease configure settings."
                startSettingsBlink()
                return
            }
            stopSettingsBlink()
            currentApiKey = storedKey
        }

        checkPermissionsAndStart()
    }

    private var blinkAnimator: ObjectAnimator? = null
    
    private fun startSettingsBlink() {
        if (blinkAnimator == null) {
            blinkAnimator = ObjectAnimator.ofFloat(btnSettings, "alpha", 1f, 0.2f, 1f).apply {
                duration = 1000
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun stopSettingsBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        btnSettings?.alpha = 1f
    }

    private fun setupLanguageControls() {
        languageRadioGroup?.setOnCheckedChangeListener { _, checkedId ->
            radioItalian?.alpha = if (checkedId == R.id.radioItalian) 1.0f else 0.4f
            radioPortuguese?.alpha = if (checkedId == R.id.radioPortuguese) 1.0f else 0.4f

            when (checkedId) {
                R.id.radioItalian -> {
                    if (useOpenAI) {
                        openAIClient?.updateTargetLanguage("Italian", currentSilenceThreshold)
                    } else {
                        changeTargetLanguage(TranslateLanguage.ITALIAN, Locale.ITALIAN)
                    }
                }
                R.id.radioPortuguese -> {
                    if (useOpenAI) {
                        openAIClient?.updateTargetLanguage("Portuguese (Brazil)", currentSilenceThreshold)
                    } else {
                        changeTargetLanguage(TranslateLanguage.PORTUGUESE, Locale("pt", "BR"))
                    }
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
            // Se sta scaricando, non fare nulla (il bottone dovrebbe essere disabilitato visivamente, ma per sicurezza)
            if (progressBar?.visibility == View.VISIBLE) {
                Toast.makeText(this, "Wait for download to finish...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Log.d("MainActivity", "DEBUG: User triggered PLAY (Listener OK)")
            Toast.makeText(this, "Play Clicked!", Toast.LENGTH_SHORT).show() 
            
            val activeKey = if (useOpenAI) openAIApiKey else currentApiKey
            if (activeKey.isEmpty()) {
                Log.e("MainActivity", "DEBUG: API Key is empty!")
                Toast.makeText(this, "Please configure API Key first", Toast.LENGTH_SHORT).show()
                startSettingsBlink()
            } else {
                Log.d("MainActivity", "DEBUG: Key found, starting flow")

                // Session TXT starts on first Play after app launch (and continues across Pause/threshold/background)
                transcriptSaver?.ensureSessionStarted()

                isStreamingActive = true
                if (useOpenAI) {
                    startOpenAIFlow()
                } else {
                    startTranslationFlow()
                }
            }
        }

        btnPause?.setOnClickListener {
            Log.d("MainActivity", "User triggered PAUSE/STOP")
            isStreamingActive = false

            if (useOpenAI) {
                openAIAudioPlayer?.interrupt()
                audioRecorderManager?.stop()
                audioRecorderManager = null
                openAIClient?.stop()
                AppLogger.log("OPENAI", "paused by user")
            } else {
                // Ferma tutto ma non chiude l'app
                ttsManager?.interrupt()
                audioRecorderManager?.stop()
                if (::assemblyAIClient.isInitialized) assemblyAIClient.stop()

                // Ferma il timer del silenzio
                silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
            }

            statusTextView?.text = "Paused. Press Play to resume."
            statusTextView?.setTextColor(android.graphics.Color.YELLOW)
        }

        btnStop?.setOnClickListener {
            Log.d("MainActivity", "User triggered STOP (End session & exit)")
            isStreamingActive = false
            AppLogger.log("APP", "stop pressed")
            endSessionAndExitApp()
        }

        btnReset?.setOnClickListener {
            Log.d("MainActivity", "User triggered RESET")
            isStreamingActive = false
            AppLogger.log("APP", "reset pressed")
            resetAppFlow()
        }
        
        btnReset?.setOnLongClickListener {
            Toast.makeText(this, "Hard Reset: Cleaning translation models...", Toast.LENGTH_LONG).show()
            translationManager?.deleteModelsAndReset()
            true
        }

        btnTranslateNow?.setOnClickListener {
            Log.d("MainActivity", "User triggered TRANSLATE NOW")
            if (useOpenAI) {
                Toast.makeText(this, "OpenAI mode: force cut not available", Toast.LENGTH_SHORT).show()
                AppLogger.log("OPENAI", "translate-now pressed (ignored in OpenAI mode)")
                return@setOnClickListener
            }
            statusTextView?.append("\n[FORCING CUT...]")
            
            // Forza il timer locale a scattare subito se c'è roba nel buffer
            silenceRunnable?.let {
                silenceHandler.removeCallbacks(it)
                it.run() // Esegue immediatamente la logica di traduzione
            }
            
            if (::assemblyAIClient.isInitialized) {
                assemblyAIClient.forceEndTurn()
            }
        }
    }

    private fun restartAfterThresholdChange(wasActive: Boolean) {
        runOnUiThread {
            if (useOpenAI) {
                openAIClient?.updateTargetLanguage(getOpenAITargetLanguage(), currentSilenceThreshold)
                if (wasActive) {
                    statusTextView?.text = "Updated OpenAI threshold."
                    statusTextView?.setTextColor(android.graphics.Color.YELLOW)
                } else {
                    statusTextView?.text = "Ready. Press Play to start."
                    statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
                }
                return@runOnUiThread
            }

            // Stop clean (senza chiudere sessione txt)
            try { ttsManager?.interrupt() } catch (_: Exception) {}
            try { audioRecorderManager?.stop() } catch (_: Exception) {}
            try { if (::assemblyAIClient.isInitialized) assemblyAIClient.stop() } catch (_: Exception) {}

            // Stop timer silenzio
            silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
            silenceRunnable = null
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
            if (useOpenAI) {
                openAIAudioPlayer?.interrupt()
                audioRecorderManager?.stop()
                audioRecorderManager = null
                openAIClient?.stop()

                statusTextView?.text = "Resetting..."

                statusTextView?.postDelayed({
                    statusTextView?.text = "Restarting..."
                    checkPermissionsAndStart()
                }, 500)
                return@runOnUiThread
            }

            ttsManager?.interrupt()
            audioRecorderManager?.stop()
            if (::assemblyAIClient.isInitialized) assemblyAIClient.stop()
            
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

        // 2) Ferma audio + websocket + TTS
        try { ttsManager?.interrupt() } catch (_: Exception) {}
        try { ttsManager?.stop() } catch (_: Exception) {}
        try { audioRecorderManager?.stop() } catch (_: Exception) {}
        try { if (::assemblyAIClient.isInitialized) assemblyAIClient.stop() } catch (_: Exception) {}
        try { openAIClient?.stop() } catch (_: Exception) {}
        try { openAIAudioPlayer?.stop() } catch (_: Exception) {}

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

    private fun setupAssemblyAI() {
        if (currentApiKey.isEmpty()) return

        assemblyAIClient = AssemblyAIClient(currentApiKey, object : AssemblyAIClient.TranscriptionListener {
            override fun onConnected() {
                runOnUiThread {
                    statusTextView?.append("\nConnected! (Silence: ${currentSilenceThreshold}ms)\nSpeak now...")
                }
            }

            override fun onTranscription(text: String, isFinal: Boolean) {
                // LOGICA CLIENT-SIDE SILENCE:
                // Ignoriamo parzialmente isFinal. Accumuliamo tutto e usiamo il nostro timer.
                
                runOnUiThread {
                    // Se arriva nuovo testo, resettiamo il timer
                    silenceRunnable?.let { silenceHandler.removeCallbacks(it) }
                    
                    // Se è un parziale, aggiorniamo solo la UI "En: ..."
                    // Se è Final (dal server), lo aggiungiamo al nostro buffer
                    if (isFinal) {
                        if (textBuffer.isNotEmpty()) textBuffer.append(" ")
                        textBuffer.append(text)
                        
                        // Aggiorniamo UI provvisoria con tutto il buffer
                        statusTextView?.text = "En: $textBuffer..."
                    } else {
                        // Per i parziali, mostriamo Buffer confermato + Parziale corrente
                        val currentView = if (textBuffer.isNotEmpty()) "$textBuffer $text" else text
                        statusTextView?.text = "En: $currentView..."
                    }

                    // Avviamo il timer di silenzio
                    silenceRunnable = Runnable {
                        // IL TIMER È SCATTATO!
                        // Significa che per X secondi non è arrivato NIENTE (né parziale né final)
                        
                        // Se abbiamo testo parziale non finalizzato, purtroppo AssemblyAI non ce lo dà "fermo".
                        // Ma se abbiamo testo nel buffer (da precedenti Final), lo mandiamo.
                        // O se l'ultimo evento era Final, il buffer è pronto.
                        
                        val textToTranslate = textBuffer.toString().trim()
                        if (textToTranslate.isNotEmpty()) {
                            processTranslation(textToTranslate)
                            textBuffer.clear()
                        }
                    }
                    
                    // Usiamo il valore della levetta come ritardo
                    silenceHandler.postDelayed(silenceRunnable!!, currentSilenceThreshold.toLong())
                }
            }

            override fun onError(error: String) {
                Log.e("MainActivity", "Error: $error")
                runOnUiThread {
                    statusTextView?.text = "Error: $error"
                }
            }
        })
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            checkKeyAndStart()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkKeyAndStart() {
        val activeKey = if (useOpenAI) openAIApiKey else currentApiKey
        if (activeKey.isEmpty()) {
            Toast.makeText(this, "Please set API Key first", Toast.LENGTH_SHORT).show()
            return
        }
        // Non avviamo più automaticamente. Aspettiamo il Play.
        statusTextView?.text = if (useOpenAI) {
            "Ready. Press Play to start (OpenAI)."
        } else {
            "Ready. Press Play to start."
        }
    }

    private fun getOpenAITargetLanguage(): String {
        return if (radioPortuguese?.isChecked == true) {
            "Portuguese (Brazil)"
        } else {
            "Italian"
        }
    }

    private fun updateOpenAIStatus() {
        runOnUiThread {
            val input = openAIInputText.trim()
            val output = openAIOutputText.trim()
            if (input.isEmpty() && output.isEmpty()) return@runOnUiThread

            val sb = StringBuilder()
            if (input.isNotEmpty()) {
                sb.append("En: ").append(input)
            }
            if (output.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("Target: ").append(output)
            }
            statusTextView?.text = sb.toString()
            statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
        }
    }

    private fun startOpenAIFlow() {
        Log.d("MainActivity", "DEBUG: Inside startOpenAIFlow")
        try {
            statusTextView?.text = "Connecting to OpenAI..."
            statusTextView?.setTextColor(android.graphics.Color.YELLOW)
            progressBar?.visibility = View.INVISIBLE

            openAIClient?.stop()
            audioRecorderManager?.stop()
            audioRecorderManager = null
            openAIAudioPlayer?.stop()
            if (::assemblyAIClient.isInitialized) assemblyAIClient.stop()
            ttsManager?.interrupt()

            openAIAudioPlayer = OpenAIAudioPlayer()

            val targetLanguage = getOpenAITargetLanguage()
            AppLogger.log("OPENAI", "startOpenAIFlow target=$targetLanguage silenceMs=$currentSilenceThreshold")
            openAIClient = OpenAIRealtimeClient(openAIApiKey, object : OpenAIRealtimeClient.Listener {
                override fun onConnected() {
                    runOnUiThread {
                        statusTextView?.text = "Connected (OpenAI). Speak now..."
                        statusTextView?.setTextColor(android.graphics.Color.LTGRAY)
                    }
                    AppLogger.log("OPENAI", "socket connected, waiting session ready")
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        statusTextView?.text = "OpenAI disconnected."
                        statusTextView?.setTextColor(android.graphics.Color.RED)
                    }
                    AppLogger.log("OPENAI", "socket disconnected")
                }

                override fun onInputTranscript(text: String, isFinal: Boolean) {
                    openAIInputText = text
                    if (isFinal) {
                        openAILastInputFinal = text
                    }
                    AppLogger.log("OPENAI", "input transcript (final=$isFinal): ${text.take(200)}")
                    updateOpenAIStatus()
                }

                override fun onOutputTranscript(text: String, isFinal: Boolean) {
                    openAIOutputText = text
                    AppLogger.log("OPENAI", "output transcript (final=$isFinal): ${text.take(200)}")
                    updateOpenAIStatus()
                    if (isFinal && openAILastInputFinal.isNotEmpty()) {
                        val logLine = "[EN]: $openAILastInputFinal\n[TR]: $openAIOutputText\n"
                        transcriptSaver?.append(logLine)
                    }
                }

                override fun onAudioDelta(audio: ByteArray) {
                    openAIAudioPlayer?.playAudio(audio)
                }

                override fun onSpeechStarted() {
                    openAIAudioPlayer?.interrupt()
                    openAIInputText = ""
                    openAIOutputText = ""
                    openAILastInputFinal = ""
                    AppLogger.log("OPENAI", "speech started")
                    updateOpenAIStatus()
                }

                override fun onSessionReady() {
                    if (audioRecorderManager != null) return
                    AppLogger.log("OPENAI", "session ready, starting recorder")
                    var chunkCounter = 0
                    // Try native 24k first; fallback to 16k + resample if needed
                    val recorder24k = AudioRecorderManager({ audioData ->
                        chunkCounter++
                        if (chunkCounter % 50 == 0) {
                            AppLogger.log("AUDIO", "sending 24k chunk bytes=${audioData.size} count=$chunkCounter")
                        }
                        openAIClient?.sendAudio(audioData)
                    }, 24000)

                    if (recorder24k.start()) {
                        AppLogger.log("AUDIO", "using 24k recorder")
                        audioRecorderManager = recorder24k
                        isStreamingActive = true
                        return
                    }

                    AppLogger.log("AUDIO", "24k recorder failed, falling back to 16k + resample")
                    val recorder16k = AudioRecorderManager({ audioData ->
                        val resampled = PcmResampler.upsample16kTo24k(audioData)
                        chunkCounter++
                        if (chunkCounter % 50 == 0) {
                            AppLogger.log("AUDIO", "sending 16k->24k chunk bytes=${resampled.size} count=$chunkCounter")
                        }
                        openAIClient?.sendAudio(resampled)
                    }, 16000)

                    if (recorder16k.start()) {
                        audioRecorderManager = recorder16k
                        isStreamingActive = true
                    } else {
                        AppLogger.log("AUDIO", "16k recorder failed")
                        runOnUiThread {
                            statusTextView?.text = "Microphone start failed"
                            statusTextView?.setTextColor(android.graphics.Color.RED)
                        }
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        statusTextView?.text = "OpenAI Error: $message"
                        statusTextView?.setTextColor(android.graphics.Color.RED)
                    }
                    AppLogger.log("OPENAI", "error: $message")
                }

                override fun onStatus(message: String) {
                    Log.d("MainActivity", "OpenAI: $message")
                    AppLogger.log("OPENAI", "status: $message")
                }
            })

            openAIClient?.connect(targetLanguage, currentSilenceThreshold)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting OpenAI flow", e)
            statusTextView?.text = "Error starting OpenAI: ${e.message}"
            statusTextView?.setTextColor(android.graphics.Color.RED)
            isStreamingActive = false
        }
    }

    private fun startTranslationFlow() {
        Log.d("MainActivity", "DEBUG: Inside startTranslationFlow")
        try {
            statusTextView?.text = "Connecting..."
            statusTextView?.setTextColor(android.graphics.Color.YELLOW)

            openAIClient?.stop()
            openAIAudioPlayer?.stop()

            // Cleanup preventivo
            if (::assemblyAIClient.isInitialized) {
                Log.d("MainActivity", "DEBUG: Stopping previous client")
                try { assemblyAIClient.stop() } catch (e: Exception) { Log.e("MainActivity", "Stop error", e) }
            }
            audioRecorderManager?.stop()

            Log.d("MainActivity", "DEBUG: Calling setupAssemblyAI")
            setupAssemblyAI()
            
            if (!::assemblyAIClient.isInitialized) {
                Log.e("MainActivity", "DEBUG: Client FAILED to initialize")
                statusTextView?.text = "Error: Client not initialized (Check API Key)"
                return
            }
            
            // #region agent log
            logDebug("Starting translation flow with threshold", "$currentSilenceThreshold")
            // #endregion
            Log.d("MainActivity", "DEBUG: Starting client with threshold $currentSilenceThreshold")
            assemblyAIClient.start(currentSilenceThreshold)

            audioRecorderManager = AudioRecorderManager({ audioData ->
                assemblyAIClient.sendAudio(audioData)
            })
            
            Log.d("MainActivity", "DEBUG: Starting recorder")
            audioRecorderManager?.start()
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
        audioRecorderManager?.stop()
        if (::assemblyAIClient.isInitialized) assemblyAIClient.stop()
        openAIClient?.stop()
        openAIAudioPlayer?.stop()
        translationManager?.close()
        ttsManager?.stop()
        try { transcriptSaver?.endSession("Session ended (onDestroy)") } catch (_: Exception) {}
    }
}
