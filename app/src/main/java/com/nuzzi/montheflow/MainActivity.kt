package com.nuzzi.montheflow

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
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

class MainActivity : AppCompatActivity() {

    private lateinit var assemblyAIClient: AssemblyAIClient
    private var audioRecorderManager: AudioRecorderManager? = null
    private var translationManager: TranslationManager? = null
    private var ttsManager: TTSManager? = null
    private var transcriptSaver: TranscriptSaver? = null // Saver
    
    // UI elements
    private var statusTextView: TextView? = null
    private var languageRadioGroup: RadioGroup? = null
    
    private var btnTranslateNow: ImageButton? = null
    private var btnReset: ImageButton? = null
    private var btnStopExit: ImageButton? = null
    private var btnSettings: ImageButton? = null
    
    // Flags UI for visual effect
    private var radioItalian: RadioButton? = null
    private var radioPortuguese: RadioButton? = null

    // API Key is now retrieved dynamically
    private var currentApiKey: String = ""

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
        
        // Gestore crash per debug
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CRASH", "Uncaught exception", throwable)
            runOnUiThread {
                try {
                    statusTextView?.text = "CRASH: ${throwable.message}\n${throwable.stackTraceToString()}"
                    statusTextView?.setTextColor(android.graphics.Color.RED)
                } catch (e: Exception) {
                    // Ignora errori UI durante il crash
                }
            }
        }
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        // Binding UI
        statusTextView = findViewById(R.id.statusText)
        languageRadioGroup = findViewById(R.id.languageRadioGroup)
        
        btnTranslateNow = findViewById(R.id.btnTranslateNow)
        btnReset = findViewById(R.id.btnReset)
        btnStopExit = findViewById(R.id.btnStopExit)
        btnSettings = findViewById(R.id.btnSettings)
        
        radioItalian = findViewById(R.id.radioItalian)
        radioPortuguese = findViewById(R.id.radioPortuguese)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Inizializza Saver
        transcriptSaver = TranscriptSaver(this)

        // Inizializza TTS
        ttsManager = TTSManager(this)

        // Inizializza Traduzione
        translationManager = TranslationManager {
            Log.d("MainActivity", "Translation model ready")
            runOnUiThread {
                if (statusTextView?.text?.contains("Translation Model") != true) {
                   statusTextView?.append("\nTranslation Model Ready")
                }
            }
        }

        setupLanguageControls()
        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        validateApiKeyAndInit()
    }

    private fun validateApiKeyAndInit() {
        val prefs = getSharedPreferences("MontheflowPrefs", Context.MODE_PRIVATE)
        val storedKey = prefs.getString("ASSEMBLYAI_KEY", "")

        if (storedKey.isNullOrEmpty()) {
            statusTextView?.text = "API Key Missing!\nPlease configure settings."
            startSettingsBlink()
        } else {
            stopSettingsBlink()
            currentApiKey = storedKey
            checkPermissionsAndStart()
        }
    }

    // Animazione lampeggiante per Settings
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
                    changeTargetLanguage(TranslateLanguage.ITALIAN, Locale.ITALIAN)
                }
                R.id.radioPortuguese -> {
                    changeTargetLanguage(TranslateLanguage.PORTUGUESE, Locale("pt", "BR"))
                }
            }
        }
    }

    private fun setupButtons() {
        // Settings Button
        btnSettings?.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        // 1. STOP / EXIT (Rosso)
        btnStopExit?.setOnClickListener {
            Log.d("MainActivity", "User triggered STOP/EXIT")
            ttsManager?.stop()
            audioRecorderManager?.stop()
            if (::assemblyAIClient.isInitialized) assemblyAIClient.stop()
            
            finishAffinity() 
            exitProcess(0)
        }

        // 2. RESET (Arancione)
        btnReset?.setOnClickListener {
            Log.d("MainActivity", "User triggered RESET")
            resetAppFlow()
        }

        // 3. TRANSLATE NOW (Verde)
        btnTranslateNow?.setOnClickListener {
            Log.d("MainActivity", "User triggered TRANSLATE NOW")
            statusTextView?.append("\n[FORCING CUT...]")
            if (::assemblyAIClient.isInitialized) {
                assemblyAIClient.forceEndTurn()
            }
        }
    }

    private fun resetAppFlow() {
        runOnUiThread {
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

    private fun changeTargetLanguage(mlKitLangCode: String, ttsLocale: Locale) {
        statusTextView?.append("\nSwitching to ${ttsLocale.displayLanguage}...")
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
                    statusTextView?.append("\nConnected! Speak now...")
                }
                // Avvia nuovo file per la sessione
                transcriptSaver?.startNewSession()
            }

            override fun onTranscription(text: String, isFinal: Boolean) {
                Log.d("MainActivity", "Transcribed: $text [Final: $isFinal]")
                
                if (isFinal) {
                    translationManager?.translate(text) { translatedText ->
                        Log.d("MainActivity", "Translated: $translatedText")
                        runOnUiThread {
                            statusTextView?.text = "En: $text\nTarget: $translatedText"
                        }
                        
                        // Salva nel file (append)
                        val logLine = "[EN]: $text\n[TR]: $translatedText\n"
                        transcriptSaver?.append(logLine)
                        
                        ttsManager?.speak(translatedText)
                    }
                } else {
                     runOnUiThread {
                        statusTextView?.text = "En: $text..."
                    }
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

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            checkKeyAndStart()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkKeyAndStart() {
        if (currentApiKey.isEmpty()) {
            Toast.makeText(this, "Please set API Key first", Toast.LENGTH_SHORT).show()
            return
        }
        startTranslationFlow()
    }

    private fun startTranslationFlow() {
        try {
            setupAssemblyAI()
            
            statusTextView?.append("\nStarting flow...")
            assemblyAIClient.start()

            audioRecorderManager = AudioRecorderManager { audioData ->
                assemblyAIClient.sendAudio(audioData)
            }
            
            audioRecorderManager?.start()
            statusTextView?.append("\nMic started.")
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting translation flow", e)
            statusTextView?.text = "Error starting: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorderManager?.stop()
        if (::assemblyAIClient.isInitialized) assemblyAIClient.stop()
        translationManager?.close()
        ttsManager?.stop()
    }
}
