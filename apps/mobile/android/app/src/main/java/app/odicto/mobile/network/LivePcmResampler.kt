package app.odicto.mobile.network

/** Stateful mono PCM16LE 16 kHz to 24 kHz interpolation. Capture chunk boundaries have no effect. */
internal class LivePcmResampler {
    private var previous: Int? = null
    private var inputIndex = -1L
    private var outputPosition = 0L

    fun append(bytes: ByteArray, count: Int): ByteArray {
        require(count in 0..bytes.size && count % 2 == 0)
        val output = java.io.ByteArrayOutputStream(count * 3 / 2 + 4)
        for (offset in 0 until count step 2) {
            val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
            inputIndex++
            val before = previous
            if (before == null) {
                write(output, sample)
                outputPosition = 2
            } else {
                while (outputPosition <= inputIndex * 3) {
                    val fraction = (outputPosition - (inputIndex - 1) * 3).toInt()
                    write(output, (before * (3 - fraction) + sample * fraction) / 3)
                    outputPosition += 2
                }
            }
            previous = sample
        }
        return output.toByteArray()
    }

    fun finish(): ByteArray {
        val output = java.io.ByteArrayOutputStream(4)
        val last = previous ?: return byteArrayOf()
        while (outputPosition < (inputIndex + 1) * 3) {
            write(output, last)
            outputPosition += 2
        }
        previous = null
        return output.toByteArray()
    }

    fun clear() { previous = null; inputIndex = -1; outputPosition = 0 }

    private fun write(output: java.io.ByteArrayOutputStream, sample: Int) {
        output.write(sample and 255)
        output.write((sample shr 8) and 255)
    }
}
