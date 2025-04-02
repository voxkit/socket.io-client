package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.socketio.client.util.argsOf
import io.voxkit.socketio.client.util.jsonElement
import io.voxkit.socketio.client.util.stringOrNull
import io.voxkit.socketio.logging.LoggingLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ConnectionTest {
    private val timeout = 10.seconds
    private lateinit var io: IO
    private lateinit var httpClient: HttpClient

    @BeforeTest
    fun setup() {
        httpClient = engineIOHttpClient {
            install(Logging) {
                level = LogLevel.INFO
            }
        }

        io = httpClient.IO {
            dispatcher = Dispatchers.IO
            loggingLevel = LoggingLevel.DEBUG
        }
    }

    @AfterTest
    fun tearDown() {
        io.close()
        httpClient.close()
    }

    @Test
    fun testConnectToDefaultNamespace() = runTest(timeout = timeout) {
        val socket = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }

        assertTrue(socket.connected, "Socket should be connected to the default namespace")
    }

    @Test
    fun testTwoSocketsWithSameNamespace() = runTest(timeout = timeout) {
        val socket1 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }
        val socket2 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")
    }

    @Test
    fun testTwoSocketsWithSameNamespaceAndDifferentQueryStrings() = runTest(timeout = timeout) {
        val socket1 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/?param1=value1") }
        val socket2 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/?param2=value2") }

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")
    }

    @Test
    fun testSendAck() = runTest(timeout = timeout) {
        val socket = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }

        launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = socket.on("ack").first()
            ev.ack?.invoke(*argsOf(5, mapOf("test" to true)))
        }
        val ackBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.on("ackBack").first() }
        socket.send("callAck")

        val ackBack = ackBackDeferred.await()

        assertIs<Socket.Event.Custom>(ackBack)
        assertEquals(JsonPrimitive(5), ackBack.args[0].jsonElement)
        assertEquals(JsonObject(mapOf("test" to JsonPrimitive(true))), ackBack.args[1].jsonElement)
    }

    @Test
    fun testReceiveDateWithAck() = runTest(timeout = timeout) {
        val socket = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }

        val ack = socket.sendWithAck("getAckDate", *argsOf(mapOf("test" to true)))

        val dateString = ack[0].jsonElement.stringOrNull
        val date = dateString?.runCatching { Instant.parse(this) }?.getOrNull()
        assertNotNull(dateString, "Date string should not be null")
        assertNotNull(date, "Date string should be a valid date")
    }
}
