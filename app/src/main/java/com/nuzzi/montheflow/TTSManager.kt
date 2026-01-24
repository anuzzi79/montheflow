package com.nuzzi.montheflow

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentLocale: Locale = Locale.ITALIAN

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.setSpeechRate(1.4f) // Imposta velocità a 1.4x
            setLanguage(currentLocale)
            Log.d(TAG, "TTS Initialized with rate 1.4x")
        } else {
            Log.e(TAG, "TTS Initialization failed")
        }
    }

    fun setLanguage(locale: Locale): Boolean {
        currentLocale = locale
        if (!isInitialized) return false

        val result = tts?.setLanguage(locale)
        
        return when (result) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                Log.e(TAG, "Language $locale not supported or missing data")
                false
            }
            else -> {
                Log.d(TAG, "TTS language set to: $locale")
                true
            }
        }
    }

    fun speak(text: String) {
        if (!isInitialized) return
        
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    fun stop() {
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        private const val TAG = "TTSManager"
    }
}
