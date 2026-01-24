package com.nuzzi.montheflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
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

class MainActivity : AppCompatActivity() {

    private lateinit var assemblyAIClient: AssemblyAIClient
    private var audioRecorderManager: AudioRecorderManager? = null
    private var translationManager: TranslationManager? = null
    private var ttsManager: TTSManager? = null
    
    // UI elements
    private var statusTextView: TextView? = null
    private var languageRadioGroup: RadioGroup? = null
    private var btnForceCut: Button? = null

    // TODO: INSERISCI QUI LA TUA API KEY DI ASSEMBLYAI
    private val API_KEY = "1c88814eb62b47adac31514e19b0ae66"

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startTranslationFlow()
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
        
        statusTextView = findViewById(R.id.statusText)
        languageRadioGroup = findViewById(R.id.languageRadioGroup)
        btnForceCut = findViewById(R.id.btnForceCut)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Inizializza TTS
        ttsManager = TTSManager(this)

        // Inizializza Traduzione
        translationManager = TranslationManager {
            Log.d("MainActivity", "Translation model ready")
            runOnUiThread {
                statusTextView?.append("\nTranslation Model Ready")
            }
        }

        setupLanguageControls()
        setupForceCutButton()
        setupAssemblyAI()
        checkPermissionsAndStart()
    }

    private fun setupLanguageControls() {
        languageRadioGroup?.setOnCheckedChangeListener { _, checkedId ->
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

    private fun setupForceCutButton() {
        btnForceCut?.setOnClickListener {
            Log.d("MainActivity", "User triggered Force Cut")
            statusTextView?.append("\n[FORCING CUT...]")
            assemblyAIClient.forceEndTurn()
        }
    }

    private fun changeTargetLanguage(mlKitLangCode: String, ttsLocale: Locale) {
        statusTextView?.append("\nSwitching to ${ttsLocale.displayLanguage}...")
        
        // Aggiorna Traduttore
        translationManager?.setTargetLanguage(mlKitLangCode)
        
        // Aggiorna TTS e gestisci errore dati mancanti
        val isTTSReady = ttsManager?.setLanguage(ttsLocale) ?: false
        
        if (!isTTSReady) {
            val msg = "Voice data for ${ttsLocale.displayLanguage} missing!"
            statusTextView?.append("\nWarning: $msg")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            
            // Suggerisci all'utente di installare i dati
            openTTSCheck()
        }
    }

    private fun openTTSCheck() {
        try {
            val intent = android.content.Intent()
            intent.action = android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to open TTS settings", e)
        }
    }

    private fun setupAssemblyAI() {
        assemblyAIClient = AssemblyAIClient(API_KEY, object : AssemblyAIClient.TranscriptionListener {
            override fun onConnected() {
                runOnUiThread {
                    statusTextView?.append("\nConnected! Speak now...")
                }
            }

            override fun onTranscription(text: String, isFinal: Boolean) {
                Log.d("MainActivity", "Transcribed: $text [Final: $isFinal]")
                
                if (isFinal) {
                    translationManager?.translate(text) { translatedText ->
                        Log.d("MainActivity", "Translated: $translatedText")
                        runOnUiThread {
                            statusTextView?.text = "En: $text\nTarget: $translatedText"
                        }
                        // Parla solo il testo finale tradotto
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
            startTranslationFlow()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startTranslationFlow() {
        try {
            statusTextView?.append("\nStarting flow...")
            
            // Avvia connessione WebSocket
            assemblyAIClient.start()

            // Inizializza Recorder
            audioRecorderManager = AudioRecorderManager { audioData ->
                assemblyAIClient.sendAudio(audioData)
            }
            
            // Avvia registrazione
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
        assemblyAIClient.stop()
        translationManager?.close()
        ttsManager?.stop()
    }
}
