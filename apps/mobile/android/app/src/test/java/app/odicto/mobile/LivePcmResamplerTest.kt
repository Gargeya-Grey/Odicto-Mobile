package app.odicto.mobile

import app.odicto.mobile.network.LivePcmResampler
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LivePcmResamplerTest {
    private fun pcm(vararg samples: Int) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it.toShort()) }
    }.array()

    @Test fun rampUsesLinearInterpolationAndMaintainsDuration() {
        val converter = LivePcmResampler()
        val result = converter.append(pcm(0, 300, 600, 900), 8) + converter.finish()
        assertArrayEquals(pcm(0, 200, 400, 600, 800, 900), result)
    }

    @Test fun chunkBoundariesDoNotChangeSamplesIncludingNegativeValues() {
        val input = pcm(*IntArray(3200) { ((it * 151) % 65536) - 32768 })
        val whole = LivePcmResampler().let { it.append(input, input.size) + it.finish() }
        val chunks = LivePcmResampler()
        val out = java.io.ByteArrayOutputStream()
        for (offset in input.indices step 2) out.write(chunks.append(input.copyOfRange(offset, offset + 2), 2))
        out.write(chunks.finish())
        assertArrayEquals(whole, out.toByteArray())
        assertEquals(input.size * 3 / 2, whole.size)
    }
}
