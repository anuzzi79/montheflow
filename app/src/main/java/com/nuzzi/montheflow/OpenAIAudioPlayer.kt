package com.nuzzi.montheflow

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class OpenAIAudioPlayer(
    private val sampleRate: Int = 24000
) {

    private var audioTrack: AudioTrack? = null

    private fun ensureTrack() {
        if (audioTrack != null) return

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = if (minBuffer > 0) minBuffer else sampleRate * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        audioTrack?.play()
    }

    fun playAudio(chunk: ByteArray) {
        ensureTrack()
        audioTrack?.write(chunk, 0, chunk.size)
    }

    fun interrupt() {
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    fun stop() {
        audioTrack?.stop()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null
    }
}
