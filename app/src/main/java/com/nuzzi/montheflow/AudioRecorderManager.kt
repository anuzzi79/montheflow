package com.nuzzi.montheflow

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class AudioRecorderManager(
    private val callback: (ByteArray) -> Unit,
    private val sampleRate: Int = 16000
) {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    // Configurazioni richieste da AssemblyAI
    private val SAMPLE_RATE = sampleRate
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    // Buffer size: AssemblyAI consiglia chunk tra 100ms e 2s, ma per streaming fluido 
    // usiamo buffer più piccoli (es. circa 100ms-200ms di audio alla volta).
    // MinBufferSize garantisce che non sia troppo piccolo per l'hardware.
    private val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    // Usiamo un buffer leggermente più grande per sicurezza, o leggiamo a chunk fissi.
    private val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 0

    @SuppressLint("MissingPermission") // Il permesso viene controllato prima di chiamare start() nella UI
    fun start(): Boolean {
        if (isRecording) return true

        // Controllo preventivo per evitare crash su dispositivi non supportati
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: $minBufferSize. Audio configuration not supported.")
            AppLogger.log("AUDIO", "Invalid buffer size: $minBufferSize")
            return false
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, // Cambiato da MIC a VOICE_RECOGNITION per migliore qualità voce
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                AppLogger.log("AUDIO", "AudioRecord initialization failed")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            AppLogger.log("AUDIO", "Recording started sampleRate=$SAMPLE_RATE bufferSize=$bufferSize")

            recordingThread = Thread {
                readAudioLoop()
            }
            recordingThread?.start()
            
            Log.d(TAG, "Recording started")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            AppLogger.log("AUDIO", "Error starting recording: ${e.message}")
            return false
        }
    }

    fun stop() {
        if (!isRecording) return

        isRecording = false
        try {
            recordingThread?.join() // Aspetta che il thread finisca
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "Recording stopped")
            AppLogger.log("AUDIO", "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            AppLogger.log("AUDIO", "Error stopping recording: ${e.message}")
        }
    }

    private fun sendAgentLog(message: String) {
        Thread {
            try {
                // Use 10.0.2.2 to reach the host's localhost from Android Emulator
                val json = "{\"sessionId\":\"debug-session\",\"runId\":\"run1\",\"hypothesisId\":\"H_AUDIO\",\"location\":\"AudioRecorderManager.kt\",\"message\":\"$message\",\"data\":{},\"timestamp\":${System.currentTimeMillis()}}"
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = RequestBody.create(mediaType, json)
                val request = Request.Builder()
                    .url("http://10.0.2.2:7248/ingest/e7b2e2e3-a60d-4a7b-acfa-2950feba4fde")
                    .post(body)
                    .build()
                OkHttpClient().newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "AgentLog Failed: ${e.message}")
            }
        }.start()
    }

    private fun readAudioLoop() {
        // Buffer di lettura: chunk frequenti (~100ms).
        // sampleRate Hz * 2 bytes (16bit) = bytes/sec.
        val readBufferSize = (SAMPLE_RATE / 10) * 2
        val data = ByteArray(readBufferSize)
        var chunkCounter = 0

        try {
            while (isRecording) {
                val result = audioRecord?.read(data, 0, readBufferSize) ?: 0
                if (result > 0) {
                    // Check di debug: controlla se il buffer è tutto zeri (silenzio assoluto)
                    // var isSilence = true
                    // for (i in 0 until 100) { // Controlla i primi 100 byte
                    //    if (data[i] != 0.toByte()) { isSilence = false; break }
                    // }
                    // if (!isSilence) Log.v(TAG, "Sending ${result} bytes of audio") 
                    
                    // Invia copia dei dati validi
                    val audioChunk = data.copyOf(result)
                    
                    // Simple RMS (Root Mean Square) check
                    var sum = 0.0
                    for (i in 0 until result step 2) {
                        if (i + 1 < result) {
                            val sample = (data[i].toInt() and 0xFF) or (data[i+1].toInt() shl 8)
                            val normalized = sample.toShort().toDouble() / 32768.0
                            sum += normalized * normalized
                        }
                    }
                    val rms = Math.sqrt(sum / (result / 2))
                    chunkCounter++
                    if (chunkCounter % 50 == 0) {
                        AppLogger.log("AUDIO", "chunk=$chunkCounter bytes=$result rms=$rms")
                    }

                    callback(audioChunk)
                } else if (result < 0) {
                    Log.w(TAG, "Audio read error: $result")
                    AppLogger.log("AUDIO", "Audio read error: $result")
                    // Se l'errore è critico, usciamo dal loop
                    if (result == AudioRecord.ERROR_INVALID_OPERATION || result == AudioRecord.ERROR_BAD_VALUE) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio loop", e)
        }
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
    }
}
