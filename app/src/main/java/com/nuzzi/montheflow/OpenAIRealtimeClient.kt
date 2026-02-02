package com.nuzzi.montheflow

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class OpenAIRealtimeClient(
    private val apiKey: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onInputTranscript(text: String, isFinal: Boolean)
        fun onOutputTranscript(text: String, isFinal: Boolean)
        fun onAudioDelta(audio: ByteArray)
        fun onSpeechStarted()
        fun onSessionReady()
        fun onError(message: String)
        fun onStatus(message: String)
    }

    private val okHttpClient = OkHttpClient()
    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()

    private var currentTargetLanguage = "Italian"
    private var currentSilenceMs = 500

    private val model = "gpt-realtime-mini"
    private val voice = "marin"
    private val transcriptionModel = "gpt-4o-mini-transcribe"
    private val inputSampleRate = 24000
    private val outputSampleRate = 24000
    private var audioChunkCounter = 0

    fun connect(targetLanguage: String, silenceMs: Int) {
        currentTargetLanguage = targetLanguage
        currentSilenceMs = silenceMs
        AppLogger.log("OPENAI", "connect model=$model voice=$voice target=$targetLanguage silenceMs=$silenceMs")

        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$model")
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        webSocket = okHttpClient.newWebSocket(request, socketListener)
    }

    fun sendAudio(chunk: ByteArray) {
        if (!isConnected) {
            if (audioChunkCounter % 50 == 0) {
                AppLogger.log("OPENAI", "sendAudio dropped (not connected)")
            }
            audioChunkCounter++
            return
        }
        audioChunkCounter++
        if (audioChunkCounter % 50 == 0) {
            AppLogger.log("OPENAI", "sendAudio bytes=${chunk.size} count=$audioChunkCounter")
        }
        val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val event = JsonObject().apply {
            addProperty("type", "input_audio_buffer.append")
            addProperty("audio", base64)
        }
        webSocket?.send(gson.toJson(event))
    }

    fun updateTargetLanguage(targetLanguage: String, silenceMs: Int) {
        currentTargetLanguage = targetLanguage
        currentSilenceMs = silenceMs
        if (!isConnected) return
        AppLogger.log("OPENAI", "updateTargetLanguage target=$targetLanguage silenceMs=$silenceMs")

        val session = JsonObject().apply {
            addProperty("instructions", buildInstructions(targetLanguage))
            add("audio", JsonObject().apply {
                add("input", JsonObject().apply {
                    add("turn_detection", buildTurnDetection(silenceMs))
                })
            })
        }

        val event = JsonObject().apply {
            addProperty("type", "session.update")
            add("session", session)
        }

        webSocket?.send(gson.toJson(event))
    }

    fun stop() {
        try {
            webSocket?.close(1000, "Client closed")
        } catch (_: Exception) {
        }
        webSocket = null
        isConnected = false
        AppLogger.log("OPENAI", "socket closed")
    }

    private fun buildInstructions(targetLanguage: String): String {
        return "You are a real-time speech translator. Translate EVERYTHING the user says into $targetLanguage. " +
            "Output ONLY the translated text in $targetLanguage. " +
            "Never ask questions, never add explanations, and never add greetings. " +
            "If the input is already in $targetLanguage, repeat it unchanged. " +
            "If you hear silence or non-speech, output nothing."
    }

    private fun buildTurnDetection(silenceMs: Int): JsonObject {
        return JsonObject().apply {
            addProperty("type", "server_vad")
            addProperty("create_response", true)
            addProperty("interrupt_response", true)
            addProperty("silence_duration_ms", silenceMs)
            addProperty("threshold", 0.01)
            addProperty("prefix_padding_ms", 300)
        }
    }

    private fun sendSessionUpdate() {
        val session = JsonObject().apply {
            addProperty("type", "realtime")
            addProperty("instructions", buildInstructions(currentTargetLanguage))
            add("output_modalities", JsonArray().apply { add("audio") })

            add("audio", JsonObject().apply {
                add("input", JsonObject().apply {
                    add("format", JsonObject().apply {
                        addProperty("type", "audio/pcm")
                        addProperty("rate", inputSampleRate)
                    })
                    add("noise_reduction", JsonObject().apply {
                        addProperty("type", "near_field")
                    })
                    add("transcription", JsonObject().apply {
                        addProperty("model", transcriptionModel)
                        addProperty("language", "en")
                    })
                    add("turn_detection", buildTurnDetection(currentSilenceMs))
                })
                add("output", JsonObject().apply {
                    add("format", JsonObject().apply {
                        addProperty("type", "audio/pcm")
                        addProperty("rate", outputSampleRate)
                    })
                    addProperty("voice", voice)
                })
            })
        }

        val event = JsonObject().apply {
            addProperty("type", "session.update")
            add("session", session)
        }
        AppLogger.log("OPENAI", "send session.update")
        webSocket?.send(gson.toJson(event))
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            listener.onConnected()
            AppLogger.log("OPENAI", "websocket open code=${response.code}")
            sendSessionUpdate()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                AppLogger.log("OPENAI", "raw event: ${text.take(500)}")
                val obj = JsonParser.parseString(text).asJsonObject
                val type = obj.get("type")?.asString ?: return

                when (type) {
                    "session.created" -> listener.onStatus("OpenAI session created")
                    "session.updated" -> {
                        listener.onStatus("OpenAI session updated")
                        listener.onSessionReady()
                    }
                    "input_audio_buffer.speech_started" -> {
                        inputTranscript.setLength(0)
                        listener.onSpeechStarted()
                    }
                    "conversation.item.input_audio_transcription.delta" -> {
                        val delta = obj.get("delta")?.asString ?: ""
                        if (delta.isNotEmpty()) {
                            inputTranscript.append(delta)
                            listener.onInputTranscript(inputTranscript.toString(), false)
                        }
                    }
                    "conversation.item.input_audio_transcription.completed" -> {
                        val transcript = obj.get("transcript")?.asString ?: ""
                        inputTranscript.setLength(0)
                        inputTranscript.append(transcript)
                        listener.onInputTranscript(inputTranscript.toString(), true)
                    }
                    "response.created" -> {
                        outputTranscript.setLength(0)
                    }
                    "response.output_audio_transcript.delta" -> {
                        val delta = obj.get("delta")?.asString ?: ""
                        if (delta.isNotEmpty()) {
                            outputTranscript.append(delta)
                            listener.onOutputTranscript(outputTranscript.toString(), false)
                        }
                    }
                    "response.output_audio_transcript.done" -> {
                        listener.onOutputTranscript(outputTranscript.toString(), true)
                    }
                    "response.output_audio.delta", "response.audio.delta" -> {
                        val payload = obj.get("delta")?.asString ?: obj.get("audio")?.asString ?: ""
                        if (payload.isNotEmpty()) {
                            val audioBytes = Base64.decode(payload, Base64.NO_WRAP)
                            listener.onAudioDelta(audioBytes)
                        }
                    }
                    "error" -> {
                        val message = obj.getAsJsonObject("error")?.get("message")?.asString
                        listener.onError(message ?: "OpenAI error")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse message", e)
                AppLogger.log("OPENAI", "parse error: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            listener.onDisconnected()
            AppLogger.log("OPENAI", "websocket closing code=$code reason=$reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            listener.onError(t.message ?: "WebSocket failure")
            AppLogger.log("OPENAI", "websocket failure: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "OpenAIRealtimeClient"
    }
}
