package com.nuzzi.montheflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class AndroidSpeechClient(
    private val context: Context,
    private val listener: TranscriptionListener
) : RecognitionListener {

    interface TranscriptionListener {
        fun onTranscription(text: String, isFinal: Boolean)
        fun onError(errorCode: Int, error: String)
        fun onConnected()
        fun onSpeechStart()
        fun onSpeechEnd()
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var recognitionServiceComponent: ComponentName? = null

    private fun ensureRecognizer() {
        if (speechRecognizer == null) {
            val component = resolveRecognitionService()
            speechRecognizer = if (component != null) {
                try {
                    Log.i(TAG, "Using RecognitionService: ${component.flattenToShortString()}")
                    SpeechRecognizer.createSpeechRecognizer(context, component)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create recognizer with $component, falling back", e)
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            speechRecognizer?.setRecognitionListener(this)
        }
    }

    private fun resolveRecognitionService(): ComponentName? {
        if (recognitionServiceComponent != null) return recognitionServiceComponent

        val pm = context.packageManager
        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.MATCH_ALL
        )

        if (services.isNullOrEmpty()) {
            Log.w(TAG, "No RecognitionService found by PackageManager")
            return null
        }

        val serviceInfos = services
            .mapNotNull { it.serviceInfo }
            .filter { it.enabled && (it.applicationInfo?.enabled != false) }
            .filter { it.packageName != GOOGLE_TTS_PACKAGE }

        if (serviceInfos.isEmpty()) {
            Log.w(TAG, "Only TTS RecognitionService found; falling back to default")
            return null
        }

        val exactGoogle = serviceInfos.firstOrNull {
            it.packageName == GOOGLE_QSB_PACKAGE && it.name == GOOGLE_RECOGNITION_SERVICE
        }
        val googleQsb = serviceInfos.firstOrNull { it.packageName == GOOGLE_QSB_PACKAGE }
        val googleAs = serviceInfos.firstOrNull { it.packageName == GOOGLE_AS_PACKAGE }
        val selected = exactGoogle ?: googleQsb ?: googleAs ?: serviceInfos.first()

        recognitionServiceComponent = ComponentName(selected.packageName, selected.name)
        return recognitionServiceComponent
    }

    fun start() {
        if (isListening) {
            return
        }
        ensureRecognizer()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            listener.onConnected()
        } catch (e: Exception) {
            isListening = false
            listener.onError(SpeechRecognizer.ERROR_CLIENT, "SpeechRecognizer start failed: ${e.message}")
        }
    }

    fun stop() {
        isListening = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SpeechRecognizer", e)
        }
    }

    fun forceEndTurn() {
        try {
            if (isListening) {
                speechRecognizer?.stopListening()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error forcing end turn", e)
        }
    }

    fun destroy() {
        isListening = false
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying SpeechRecognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        Log.d(TAG, "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "Beginning of speech")
        listener.onSpeechStart()
    }

    override fun onRmsChanged(rmsdB: Float) {
        // No-op
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        // No-op
    }

    override fun onEndOfSpeech() {
        Log.d(TAG, "End of speech")
        isListening = false
        listener.onSpeechEnd()
    }

    override fun onError(error: Int) {
        isListening = false
        listener.onError(error, mapError(error))
    }

    override fun onResults(results: Bundle?) {
        isListening = false
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            listener.onTranscription(text, true)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isNotEmpty()) {
            listener.onTranscription(text, false)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {
        // No-op
    }

    private fun mapError(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission error"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language unavailable"
            else -> "Unknown error ($error)"
        }
    }

    companion object {
        private const val TAG = "AndroidSpeechClient"
        private const val GOOGLE_QSB_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val GOOGLE_RECOGNITION_SERVICE =
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        private const val GOOGLE_AS_PACKAGE = "com.google.android.as"
        private const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }
}
