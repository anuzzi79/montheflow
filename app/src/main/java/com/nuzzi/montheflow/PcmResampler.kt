package com.nuzzi.montheflow

object PcmResampler {

    // Upsample PCM16 mono from 16kHz to 24kHz using linear interpolation.
    fun upsample16kTo24k(pcm16: ByteArray): ByteArray {
        val inSamples = pcm16.size / 2
        if (inSamples == 0) return ByteArray(0)

        val outSamples = (inSamples * 3) / 2
        val out = ByteArray(outSamples * 2)

        var outIndex = 0
        var i = 0
        while (i + 1 < inSamples && outIndex + 2 < outSamples) {
            val a = readSample(pcm16, i)
            val b = readSample(pcm16, i + 1)

            writeSample(out, outIndex++, a)
            writeSample(out, outIndex++, ((a + b) / 2).toShort())
            writeSample(out, outIndex++, b)

            i += 2
        }

        if (i < inSamples && outIndex < outSamples) {
            val a = readSample(pcm16, i)
            writeSample(out, outIndex, a)
        }

        return out
    }

    private fun readSample(buffer: ByteArray, index: Int): Short {
        val lo = buffer[index * 2].toInt() and 0xFF
        val hi = buffer[index * 2 + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun writeSample(buffer: ByteArray, index: Int, value: Short) {
        buffer[index * 2] = (value.toInt() and 0xFF).toByte()
        buffer[index * 2 + 1] = (value.toInt() shr 8).toByte()
    }
}
