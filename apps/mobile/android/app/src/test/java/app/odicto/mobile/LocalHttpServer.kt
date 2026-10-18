package app.odicto.mobile

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** A one-reply HTTP/1.1 server on 127.0.0.1 that keeps connections alive and counts them. */
class LocalHttpServer(private val reply: String) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val connections = AtomicInteger()

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                connections.incrementAndGet()
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) = socket.use {
        val input = BufferedInputStream(it.getInputStream())
        val output = it.getOutputStream()
        try {
            while (true) {
                val head = readHead(input) ?: return
                val length = head.lineSequence().firstOrNull { line -> line.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt() ?: 0
                repeat(length) { if (input.read() < 0) return }
                val body = reply.toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
                output.flush()
            }
        } catch (_: IOException) {}
    }

    private fun readHead(input: BufferedInputStream): String? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val next = input.read()
            if (next < 0) return null
            head.write(next)
            matched = if (next == "\r\n\r\n"[matched].code) matched + 1 else if (next == '\r'.code) 1 else 0
        }
        return head.toString(Charsets.US_ASCII.name())
    }

    override fun close() = server.close()
}
