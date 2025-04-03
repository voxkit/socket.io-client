package io.voxkit.client

import io.voxkit.engineio.client.engineIO
import io.voxkit.engineio.client.ioHttpClient
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.parser.Packet
import io.voxkit.socketio.logging.LoggingLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineTest {
    private val serverPort = 3000
    private val httpClient = ioHttpClient()

    @Test
    fun testConnectToLocalhost() = runTest(timeout = TIMEOUT) {
        val engine = backgroundScope.engineIO(httpClient) {
            port = serverPort
            loggingLevel = LoggingLevel.DEBUG
        }

        val packet = engine.incoming.receive()

        engine.close()

        assertEquals(Packet.Message("hi"), packet)
    }

    @Test
    fun receiveEmoji() = runTest(timeout = TIMEOUT) {
        val engine = backgroundScope.engineIO(httpClient) {
            port = serverPort
            loggingLevel = LoggingLevel.DEBUG
        }

        val packetDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            engine.incoming.receive() // skip "hi" message
            engine.incoming.receive()
        }

        engine.send("\uD800\uDC00-\uDB7F\uDFFF\uDB80\uDC00-\uDBFF\uDFFF\uE000-\uF8FF")
        val packet = packetDeferred.await()
        engine.close()

        assertEquals(Packet.Message("\uD800\uDC00-\uDB7F\uDFFF\uDB80\uDC00-\uDBFF\uDFFF\uE000-\uF8FF"), packet)
    }

    @Test
    fun testPollingWithHeaders() = runTest(timeout = TIMEOUT) {
        val session = backgroundScope.engineIO(httpClient) {
            port = serverPort
            headers.append("X-EngineIO", "bar")
            transports = setOf(TransportType.POLLING)
            loggingLevel = LoggingLevel.DEBUG
        }

        val responseHeaders = session.call.filterNotNull().first().response.headers
        session.close()

        assertEquals(listOf("hi", "bar"), responseHeaders?.getAll("X-EngineIO"))
    }

    @Test
    fun testWebSocketWithHeaders() = runTest(timeout = TIMEOUT) {
        val session = backgroundScope.engineIO(httpClient) {
            port = serverPort
            headers.append("X-EngineIO", "bar")
            transports = setOf(TransportType.WEBSOCKET)
            loggingLevel = LoggingLevel.DEBUG
        }

        val responseHeaders = session.call.filterNotNull().first().response.headers
        session.close()

        assertEquals(listOf("hi", "bar"), responseHeaders?.getAll("X-EngineIO"))
    }
}
