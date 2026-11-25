package com.zapp.net.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Minimal SOCKS5 client, enough for a CONNECT to a host:port.
 *
 * Ported shape from the reference `tor_manager.open_socks5_tunnel` (src/tor_manager.py):
 * greet, offer only "no auth", demand a success reply, then read the address type and
 * discard exactly as many bytes as the reply says it carries. Anything else is a failure,
 * because a half-read reply would corrupt the TLS stream that follows.
 */
object Socks5 {

    /**
     * Dial `host:port` through the SOCKS5 listener at `socksHost:socksPort`.
     *
     * @return the connected socket, already past the handshake, ready for TLS bytes.
     */
    @Throws(IOException::class)
    fun connect(
        socksHost: String,
        socksPort: Int,
        host: String,
        port: Int,
        timeoutMs: Int = 8_000,
    ): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            val input = s.getInputStream()

            // greeting: version 5, one method offered, method 0 (no auth)
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = ByteArray(2)
            readFully(input, greet)
            if (greet[0] != 0x05.toByte()) throw IOException("socks: not v5")
            if (greet[1] != 0x00.toByte()) throw IOException("socks: auth method refused (${greet[1]})")

            // CONNECT with a domain name: the proxy (Tor) resolves it, never us
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            if (hostBytes.size > 255) throw IOException("socks: host too long")
            val req = ByteArray(5 + hostBytes.size + 2)
            req[0] = 0x05           // version
            req[1] = 0x01           // CONNECT
            req[2] = 0x00           // reserved
            req[3] = 0x03           // address type: domain
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = ((port ushr 8) and 0xFF).toByte()
            req[6 + hostBytes.size] = (port and 0xFF).toByte()
            out.write(req)
            out.flush()

            val head = ByteArray(4)
            readFully(input, head)
            if (head[0] != 0x05.toByte()) throw IOException("socks: bad reply version")
            if (head[1] != 0x00.toByte()) throw IOException("socks: connect failed (${head[1]})")
            when (head[3].toInt() and 0xFF) {
                0x01 -> readFully(input, ByteArray(4))          // IPv4
                0x04 -> readFully(input, ByteArray(16))         // IPv6
                0x03 -> {
                    val len = ByteArray(1)
                    readFully(input, len)
                    readFully(input, ByteArray(len[0].toInt() and 0xFF))
                }
                else -> throw IOException("socks: bad address type")
            }
            readFully(input, ByteArray(2))                       // port
            s.soTimeout = 0
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw if (e is IOException) e else IOException(e)
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) throw IOException("socks: eof after $off/${buf.size} bytes")
            off += n
        }
    }
}
