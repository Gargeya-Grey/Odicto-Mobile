package app.odicto.mobile

import app.odicto.mobile.network.FlacEncoder
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The encoder must be lossless and bit-exact against the FLAC format; a small decoder in this file checks both. */
class FlacEncoderTest {
    private fun pcm(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> out[2 * i] = s.toByte(); out[2 * i + 1] = (s.toInt() shr 8).toByte() }
        return out
    }

    private fun encode(samples: ShortArray, rate: Int = 16_000): ByteArray =
        FlacEncoder(rate).apply { append(pcm(samples)) }.finish()

    private fun silence(n: Int) = ShortArray(n)
    private fun sine(n: Int) = ShortArray(n) { (20_000 * sin(2 * PI * 440.0 * it / 16_000)).toInt().toShort() }
    private fun sweep(n: Int) = ShortArray(n) {
        val t = it / 16_000.0
        (15_000 * sin(2 * PI * (100.0 * t + 1_500.0 * t * t / 2 / (n / 16_000.0)))).toInt().toShort()
    }
    private fun noise(n: Int, seed: Long = 7) = Random(seed).let { r -> ShortArray(n) { (r.nextInt(65_536) - 32_768).toShort() } }
    private fun alternating(n: Int) = ShortArray(n) { if (it % 2 == 0) 32_767 else -32_768 }
    private fun dc(n: Int) = ShortArray(n) { 1_234 }
    private fun sweepPlusNoise(n: Int): ShortArray {
        val s = sweep(n); val r = Random(11)
        return ShortArray(n) { (s[it] + r.nextInt(2_001) - 1_000).toShort() }
    }

    // ---- 1. exact round trip ----

    @Test fun silenceRoundTrips() = assertRoundTrip(silence(10_000))
    @Test fun sineRoundTrips() = assertRoundTrip(sine(10_000))
    @Test fun sweepRoundTrips() = assertRoundTrip(sweep(20_000))
    @Test fun fullScaleNoiseRoundTrips() = assertRoundTrip(noise(10_000))
    @Test fun fullScaleAlternationRoundTrips() = assertRoundTrip(alternating(10_000))

    @Test fun dcOffsetUsesConstantSubframes() {
        val flac = decode(encode(dc(8_192)))
        assertArrayEquals(dc(8_192), flac.samples)
        assertEquals(mapOf("CONSTANT" to 2), flac.subframes)
    }

    @Test fun fullScaleAlternationAfterSilenceUsesWideRiceParameters() {
        // Order 0 on 32767/-32768 needs Rice parameter 15, which only the 5-bit method can express.
        val s = ShortArray(8_192) { if (it % 4_096 < 2_048) 0 else if (it % 2 == 0) 32_767 else -32_768 }
        val flac = decode(encode(s))
        assertArrayEquals(s, flac.samples)
        assertTrue("5-bit Rice parameters never used: ${flac.riceMethods}", 1 in flac.riceMethods)
    }

    /** Integrates small random steps [order] times, so the fixed predictor of that order leaves only the steps. */
    private fun integrated(order: Int, n: Int, seed: Long): ShortArray {
        val r = Random(seed)
        val x = LongArray(n) { (r.nextInt(3) - 1).toLong() }
        repeat(order) { for (i in 1 until n) x[i] += x[i - 1] }
        x.forEach { assertTrue("test signal must fit 16 bits", it in -32_768..32_767) }
        return ShortArray(n) { x[it].toShort() }
    }

    @Test fun everyFixedOrderRoundTripsAndIsChosenSomewhere() {
        val signals = listOf(integrated(0, 4_096, 1), integrated(1, 1_000, 2), integrated(2, 200, 3), integrated(3, 50, 4), integrated(4, 30, 5))
        val kinds = mutableSetOf<String>()
        for (s in signals) {
            val flac = decode(encode(s))
            assertArrayEquals(s, flac.samples)
            kinds += flac.subframes.keys
        }
        assertEquals(setOf("FIXED0", "FIXED1", "FIXED2", "FIXED3", "FIXED4"), kinds)
    }

    // ---- 2. lengths ----

    @Test fun edgeLengthsRoundTrip() {
        for (n in intArrayOf(0, 1, 15, 4095, 4096, 4097, 3 * 4096 + 17)) {
            val s = sweep(n)
            val flac = decode(encode(s))
            assertEquals("total for $n", n.toLong(), flac.total)
            assertArrayEquals("samples for $n", s, flac.samples)
        }
    }

    @Test fun emptyStreamIsAHeaderOnly() {
        val bytes = encode(silence(0))
        assertEquals(42, bytes.size)
        assertEquals(0L, decode(bytes).total)
    }

    @Test fun singleSampleFrameHasTheExactHeader() {
        val bytes = encode(shortArrayOf(5))
        assertEquals(54, bytes.size)
        // sync, block size code 0110, rate code 0101, mono 16-bit, frame 0, block size - 1
        assertArrayEquals(
            intArrayOf(0xFF, 0xF8, 0x65, 0x08, 0x00, 0x00),
            IntArray(6) { bytes[42 + it].toInt() and 0xFF },
        )
    }

    @Test fun fullBlockUsesTheFourKiloSampleCode() {
        val bytes = encode(sine(4096))
        assertArrayEquals(intArrayOf(0xFF, 0xF8, 0xC5, 0x08, 0x00), IntArray(5) { bytes[42 + it].toInt() and 0xFF })
    }

    @Test fun shortFinalBlockCodes() {
        val bytes = encode(sine(4096 + 300))
        val second = decode(bytes).frameStarts[1]
        // 300 samples need the 16-bit size field: code 0111, value 299
        assertArrayEquals(
            intArrayOf(0xFF, 0xF8, 0x75, 0x08, 0x01, 0x01, 0x2B),
            IntArray(7) { bytes[second + it].toInt() and 0xFF },
        )
    }

    @Test fun otherSampleRatesRoundTrip() {
        for (rate in intArrayOf(8_000, 22_050, 24_000, 32_000, 44_100, 48_000, 12_345)) {
            val flac = decode(encode(sine(5_000), rate))
            assertEquals(rate, flac.sampleRate)
            assertArrayEquals(sine(5_000), flac.samples)
        }
    }

    @Test fun frameNumbersPastOneByteDecode() {
        val s = ShortArray(4096 * 140) { (it % 50).toShort() }
        val flac = decode(encode(s))
        assertEquals(140, flac.frameStarts.size)
        assertArrayEquals(s, flac.samples)
    }

    // ---- 3. chunking ----

    @Test fun chunkedAppendsMatchOneAppend() {
        val all = pcm(sweepPlusNoise(20_000))
        val whole = FlacEncoder().apply { append(all) }.finish()
        val chunked = FlacEncoder().apply {
            var at = 0
            for (size in intArrayOf(1, 3, 3_199)) { append(all, at, size); at += size }
            append(all, at, all.size - at)
        }.finish()
        assertArrayEquals(whole, chunked)
    }

    @Test fun oddByteChunksEverywhereMatch() {
        val all = pcm(sweepPlusNoise(9_001))
        val whole = FlacEncoder().apply { append(all) }.finish()
        val chunked = FlacEncoder().apply { var at = 0; while (at < all.size) { val n = minOf(1_001, all.size - at); append(all, at, n); at += n } }.finish()
        assertArrayEquals(whole, chunked)
    }

    @Test fun sampleCountCountsWholeSamplesOnly() {
        val enc = FlacEncoder()
        enc.append(ByteArray(7))
        assertEquals(3L, enc.sampleCount)
        enc.append(ByteArray(1))
        assertEquals(4L, enc.sampleCount)
    }

    // ---- 4. STREAMINFO ----

    @Test fun streamInfoDescribesTheStream() {
        val samples = sweepPlusNoise(10_000)
        val bytes = encode(samples)
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.ISO_8859_1))
        assertEquals(0x80, bytes[4].toInt() and 0xFF)
        assertArrayEquals(intArrayOf(0, 0, 34), IntArray(3) { bytes[5 + it].toInt() and 0xFF })
        val flac = decode(bytes)
        assertEquals(16_000, flac.sampleRate)
        assertEquals(1, flac.channels)
        assertEquals(16, flac.bitsPerSample)
        assertEquals(10_000L, flac.total)
        assertEquals(4096, flac.minBlock)
        assertEquals(4096, flac.maxBlock)
        assertEquals(3, flac.frameStarts.size)
        assertEquals(flac.frameSizes.min(), flac.minFrame)
        assertEquals(flac.frameSizes.max(), flac.maxFrame)
        assertArrayEquals(ByteArray(16), flac.md5)
    }

    @Test fun streamInfoOfSingleFrameStreamHasThatFrameSize() {
        val flac = decode(encode(dc(4096)))
        assertEquals(1, flac.frameSizes.size)
        assertEquals(flac.frameSizes[0], flac.minFrame)
        assertEquals(flac.frameSizes[0], flac.maxFrame)
    }

    @Test fun decoderRejectsACorruptedFrame() {
        val bytes = encode(sweep(5_000))
        bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0x10).toByte()
        try { decode(bytes); fail("a flipped bit must break a CRC") } catch (_: AssertionError) {}
    }

    // ---- 5. size ----

    @Test fun silenceCompressesBelowTwoPercent() {
        val wav = 44 + 2 * 480_000
        assertTrue(encode(silence(480_000)).size < wav / 50)
    }

    @Test fun noiseNeverGrowsPastOnePercentOverWav() {
        val wav = 44 + 2 * 480_000
        val flac = encode(noise(480_000)).size
        assertTrue("flac=$flac wav=$wav", flac <= wav + wav / 100)
        assertTrue(flac > wav * 99 / 100)
    }

    @Test fun alternatingFullScaleStaysNearWavSize() {
        val wav = 44 + 2 * 100_000
        assertTrue(encode(alternating(100_000)).size <= wav + wav / 100)
    }

    @Test fun sweepCompressesWell() {
        val wav = 44 + 2 * 160_000
        assertTrue(encode(sweep(160_000)).size < wav * 6 / 10)
    }

    // ---- 6. lifecycle ----

    @Test fun finishTwiceThrows() {
        val enc = FlacEncoder().apply { append(pcm(sine(100))) }
        enc.finish()
        try { enc.finish(); fail("second finish must throw") } catch (_: IllegalStateException) {}
    }

    @Test fun appendAfterFinishThrows() {
        val enc = FlacEncoder().apply { finish() }
        try { enc.append(ByteArray(2)); fail("append after finish must throw") } catch (_: IllegalStateException) {}
    }

    @Test fun clearThenAppendThrows() {
        val enc = FlacEncoder().apply { append(pcm(sine(5_000))) }
        enc.clear()
        try { enc.append(ByteArray(2)); fail("append after clear must throw") } catch (_: IllegalStateException) {}
    }

    @Test fun clearThenFinishThrows() {
        val enc = FlacEncoder().apply { append(pcm(sine(5_000))) }
        enc.clear()
        try { enc.finish(); fail("finish after clear must throw") } catch (_: IllegalStateException) {}
    }

    @Test fun clearAfterFinishIsHarmless() {
        val enc = FlacEncoder().apply { append(pcm(sine(5_000))) }
        enc.finish()
        enc.clear()
        enc.clear()
    }

    @Test fun badRangesAreRejected() {
        val enc = FlacEncoder()
        try { enc.append(ByteArray(4), 3, 4); fail("range past the end") } catch (_: IllegalArgumentException) {}
        try { FlacEncoder(0); fail("rate 0") } catch (_: IllegalArgumentException) {}
    }

    @Test fun finishedBytesAreIndependentOfLaterUse() {
        val enc = FlacEncoder().apply { append(pcm(sine(5_000))) }
        val bytes = enc.finish()
        val copy = bytes.copyOf()
        enc.clear()
        assertArrayEquals(copy, bytes)
    }

    // ---- 7. speed ----

    @Test fun thirtySecondsEncodeQuickly() {
        val all = pcm(sweepPlusNoise(480_000))
        val start = System.nanoTime()
        val bytes = FlacEncoder().apply { append(all) }.finish()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("FLAC 30 s encode: $ms ms, ${bytes.size} bytes")
        assertTrue("took $ms ms", ms < 2_000)
    }

    // ---- minimal FLAC decoder: exactly what the encoder emits ----

    private fun assertRoundTrip(samples: ShortArray) {
        val flac = decode(encode(samples))
        assertEquals(samples.size.toLong(), flac.total)
        assertArrayEquals(samples, flac.samples)
    }

    private class Decoded(
        val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val total: Long,
        val minBlock: Int, val maxBlock: Int, val minFrame: Int, val maxFrame: Int, val md5: ByteArray,
        val samples: ShortArray, val frameStarts: List<Int>, val frameSizes: List<Int>,
        val subframes: Map<String, Int>, val riceMethods: Set<Int>,
    )

    private class Bits(private val b: ByteArray, var pos: Int) {
        fun read(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 1) or ((b[pos shr 3].toInt() shr (7 - (pos and 7))) and 1).toLong(); pos++ }
            return v
        }
        fun signed(n: Int): Long { val v = read(n); return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v }
        fun unary(): Long { var q = 0L; while (read(1) == 0L) q++; return q }
    }

    private fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) { c = c xor (b[i].toInt() and 0xFF); repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1; c = c and 0xFF } }
        return c
    }

    private fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) { c = c xor ((b[i].toInt() and 0xFF) shl 8); repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1; c = c and 0xFFFF } }
        return c
    }

    private fun decode(b: ByteArray): Decoded {
        assertEquals("fLaC", String(b, 0, 4, Charsets.ISO_8859_1))
        val h = Bits(b, 32)
        assertEquals("last-metadata flag", 1L, h.read(1))
        assertEquals("STREAMINFO type", 0L, h.read(7))
        assertEquals(34L, h.read(24))
        val minBlock = h.read(16).toInt(); val maxBlock = h.read(16).toInt()
        val minFrame = h.read(24).toInt(); val maxFrame = h.read(24).toInt()
        val rate = h.read(20).toInt(); val channels = h.read(3).toInt() + 1; val bps = h.read(5).toInt() + 1
        val total = h.read(36)
        val md5 = ByteArray(16) { h.read(8).toByte() }
        assertEquals(42 * 8, h.pos)
        val samples = ShortArray(total.toInt())
        var done = 0
        var pos = 42
        val starts = mutableListOf<Int>(); val sizes = mutableListOf<Int>()
        val kinds = sortedMapOf<String, Int>(); val methods = mutableSetOf<Int>()
        while (pos < b.size) {
            val start = pos
            val br = Bits(b, pos * 8)
            assertEquals(0xFFF8L, br.read(16))
            val bsCode = br.read(4).toInt(); val rateCode = br.read(4).toInt()
            assertEquals(0L, br.read(4)); assertEquals(4L, br.read(3)); assertEquals(0L, br.read(1))
            val first = br.read(8).toInt()
            var frameNo = first.toLong()
            if (first >= 0x80) {
                val extra = Integer.numberOfLeadingZeros(first.inv() shl 24) - 1
                frameNo = (first and (0x7F shr (extra + 1))).toLong()
                repeat(extra) { val c = br.read(8).toInt(); assertEquals(0x80, c and 0xC0); frameNo = (frameNo shl 6) or (c and 0x3F).toLong() }
            }
            assertEquals("frame number", starts.size.toLong(), frameNo)
            val n = when (bsCode) { 12 -> 4096; 6 -> br.read(8).toInt() + 1; 7 -> br.read(16).toInt() + 1; else -> throw AssertionError("block size code $bsCode") }
            val codeRate = mapOf(4 to 8_000, 5 to 16_000, 6 to 22_050, 7 to 24_000, 8 to 32_000, 9 to 44_100, 10 to 48_000)[rateCode] ?: rate
            assertEquals(rate, codeRate)
            assertEquals("CRC-8", crc8(b, start, br.pos / 8), br.read(8).toInt())
            assertEquals(0L, br.read(1))
            val type = br.read(6).toInt()
            assertEquals(0L, br.read(1))
            val x = IntArray(n)
            when {
                type == 0 -> { val v = br.signed(16).toInt(); x.fill(v); kinds.merge("CONSTANT", 1, Int::plus) }
                type == 1 -> { for (i in 0 until n) x[i] = br.signed(16).toInt(); kinds.merge("VERBATIM", 1, Int::plus) }
                type in 8..12 -> { val order = type - 8; kinds.merge("FIXED$order", 1, Int::plus); fixed(br, x, order, methods) }
                else -> throw AssertionError("subframe type $type")
            }
            if (br.pos and 7 != 0) assertEquals("padding", 0L, br.read(8 - (br.pos and 7)))
            val end = br.pos / 8
            assertEquals("CRC-16", crc16(b, start, end), br.read(16).toInt())
            for (i in 0 until n) samples[done + i] = x[i].toShort()
            done += n
            starts += start; sizes += br.pos / 8 - start; pos = br.pos / 8
        }
        assertEquals("decoded sample total", total.toInt(), done)
        return Decoded(rate, channels, bps, total, minBlock, maxBlock, minFrame, maxFrame, md5, samples, starts, sizes, kinds, methods)
    }

    private fun fixed(br: Bits, x: IntArray, order: Int, methods: MutableSet<Int>) {
        val n = x.size
        for (i in 0 until order) x[i] = br.signed(16).toInt()
        val method = br.read(2).toInt()
        assertTrue("Rice method $method", method == 0 || method == 1)
        methods += method
        val po = br.read(4).toInt()
        assertTrue("partition order $po", po <= 8 && n % (1 shl po) == 0 && (n shr po) > order)
        val bits = if (method == 0) 4 else 5
        var i = order
        for (p in 0 until (1 shl po)) {
            val k = br.read(bits).toInt()
            assertTrue("escape code", k != (1 shl bits) - 1)
            val count = (n shr po) - (if (p == 0) order else 0)
            repeat(count) {
                val u = (br.unary() shl k) or br.read(k)
                val r = (u ushr 1) xor -(u and 1)
                val s = { back: Int -> x[i - back].toLong() }
                val pred = when (order) {
                    0 -> 0L
                    1 -> s(1)
                    2 -> 2 * s(1) - s(2)
                    3 -> 3 * s(1) - 3 * s(2) + s(3)
                    else -> 4 * s(1) - 6 * s(2) + 4 * s(3) - s(4)
                }
                val v = pred + r
                assertTrue("sample out of 16-bit range: $v", v in -32_768..32_767)
                x[i++] = v.toInt()
            }
        }
        assertEquals(n, i)
    }
}
