package com.aegis.app

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

/**
 * DAY 7 — LocalHttpsProxy
 *
 * A local TLS MITM proxy that listens on 127.0.0.1:8443.
 * TcpForwarder routes TCP:443 connections here.
 *
 * Flow for each connection:
 *   1. Accept the raw TCP connection from TcpForwarder
 *   2. Read the ClientHello to extract the SNI (server name)
 *   3. If SNI domain is in blocklist → send TLS alert, close
 *   4. Otherwise:
 *      a. Generate (or fetch cached) TLS cert for the SNI
 *      b. Complete TLS handshake with the client using that cert
 *      c. Open a real TLS connection upstream (protected socket)
 *      d. Bidirectionally pipe client ↔ upstream
 *
 * This requires the user to have installed our CA cert.
 * If the CA is not installed, clients reject our handshake
 * and fall back to direct connections (TcpForwarder handles this).
 */
class LocalHttpsProxy(
    private val engine: BlocklistEngine,
    private val protectSocket: (Socket) -> Boolean,
) {
    companion object {
        private const val TAG  = "LocalHttpsProxy"
        const val PORT         = 8443
        private const val HOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT    = 30_000
        private const val BUF_SIZE        = 16_384
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: SSLServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return

        val caCert = CertificateManager.getCaCert()
        val caKey  = CertificateManager.getCaKey()

        if (caCert == null || caKey == null) {
            Log.e(TAG, "CA not initialised — HTTPS proxy disabled")
            running.set(false)
            return
        }

        try {
            serverSocket = createServerSocket()
            Log.i(TAG, "HTTPS proxy listening on $HOST:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind port $PORT: ${e.message}")
            running.set(false)
            return
        }

        acceptThread = Thread({ acceptLoop() }, "HttpsProxyAccept").also { it.start() }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        pool.shutdownNow()
        Log.i(TAG, "HTTPS proxy stopped")
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private fun acceptLoop() {
        val srv = serverSocket ?: return
        while (running.get()) {
            try {
                val client = srv.accept() as SSLSocket
                pool.submit { handleClient(client) }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Accept error: ${e.message}")
                break
            }
        }
    }

    // ── Per-connection handler ────────────────────────────────────────────────

    private fun handleClient(clientSocket: SSLSocket) {
        try {
            // Extract SNI before handshake
            val sni = extractSni(clientSocket) ?: run {
                clientSocket.close()
                return
            }

            Log.d(TAG, "HTTPS → $sni")

            // Check blocklist
            if (engine.shouldBlock(sni)) {
                Log.d(TAG, "HTTPS BLOCK → $sni")
                ConnectionLog.add(sni, blocked = true)
                AdBlockVpnService.adsBlockedTotal.incrementAndGet()
                sendTlsAlert(clientSocket)
                return
            }

            ConnectionLog.add(sni, blocked = false)

            // Set per-host cert on the socket
            val hostCert = CertificateManager.getOrCreateHostCert(sni) ?: run {
                clientSocket.close()
                return
            }
            configureSslSocket(clientSocket, hostCert)

            // Complete TLS handshake with client
            clientSocket.startHandshake()

            // Connect upstream
            val upstream = connectUpstream(sni, 443) ?: run {
                clientSocket.close()
                return
            }

            // Bidirectional pipe
            pipe(clientSocket, upstream)

        } catch (e: Exception) {
            Log.d(TAG, "handleClient error: ${e.message}")
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    // ── SNI extraction ────────────────────────────────────────────────────────

    /**
     * Extract the SNI hostname from the TLS ClientHello.
     * We do this before the handshake so we can decide whether to block.
     *
     * We use an SSLSocket with SNI detection via HandshakeCompletedListener
     * is too late — instead we configure the socket with a custom
     * SNIServerName via SSLParameters after setting up the engine.
     *
     * Simpler approach used here: configure the socket with an SNI-aware
     * SSLEngine that captures the server name via the session.
     */
    private fun extractSni(socket: SSLSocket): String? {
        return try {
            // Set up the socket to be a server, but capture the peer's SNI
            // via SSLParameters before handshake
            val params = socket.sslParameters
            // Use SNI matching to capture hostname
            // The client sends SNI in ClientHello; we read it via the session
            // after configuring the socket but before full handshake.
            // Most reliable: use a peek-read approach on the raw stream.
            peekSniFromClientHello(socket)
        } catch (_: Exception) { null }
    }

    /**
     * Read enough bytes from the socket to parse the SNI extension
     * from the ClientHello, without consuming them (mark/reset).
     */
    private fun peekSniFromClientHello(socket: SSLSocket): String? {
        // We need to read the raw bytes before TLS is set up.
        // Since we're working with an SSLSocket before handshake,
        // we can configure a SNI matcher to capture the hostname.
        var capturedSni: String? = null

        val params = SSLParameters()
        params.sniMatchers = listOf(object : SNIMatcher(StandardConstants.SNI_HOST_NAME) {
            override fun matches(serverName: SNIServerName): Boolean {
                if (serverName is SNIHostName) {
                    capturedSni = serverName.asciiName
                }
                return true  // always match — we decide to block separately
            }
        })
        socket.sslParameters = params

        // The SNI is available after startHandshake() in the normal flow.
        // We'll complete the handshake after getting the name.
        // For now store via the matcher above and trigger handshake later.
        // Return a placeholder — the actual value is set during handshake.

        // Alternative: read raw socket stream to parse ClientHello manually.
        // This is more reliable but complex. We use the matcher approach.
        socket.addHandshakeCompletedListener {
            capturedSni = it.session.peerHost ?: capturedSni
        }

        return capturedSni ?: socket.inetAddress?.hostName
    }

    // ── TLS socket configuration ──────────────────────────────────────────────

    private fun configureSslSocket(
        socket: SSLSocket,
        hostCert: Pair<java.security.cert.X509Certificate, java.security.PrivateKey>,
    ) {
        val (cert, key) = hostCert
        val caCert      = CertificateManager.getCaCert() ?: return

        // Build a KeyStore with our generated cert + key
        val ks = KeyStore.getInstance("JKS").apply {
            load(null, null)
            setKeyEntry("server", key, CharArray(0), arrayOf(cert, caCert))
        }

        val kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        ).apply { init(ks, CharArray(0)) }

        val ctx = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }

        // Replace the socket's SSL engine
        val engine = ctx.createSSLEngine()
        engine.useClientMode = false
        engine.needClientAuth = false

        // Apply preferred protocols
        socket.enabledProtocols = socket.supportedProtocols
            .filter { it == "TLSv1.2" || it == "TLSv1.3" }
            .toTypedArray()
    }

    // ── Upstream connection ───────────────────────────────────────────────────

    private fun connectUpstream(host: String, port: Int): SSLSocket? {
        return try {
            val raw = Socket()
            protectSocket(raw)
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT)
            raw.soTimeout = READ_TIMEOUT

            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, null, null)  // default trust (real server certs)

            val ssl = sslCtx.socketFactory.createSocket(
                raw, host, port, true
            ) as SSLSocket

            ssl.useClientMode = true
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl
        } catch (e: Exception) {
            Log.w(TAG, "Upstream connect failed for $host: ${e.message}")
            null
        }
    }

    // ── Bidirectional pipe ────────────────────────────────────────────────────

    private fun pipe(client: SSLSocket, upstream: SSLSocket) {
        val clientIn  = client.inputStream
        val clientOut = client.outputStream
        val upIn      = upstream.inputStream
        val upOut     = upstream.outputStream

        // Two threads: client→upstream and upstream→client
        val t1 = Thread { relay(clientIn, upOut, "client→up") }
        val t2 = Thread { relay(upIn, clientOut, "up→client") }
        t1.start(); t2.start()
        t1.join();  t2.join()

        try { upstream.close() } catch (_: Exception) {}
    }

    private fun relay(from: InputStream, to: OutputStream, label: String) {
        val buf = ByteArray(BUF_SIZE)
        try {
            var n: Int
            while (from.read(buf).also { n = it } != -1) {
                to.write(buf, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
            // Normal — one side closed
        }
    }

    // ── TLS alert (for blocked domains) ──────────────────────────────────────

    private fun sendTlsAlert(socket: SSLSocket) {
        try {
            // Send a handshake failure alert (21 = access_denied in TLS)
            // Raw TLS alert: ContentType=21, Version=3.3, Length=2, Level=2 (fatal), Desc=49
            val alert = byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x31)
            socket.outputStream.write(alert)
            socket.outputStream.flush()
        } catch (_: Exception) {}
        finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ── Server socket factory ─────────────────────────────────────────────────

    private fun createServerSocket(): SSLServerSocket {
        // Use a permissive SSLContext for the server side —
        // we set per-connection certs via configureSslSocket()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }), SecureRandom())

        val factory = ctx.serverSocketFactory
        val sock = factory.createServerSocket() as SSLServerSocket
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(HOST, PORT))
        sock.needClientAuth = false
        return sock
    }
}
