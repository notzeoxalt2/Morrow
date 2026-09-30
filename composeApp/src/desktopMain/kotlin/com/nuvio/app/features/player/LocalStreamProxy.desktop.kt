package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

actual object LocalStreamProxy {
    private val log = Logger.withTag("LocalStreamProxy")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Volatile
    private var isStarted: Boolean = false

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    actual fun start(): Int {
        if (isStarted && boundPort > 0) return boundPort
        synchronized(this) {
            if (isStarted && boundPort > 0) return boundPort
            try {
                val ss = ServerSocket(0, 100, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                boundPort = ss.localPort
                isStarted = true
                log.i { "LocalStreamProxy active on 127.0.0.1:$boundPort" }
                scope.launch { acceptLoop(ss) }
            } catch (e: Throwable) {
                log.e(e) { "Failed to start LocalStreamProxy" }
            }
        }
        return boundPort
    }

    actual fun getPort(): Int = boundPort

    actual fun isRunning(): Boolean = isStarted && boundPort > 0 && serverSocket?.isClosed == false

    actual fun wrapUrl(targetUrl: String, headers: Map<String, String>?): String {
        if (headers.isNullOrEmpty() || targetUrl.isBlank()) return targetUrl
        if (targetUrl.startsWith("http://127.0.0.1:") || targetUrl.startsWith("http://localhost:")) return targetUrl
        val port = start()
        if (port <= 0) return targetUrl

        val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")
        val encodedHeaders = encodeHeaders(headers)
        return "http://127.0.0.1:$port/stream?url=$encodedUrl&h=$encodedHeaders"
    }

    private fun encodeHeaders(headers: Map<String, String>): String {
        val raw = headers.entries.joinToString(";;") { "${it.key}::${it.value}" }
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var bitCount = 0
        var bits = 0
        for (b in bytes) {
            bits = (bits shl 8) or (b.toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= 6) {
                bitCount -= 6
                sb.append(table[(bits ushr bitCount) and 0x3F])
            }
        }
        if (bitCount > 0) {
            sb.append(table[(bits shl (6 - bitCount)) and 0x3F])
        }
        return sb.toString().replace("+", "-").replace("/", "_")
    }

    private fun decodeHeaders(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        try {
            val clean = encoded.replace("-", "+").replace("_", "/")
            val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val bytes = ArrayList<Byte>()
            var bits = 0
            var bitCount = 0
            for (char in clean) {
                val idx = table.indexOf(char)
                if (idx >= 0) {
                    bits = (bits shl 6) or idx
                    bitCount += 6
                    if (bitCount >= 8) {
                        bitCount -= 8
                        bytes.add(((bits ushr bitCount) and 0xFF).toByte())
                    }
                }
            }
            val raw = bytes.toByteArray().decodeToString()
            return raw.split(";;").mapNotNull { entry ->
                val parts = entry.split("::", limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
            }.toMap()
        } catch (_: Throwable) {
            return emptyMap()
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) = withContext(Dispatchers.IO) {
        while (!ss.isClosed) {
            try {
                val socket = ss.accept()
                launch { handleClient(socket) }
            } catch (_: Throwable) {
                if (ss.isClosed) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val firstLine = reader.readLine() ?: return

                val parts = firstLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val pathAndQuery = parts[1]

                // Read client headers (e.g. Range)
                var rangeHeader: String? = null
                var headerLine: String?
                while (reader.readLine().also { headerLine = it } != null) {
                    if (headerLine.isNullOrBlank()) break
                    val hl = headerLine ?: break
                    if (hl.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = hl.substringAfter(":").trim()
                    }
                }

                if (pathAndQuery.startsWith("/ping")) {
                    val body = "OK"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n$body"
                    s.getOutputStream().write(resp.toByteArray(StandardCharsets.UTF_8))
                    s.getOutputStream().flush()
                    return
                }

                if (!pathAndQuery.startsWith("/stream?")) {
                    val notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(notFound.toByteArray(StandardCharsets.UTF_8))
                    s.getOutputStream().flush()
                    return
                }

                val query = pathAndQuery.substringAfter("/stream?")
                var targetUrl = ""
                var encodedHeaders = ""
                query.split("&").forEach { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2) {
                        if (kv[0] == "url") {
                            targetUrl = runCatching { URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1])
                        } else if (kv[0] == "h") {
                            encodedHeaders = kv[1]
                        }
                    }
                }

                if (targetUrl.isBlank()) {
                    val badReq = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    s.getOutputStream().write(badReq.toByteArray(StandardCharsets.UTF_8))
                    s.getOutputStream().flush()
                    return
                }

                val customHeaders = decodeHeaders(encodedHeaders)
                proxyRequest(s, method, targetUrl, customHeaders, rangeHeader)
            }
        } catch (_: Throwable) {
            // Client closed connection (normal during seek / stop)
        }
    }

    private fun proxyRequest(
        socket: Socket,
        method: String,
        targetUrl: String,
        headers: Map<String, String>,
        rangeHeader: String?,
    ) {
        val reqBuilder = Request.Builder().url(targetUrl)
        if (method.equals("HEAD", ignoreCase = true)) {
            reqBuilder.head()
        } else {
            reqBuilder.get()
        }

        headers.forEach { (name, value) ->
            reqBuilder.header(name, value)
        }
        if (!rangeHeader.isNullOrBlank()) {
            reqBuilder.header("Range", rangeHeader)
        }

        val upstreamResp = httpClient.newCall(reqBuilder.build()).execute()
        val code = upstreamResp.code
        val message = upstreamResp.message.ifBlank { "OK" }
        val contentType = upstreamResp.header("Content-Type", "").orEmpty()

        val isM3u8 = contentType.contains("mpegurl", ignoreCase = true) ||
            targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)

        val out = socket.getOutputStream()

        if (isM3u8 && code in 200..299 && !method.equals("HEAD", ignoreCase = true)) {
            val bodyText = upstreamResp.body?.string().orEmpty()
            val rewritten = rewriteM3u8(bodyText, targetUrl, headers)
            val bodyBytes = rewritten.toByteArray(StandardCharsets.UTF_8)

            val head = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(StandardCharsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
        } else {
            val statusLine = "HTTP/1.1 $code $message\r\n"
            val sb = StringBuilder(statusLine)
            upstreamResp.headers.forEach { (name, value) ->
                if (!name.equals("Transfer-Encoding", ignoreCase = true) &&
                    !name.equals("Connection", ignoreCase = true)) {
                    sb.append("$name: $value\r\n")
                }
            }
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Access-Control-Allow-Headers: *\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
            out.flush()

            if (!method.equals("HEAD", ignoreCase = true)) {
                upstreamResp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                    }
                    out.flush()
                }
            }
        }
    }

    private fun rewriteM3u8(
        content: String,
        baseUrl: String,
        headers: Map<String, String>,
    ): String {
        val lines = content.lines()
        val sb = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> sb.append("\n")
                trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val rewritten = Regex("""URI=["']([^"']+)["']""").replace(trimmed) { mr ->
                        val keyUri = mr.groupValues[1]
                        val resolved = resolveRelativeUrl(baseUrl, keyUri)
                        """URI="${wrapUrl(resolved, headers)}""""
                    }
                    sb.append(rewritten).append("\n")
                }
                trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val rewritten = Regex("""URI=["']([^"']+)["']""").replace(trimmed) { mr ->
                        val mapUri = mr.groupValues[1]
                        val resolved = resolveRelativeUrl(baseUrl, mapUri)
                        """URI="${wrapUrl(resolved, headers)}""""
                    }
                    sb.append(rewritten).append("\n")
                }
                trimmed.startsWith("#") -> {
                    sb.append(trimmed).append("\n")
                }
                else -> {
                    val resolved = resolveRelativeUrl(baseUrl, trimmed)
                    sb.append(wrapUrl(resolved, headers)).append("\n")
                }
            }
        }
        return sb.toString()
    }

    private fun resolveRelativeUrl(baseUrl: String, rel: String): String {
        if (rel.startsWith("http://") || rel.startsWith("https://")) return rel
        return try {
            URI(baseUrl).resolve(rel).toString()
        } catch (_: Throwable) {
            val baseDir = baseUrl.substringBeforeLast("/") + "/"
            baseDir + rel.removePrefix("/")
        }
    }
}
