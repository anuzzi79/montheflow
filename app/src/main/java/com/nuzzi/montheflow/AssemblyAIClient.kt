package com.nuzzi.montheflow

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okio.ByteString.Companion.toByteString

class AssemblyAIClient(
    private val apiKey: String,
    private val listener: TranscriptionListener
) : WebSocketListener() {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    interface TranscriptionListener {
        fun onTranscription(text: String, isFinal: Boolean)
        fun onError(error: String)
        fun onConnected()
    }

    // Helper per debug
    private fun logDebug(hypothesisId: String, msg: String, data: String = "{}") {
        val timestamp = System.currentTimeMillis()
        val safeMsg = msg.replace("\"", "'")
        val json = "{\"id\":\"log_${timestamp}\",\"timestamp\":${timestamp},\"location\":\"AssemblyAIClient.kt\",\"message\":\"${safeMsg}\",\"data\":${data},\"sessionId\":\"debug-session\",\"hypothesisId\":\"${hypothesisId}\"}"
        Log.e("CURSOR_DEBUG", json)
    }

    fun start() {
        // AGGIORNAMENTO ALLA V3 (Documentazione Universal Streaming)
        // Aggiungiamo format_turns=true per avere punteggiatura (fondamentale per la traduzione)
        // Impostiamo end_utterance_silence_threshold=500 per un bilanciamento ottimale tra reattività e contesto
        val url = "wss://streaming.assemblyai.com/v3/ws?sample_rate=16000&encoding=pcm_s16le&format_turns=true&end_utterance_silence_threshold=500"
        
        // #region agent log
        logDebug("V3", "Connecting with Optimized Params (500ms)", "{\"url\": \"$url\"}")
        // #endregion

        Log.d(TAG, "Connecting to V3 URL: $url")

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
                        // LOGICA ANTI-DUPLICAZIONE:
                        // Se format_turns=true, riceviamo prima un evento 'end_of_turn=true' (grezzo)
                        // e subito dopo uno 'turn_is_formatted=true' (con punteggiatura).
                        // Per evitare che il TTS parli due volte, consideriamo "Finale" (e quindi da tradurre/parlare)
                        // SOLO quello formattato.
                        
                        var isFinal = response.end_of_turn == true
                        
                        // Se è marcato come fine turno, ma NON è ancora formattato, aspettiamo il prossimo messaggio.
                        if (isFinal && response.turn_is_formatted == false) {
                            isFinal = false 
                        }

                        listener.onTranscription(response.transcript, isFinal)
                    }
                }
                "Begin" -> {
                    Log.d(TAG, "Session began V3: ${response.id}")
                }
                "Error" -> {
                    Log.e(TAG, "Server Error V3: $text")
                    // #region agent log
                    logDebug("V3", "Received Error Message", "{\"payload\": \"${text.replace("\"", "'")}\"}")
                    // #endregion
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
        // #region agent log
        logDebug("V3", "WebSocket Failure", "{\"error\": \"${t.message}\", \"response_code\": ${response?.code}}")
        // #endregion
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
