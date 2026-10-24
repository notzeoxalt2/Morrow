package com.nuvio.app.features.plugins

import com.nuvio.app.features.plugins.runtime.PluginRuntime
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginFetchTimeoutDesktopTest {
    @Test fun callerCanChooseABoundedFetchDeadline() = runBlocking {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val executor=Executors.newCachedThreadPool()
        server.executor=executor
        server.createContext("/") { exchange ->
            try {
                Thread.sleep(1500)
                val body="ok".toByteArray()
                exchange.sendResponseHeaders(200,body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            for ((timeout,expected) in listOf(1000 to "0",3000 to "200")) {
                val streams=PluginRuntime.executePlugin(
                    code="""module.exports.getStreams=async function(){
                        const r=await fetch('http://127.0.0.1:${server.address.port}/slow',{timeoutMs:$timeout});
                        return [{url:'https://example.test/status',title:String(r.status)}];
                    };""",tmdbId="20",mediaType="tv",season=1,episode=1,scraperId="fetch-deadline-fixture")
                assertEquals(expected,streams.single().title)
            }
        } finally { server.stop(0);executor.shutdownNow() }
    }
}
