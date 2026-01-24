package com.nuzzi.montheflow

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationManager(
    private val onModelReady: () -> Unit
) {

    private var translator: Translator? = null
    private var isReady = false
    private var currentTargetLanguage: String = TranslateLanguage.ITALIAN

    init {
        // Inizializza con Italiano di default
        initializeTranslator(TranslateLanguage.ITALIAN)
    }

    fun setTargetLanguage(languageCode: String) {
        if (currentTargetLanguage == languageCode && isReady) return

        Log.d(TAG, "Switching language to: $languageCode")
        currentTargetLanguage = languageCode
        isReady = false
        
        translator?.close()
        initializeTranslator(languageCode)
    }

    private fun initializeTranslator(targetLang: String) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLang)
            .build()

        translator = Translation.getClient(options)

        val conditions = DownloadConditions.Builder()
            .requireWifi()
            .build()

        translator?.downloadModelIfNeeded(conditions)
            ?.addOnSuccessListener {
                Log.d(TAG, "Translation model ($targetLang) downloaded/ready")
                isReady = true
                onModelReady()
            }
            ?.addOnFailureListener { exception ->
                Log.e(TAG, "Model download failed", exception)
            }
    }

    fun translate(text: String, callback: (String) -> Unit) {
        if (!isReady || translator == null) {
            Log.w(TAG, "Translator not ready yet for $currentTargetLanguage")
            return
        }

        translator?.translate(text)
            ?.addOnSuccessListener { translatedText ->
                callback(translatedText)
            }
            ?.addOnFailureListener { exception ->
                Log.e(TAG, "Translation error", exception)
            }
    }

    fun close() {
        translator?.close()
    }

    companion object {
        private const val TAG = "TranslationManager"
    }
}
