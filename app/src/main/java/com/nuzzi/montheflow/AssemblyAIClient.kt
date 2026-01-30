package com.nuzzi.montheflow

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.ByteString.Companion.toByteString
import java.io.File
import java.util.Date

class AssemblyAIClient(
    private val apiKey: String,
    private val listener: TranscriptionListener
) : WebSocketListener() {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private var audioChunkCounter = 0

    interface TranscriptionListener {
        fun onTranscription(text: String, isFinal: Boolean)
        fun onError(error: String)
        fun onConnected()
    }

    private fun logDebug(message: String, data: String = "{}") {
        try {
             // Optional: restore file logging or just remove
        } catch (e: Exception) {}
    }

    fun start(silenceThreshold: Int = 500) {
        // RIMOSSO format_turns=true per testare se interferisce con il silenzio
        // Abilitiamo il modello Multilingual con Language Detection automatico
        // Supporta automaticamente: Inglese, Italiano, Portoghese, Spagnolo, Francese, Tedesco.
        val url = "wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&end_utterance_silence_threshold=$silenceThreshold&speech_model=universal-streaming-multilingual&language_detection=true"
        
        Log.d(TAG, "Connecting to V3 URL (Multilingual): ${url.replace(apiKey, "HIDDEN_KEY")}") // Log di verifica URL
        Log.d(TAG, "Silence Threshold applied: $silenceThreshold ms")
        
        // #region agent log
        logDebug("Starting AssemblyAIClient", "{\"url\":\"${url.replace(apiKey, "HIDDEN")}\", \"silenceThreshold\":$silenceThreshold}")
        // #endregion

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", apiKey)
            .build()

        webSocket = client.newWebSocket(request, this)
    }

    fun sendAudio(data: ByteArray) {
        // Invia dati audio binari (PCM16)
        webSocket?.send(data.toByteString())
    }

    fun forceEndTurn() {
        // Comando per forzare la chiusura della frase corrente (Cut manuale)
        // In V3 il comando corretto è "ForceEndpoint" (non ForceEndUtterance)
        // Ma con la logica client-side, dobbiamo anche forzare il timer locale!
        // Tuttavia, AssemblyAI non ha un modo per dirgli "dammi quello che hai in buffer ora anche se parziale".
        // ForceEndpoint chiude il turno sul server -> Arriva un Final -> Il nostro codice lo accoda -> Timer parte.
        // Quindi dobbiamo anche dire al client: "Se premi il bottone, ignora il timer e vai subito".
        val forceMsg = "{\"type\": \"ForceEndpoint\"}"
        Log.d(TAG, "Sending ForceEndpoint")
        webSocket?.send(forceMsg)
    }

    fun stop() {
        webSocket?.close(1000, "User stopped")
        webSocket = null
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d(TAG, "Connected to AssemblyAI V3")
        listener.onConnected()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val response = gson.fromJson(text, AssemblyResponseV3::class.java)
            
            // In V3 il campo 'type' indica il tipo di messaggio
            when (response.type) {
                "Turn" -> {
                    // 'transcript' contiene il testo, 'end_of_turn' indica se è finale
                    if (!response.transcript.isNullOrEmpty()) {
                        // #region agent log
                        logDebug("Turn received", "{\"transcript\":\"${response.transcript}\", \"end_of_turn\":${response.end_of_turn}, \"turn_is_formatted\":${response.turn_is_formatted}}")
                        // #endregion

                        // LOGICA ANTI-DUPLICAZIONE RIMOSSA (senza format_turns non serve)
                        // Se format_turns=true, riceviamo prima un evento 'end_of_turn=true' (grezzo)
                        // e subito dopo uno 'turn_is_formatted=true' (con punteggiatura).
                        
                        val isFinal = response.end_of_turn == true
                        
                        // Senza format_turns, ci fidiamo solo di end_of_turn
                        listener.onTranscription(response.transcript, isFinal)
                    }
                }
                "Begin" -> {
                    Log.d(TAG, "Session began V3: ${response.id}")
                }
                "Error" -> {
                    Log.e(TAG, "Server Error V3: $text")
                    listener.onError("Server Error: $text")
                }
                "Termination" -> {
                    Log.d(TAG, "Session terminated V3")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message V3: $text", e)
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(TAG, "WebSocket Failure V3", t)
        listener.onError(t.message ?: "Unknown error")
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket?.close(1000, null)
        Log.d(TAG, "WebSocket Closing V3: $reason")
    }

    companion object {
        private const val TAG = "AssemblyAIClient"
    }

    // Nuova struttura JSON per V3
    private data class AssemblyResponseV3(
        val type: String,
        val transcript: String?,
        val end_of_turn: Boolean?,
        val turn_is_formatted: Boolean?,
        val id: String?
    )
}
