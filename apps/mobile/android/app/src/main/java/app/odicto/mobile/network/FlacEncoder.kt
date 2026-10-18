package app.odicto.mobile.network

/** Streams 16-bit little-endian mono PCM into an in-memory FLAC file. Lossless; nothing touches disk. */
class FlacEncoder(private val sampleRate: Int = 16_000) {
    /** Number of samples appended so far. */
    var sampleCount: Long = 0
        private set

    private val block = IntArray(BLOCK)
    private var filled = 0
    private var carry = -1
    private var out = ByteArray(INITIAL_CAPACITY)
    private var len = HEADER_BYTES
    private var frames = 0
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private var state = OPEN
    private var acc = 0L
    private var accBits = 0

    // Per-block scratch, allocated once. Zigzag residuals, Rice cost table, chosen partition parameters.
    private val zigzag = IntArray(BLOCK)
    private val cost = LongArray(MAX_PARTITIONS * (MAX_PARAM + 1))
    private val params4 = IntArray(MAX_PARTITIONS)
    private val params5 = IntArray(MAX_PARTITIONS)
    private val bestParams = IntArray(MAX_PARTITIONS)
    private var bestBits = 0L
    private var bestOrder = 0
    private var bestPartitionOrder = 0
    private var bestMethod = 0

    init {
        require(sampleRate in 1 until (1 shl 20)) { "sample rate out of range" }
    }

    /** Appends PCM bytes. A trailing odd byte is carried to the next call. Each full block is encoded at once. */
    fun append(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        check(state == OPEN) { "encoder is closed" }
        require(offset >= 0 && length >= 0 && length <= pcm.size - offset) { "range outside the buffer" }
        var i = offset
        val end = offset + length
        if (carry >= 0 && i < end) {
            push(carry or (pcm[i++].toInt() shl 8))
            carry = -1
        }
        while (i + 1 < end) {
            push((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8))
            i += 2
        }
        if (i < end) carry = pcm[i].toInt() and 0xFF
    }

    /** Encodes the remaining samples and returns the complete .flac file. Call once; later calls throw IllegalStateException. Internal buffers are wiped afterwards. */
    fun finish(): ByteArray {
        check(state == OPEN) { "encoder is closed" }
        if (filled > 0) encodeBlock(filled)
        writeStreamInfo()
        val result = out.copyOf(len)
        wipe()
        state = FINISHED
        return result
    }

    /** Zero-fills and drops every buffered sample and encoded byte (used on cancel). */
    fun clear() {
        if (state == OPEN) state = CLEARED
        wipe()
    }

    private fun push(raw: Int) {
        block[filled++] = raw.toShort().toInt()
        sampleCount++
        if (filled == BLOCK) {
            encodeBlock(BLOCK)
            filled = 0
        }
    }

    private fun wipe() {
        block.fill(0); zigzag.fill(0); cost.fill(0)
        params4.fill(0); params5.fill(0); bestParams.fill(0)
        out.fill(0)
        out = EMPTY
        len = 0; filled = 0; carry = -1; acc = 0; accBits = 0
    }

    private fun encodeBlock(n: Int) {
        val frameStart = len
        val sizeCode = when { n == BLOCK -> 12; n <= 256 -> 6; else -> 7 }
        putByte(0xFF)
        putByte(0xF8)
        putByte((sizeCode shl 4) or rateCode())
        putByte(0x08)
        putFrameNumber(frames)
        if (sizeCode == 6) putByte(n - 1)
        if (sizeCode == 7) { putByte((n - 1) shr 8); putByte((n - 1) and 0xFF) }
        putByte(crc8(out, frameStart, len))

        writeSubframe(n)
        if (accBits > 0) writeBits(0, 8 - accBits)
        val crc = crc16(out, frameStart, len)
        putByte(crc shr 8)
        putByte(crc and 0xFF)

        val size = len - frameStart
        if (size < minFrame) minFrame = size
        if (size > maxFrame) maxFrame = size
        frames++
        acc = 0; accBits = 0
    }

    private fun rateCode() = when (sampleRate) {
        8_000 -> 4; 16_000 -> 5; 22_050 -> 6; 24_000 -> 7; 32_000 -> 8; 44_100 -> 9; 48_000 -> 10
        else -> 0
    }

    private fun writeSubframe(n: Int) {
        val s = block
        var constant = true
        for (i in 1 until n) if (s[i] != s[0]) { constant = false; break }
        if (constant) {
            putByte(0x00)
            writeBits(s[0].toLong() and 0xFFFF, 16)
            return
        }
        bestBits = Long.MAX_VALUE
        for (order in 0..MAX_ORDER) if (n > order) searchRice(n, order)
        if (bestBits >= 8L + 16L * n) {
            putByte(0x02)
            for (i in 0 until n) writeBits(s[i].toLong() and 0xFFFF, 16)
            return
        }
        val order = bestOrder
        putByte(0x10 or (order shl 1))
        for (i in 0 until order) writeBits(s[i].toLong() and 0xFFFF, 16)
        residuals(order, n)
        writeBits(bestMethod.toLong(), 2)
        writeBits(bestPartitionOrder.toLong(), 4)
        val paramBits = if (bestMethod == 0) 4 else 5
        val size = n shr bestPartitionOrder
        var i = order
        for (p in 0 until (1 shl bestPartitionOrder)) {
            val k = bestParams[p]
            writeBits(k.toLong(), paramBits)
            val stop = (p + 1) * size
            while (i < stop) {
                val z = zigzag[i++]
                var q = z ushr k
                while (q >= 32) { writeBits(0, 32); q -= 32 }
                if (q > 0) writeBits(0, q)
                writeBits((1L shl k) or (z.toLong() and ((1L shl k) - 1)), k + 1)
            }
        }
    }

    /** Zigzag-maps the fixed-predictor residuals of [order] into [zigzag] (from index [order]) and returns the largest. */
    private fun residuals(order: Int, n: Int): Int {
        val s = block
        val z = zigzag
        var max = 0
        for (i in order until n) {
            // |r| <= 16 * 32768, so Int arithmetic cannot overflow.
            val r = when (order) {
                0 -> s[i]
                1 -> s[i] - s[i - 1]
                2 -> s[i] - 2 * s[i - 1] + s[i - 2]
                3 -> s[i] - 3 * s[i - 1] + 3 * s[i - 2] - s[i - 3]
                else -> s[i] - 4 * s[i - 1] + 6 * s[i - 2] - 4 * s[i - 3] + s[i - 4]
            }
            val u = (r shl 1) xor (r shr 31)
            z[i] = u
            if (u > max) max = u
        }
        return max
    }

    private fun searchRice(n: Int, order: Int) {
        val maxU = residuals(order, n)
        val top = minOf(MAX_PARAM, 32 - Integer.numberOfLeadingZeros(maxU))
        val width = top + 1
        var maxPo = 0
        while (maxPo < MAX_PARTITION_ORDER && n % (2 shl maxPo) == 0 && (n shr (maxPo + 1)) > order) maxPo++

        var parts = 1 shl maxPo
        val size = n shr maxPo
        java.util.Arrays.fill(cost, 0, parts * width, 0L)
        var i = order
        for (p in 0 until parts) {
            val base = p * width
            val stop = (p + 1) * size
            while (i < stop) {
                val u = zigzag[i++]
                for (k in 0..top) cost[base + k] += (u ushr k).toLong()
            }
        }

        var po = maxPo
        while (true) {
            val partSize = n shr po
            var total4 = 0L
            var total5 = 0L
            for (p in 0 until parts) {
                val count = (partSize - if (p == 0) order else 0).toLong()
                val base = p * width
                var best5 = Long.MAX_VALUE; var k5 = 0
                var best4 = Long.MAX_VALUE; var k4 = 0
                for (k in 0..top) {
                    val c = cost[base + k] + count * (k + 1)
                    if (c < best5) { best5 = c; k5 = k }
                    if (k <= MAX_PARAM_4BIT && c < best4) { best4 = c; k4 = k }
                }
                total4 += best4 + 4; total5 += best5 + 5
                params4[p] = k4; params5[p] = k5
            }
            val method = if (total4 <= total5) 0 else 1
            val bits = 8L + 16L * order + 6 + if (method == 0) total4 else total5
            if (bits < bestBits) {
                bestBits = bits; bestOrder = order; bestPartitionOrder = po; bestMethod = method
                System.arraycopy(if (method == 0) params4 else params5, 0, bestParams, 0, parts)
            }
            if (po == 0) break
            parts = parts shr 1
            for (p in 0 until parts) for (k in 0..top) cost[p * width + k] = cost[2 * p * width + k] + cost[(2 * p + 1) * width + k]
            po--
        }
    }

    private fun writeStreamInfo() {
        val w = out
        w[0] = 'f'.code.toByte(); w[1] = 'L'.code.toByte(); w[2] = 'a'.code.toByte(); w[3] = 'C'.code.toByte()
        w[4] = 0x80.toByte()
        w[5] = 0; w[6] = 0; w[7] = 34
        var p = 8
        fun put(value: Long, bytes: Int) { for (b in bytes - 1 downTo 0) w[p++] = (value shr (8 * b)).toByte() }
        put(BLOCK.toLong(), 2); put(BLOCK.toLong(), 2)
        put(if (frames == 0) 0 else minFrame.toLong(), 3); put(maxFrame.toLong(), 3)
        // sample rate (20 bits), channels - 1 (3 bits), bits per sample - 1 (5 bits), total samples (36 bits)
        val packed = (sampleRate.toLong() shl 44) or (0L shl 41) or (15L shl 36) or sampleCount
        put(packed, 8)
        for (i in 0 until 16) w[p++] = 0
    }

    private fun putByte(b: Int) {
        if (len == out.size) {
            val bigger = out.copyOf(out.size * 2)
            out.fill(0)
            out = bigger
        }
        out[len++] = b.toByte()
    }

    private fun putFrameNumber(v: Int) {
        if (v < 0x80) { putByte(v); return }
        val extra = when { v < 0x800 -> 1; v < 0x10000 -> 2; v < 0x200000 -> 3; v < 0x4000000 -> 4; else -> 5 }
        putByte(((0xFF00 shr (extra + 1)) and 0xFF) or (v shr (6 * extra)))
        for (i in extra - 1 downTo 0) putByte(0x80 or ((v shr (6 * i)) and 0x3F))
    }

    private fun writeBits(value: Long, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (value and ((1L shl n) - 1))
        accBits += n
        while (accBits >= 8) {
            accBits -= 8
            putByte((acc ushr accBits).toInt() and 0xFF)
        }
    }

    private companion object {
        const val BLOCK = 4096
        const val HEADER_BYTES = 42
        const val INITIAL_CAPACITY = 1 shl 16
        const val MAX_ORDER = 4
        const val MAX_PARTITION_ORDER = 8
        const val MAX_PARTITIONS = 1 shl MAX_PARTITION_ORDER
        const val MAX_PARAM = 30
        const val MAX_PARAM_4BIT = 14
        const val OPEN = 0
        const val FINISHED = 1
        const val CLEARED = 2
        val EMPTY = ByteArray(0)

        val CRC8 = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
            c and 0xFF
        }
        val CRC16 = IntArray(256) { n ->
            var c = n shl 8
            repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
            c and 0xFFFF
        }

        fun crc8(b: ByteArray, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = CRC8[c xor (b[i].toInt() and 0xFF)]
            return c
        }

        fun crc16(b: ByteArray, from: Int, to: Int): Int {
            var c = 0
            for (i in from until to) c = ((c shl 8) and 0xFFFF) xor CRC16[(c shr 8) xor (b[i].toInt() and 0xFF)]
            return c
        }
    }
}
