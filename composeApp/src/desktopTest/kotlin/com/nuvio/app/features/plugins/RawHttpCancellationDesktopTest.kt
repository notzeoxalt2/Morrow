package com.nuvio.app.features.plugins

import com.nuvio.app.features.addons.httpRequestRaw
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawHttpCancellationDesktopTest {
    @Test fun cancelledRequestDoesNotWaitForSlowResponseHeaders() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try { Thread.sleep(2500); exchange.sendResponseHeaders(200, -1) }
            finally { exchange.close() }
        }
        server.start()
        try {
            val start = System.nanoTime()
            val result = withTimeoutOrNull(150) {
                httpRequestRaw("GET", "http://127.0.0.1:${server.address.port}/", emptyMap(), "")
            }
            assertNull(result)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "Cancellation waited for the upstream response")
        } finally { server.stop(0) }
    }
}
