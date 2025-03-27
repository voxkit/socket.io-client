package io.voxkit.client

import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.engineio.client.engineIOSession
import io.voxkit.engineio.client.transports.TransportType
import io.voxkit.engineio.parser.Packet
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineIOSessionTest {
    private val httpClient = engineIOHttpClient()
    private val serverPort = 3000

    @Test
    fun testConnectToLocalhost() = runTest {
        val session = httpClient.engineIOSession {
            port = serverPort
        }

        val packet = session.incoming.receive()
        session.close()

        assertEquals(Packet.Message("hi"), packet)
    }

    @Test
    fun receiveEmoji() = runTest {
        val session = httpClient.engineIOSession {
            port = serverPort
        }

        session.send("\uD800\uDC00-\uDB7F\uDFFF\uDB80\uDC00-\uDBFF\uDFFF\uE000-\uF8FF")
        session.incoming.receive() // skip "hi" message
        val packet = session.incoming.receive()
        session.close()

        assertEquals(Packet.Message("\uD800\uDC00-\uDB7F\uDFFF\uDB80\uDC00-\uDBFF\uDFFF\uE000-\uF8FF"), packet)
    }

    @Test
    fun testPollingWithHeaders() = runTest {
        val session = httpClient.engineIOSession {
            port = serverPort
            headers.append("X-EngineIO", "bar")
            transports = setOf(TransportType.POLLING)
        }

        val responseHeaders = session.call.value?.response?.headers
        session.close()

        assertEquals(listOf("hi", "bar"), responseHeaders?.getAll("X-EngineIO"))
    }

    @Test
    fun testWebSocketWithHeaders() = runTest {
        val session = httpClient.engineIOSession {
            port = serverPort
            headers.append("X-EngineIO", "bar")
            transports = setOf(TransportType.WEBSOCKET)
        }

        val responseHeaders = session.call.value?.response?.headers
        session.close()

        assertEquals(listOf("hi", "bar"), responseHeaders?.getAll("X-EngineIO"))
    }
}
