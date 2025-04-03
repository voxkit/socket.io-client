package io.voxkit.client

import io.voxkit.engineio.client.EngineOptionsBuilder
import io.voxkit.engineio.client.transports.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineOptionsBuilderTest {
    @Test
    fun testWithHttpUrl() {
        val builder = EngineOptionsBuilder("http://localhost:8080/socket.io/?foo=bar")

        assertEquals(false, builder.secure)
        assertEquals("localhost", builder.host)
        assertEquals(8080, builder.port)
        assertEquals("/socket.io/", builder.path)
        assertEquals(setOf(TransportType.POLLING, TransportType.WEBSOCKET), builder.transports)
        assertEquals("bar", builder.parameters["foo"])
    }

    @Test
    fun testWithHttpsUrl() {
        val builder = EngineOptionsBuilder("https://localhost:8080/socket.io/?foo=bar")

        assertEquals(true, builder.secure)
        assertEquals("localhost", builder.host)
        assertEquals(8080, builder.port)
        assertEquals("/socket.io/", builder.path)
        assertEquals(setOf(TransportType.POLLING, TransportType.WEBSOCKET), builder.transports)
        assertEquals("bar", builder.parameters["foo"])
    }

    @Test
    fun testWithWSUrl() {
        val builder = EngineOptionsBuilder("ws://localhost:8080/socket.io/?foo=bar")

        assertEquals(false, builder.secure)
        assertEquals("localhost", builder.host)
        assertEquals(8080, builder.port)
        assertEquals("/socket.io/", builder.path)
        assertEquals(setOf(TransportType.WEBSOCKET), builder.transports)
        assertEquals("bar", builder.parameters["foo"])
    }

    @Test
    fun testWithWssUrl() {
        val builder = EngineOptionsBuilder("wss://localhost:8080/socket.io/?foo=bar")

        assertEquals(true, builder.secure)
        assertEquals("localhost", builder.host)
        assertEquals(8080, builder.port)
        assertEquals("/socket.io/", builder.path)
        assertEquals(setOf(TransportType.WEBSOCKET), builder.transports)
        assertEquals("bar", builder.parameters["foo"])
    }
}
