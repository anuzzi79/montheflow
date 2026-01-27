package com.nuzzi.montheflow

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class TranslationManager(
    private val onStatusChange: (String, Boolean) -> Unit
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
    
    fun deleteModelsAndReset() {
        val modelManager = RemoteModelManager.getInstance()
        
        val itModel = TranslateRemoteModel.Builder(TranslateLanguage.ITALIAN).build()
        val ptModel = TranslateRemoteModel.Builder(TranslateLanguage.PORTUGUESE).build()
        
        onStatusChange("Deleting corrupt models...", false)
        
        modelManager.deleteDownloadedModel(itModel)
        modelManager.deleteDownloadedModel(ptModel).addOnCompleteListener {
            onStatusChange("Models deleted. Retrying download...", false)
            // Riprova a scaricare la lingua corrente
            initializeTranslator(currentTargetLanguage)
        }
    }

    private var retryCount = 0
    private val MAX_RETRIES = 3
    private var downloadTask: com.google.android.gms.tasks.Task<Void>? = null

    private var currentDownloadLang: String? = null

    private fun initializeTranslator(targetLang: String) {
        if (currentDownloadLang == targetLang && !isReady) {
            Log.d(TAG, "Download for $targetLang already in progress. Ignoring duplicate request.")
            return
        }
        currentDownloadLang = targetLang
        
        onStatusChange("Checking/Downloading model for $targetLang... (Attempt ${retryCount + 1})", false)

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLang)
            .build()
        
        translator = Translation.getClient(options)
        
        val conditions = DownloadConditions.Builder().build() // Default

        // WATCHDOG TIMER: 45 secondi
        val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val watchdogRunnable = Runnable {
            if (currentDownloadLang == targetLang && !isReady) {
                Log.w(TAG, "Download timed out (Watchdog)")
                handleDownloadFailure(Exception("Download Timed Out"), targetLang)
            }
        }
        watchdogHandler.postDelayed(watchdogRunnable, 45000)

        // USO API STANDARD
        downloadTask = translator!!.downloadModelIfNeeded(conditions)
        
        downloadTask?.addOnSuccessListener {
                if (currentDownloadLang == targetLang) {
                    watchdogHandler.removeCallbacks(watchdogRunnable) // Ferma il timer adesso
                    retryCount = 0 
                    isReady = true
                    currentDownloadLang = null // Reset
                    onStatusChange("Translation Model ($targetLang) Ready", false)
                }
            }
            ?.addOnFailureListener { exception ->
                if (currentDownloadLang == targetLang) {
                    watchdogHandler.removeCallbacks(watchdogRunnable) // Ferma il timer
                    handleDownloadFailure(exception, targetLang)
                }
            }
    }

    private fun handleDownloadFailure(exception: Exception, targetLang: String) {
        Log.e(TAG, "Manager download failed", exception)
        
        currentDownloadLang = null // Reset per permettere retry

        if (retryCount < MAX_RETRIES) {
            retryCount++
            val delay = 2000L * retryCount
            onStatusChange("Download failed/stuck. Retrying in ${delay/1000}s...", false)
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                initializeTranslator(targetLang)
            }, delay)
        } else {
            onStatusChange("Download failed permanently: ${exception.message}. Check Internet/Play Services.", true)
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
