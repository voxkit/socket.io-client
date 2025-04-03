package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.socketio.client.util.argsOf
import io.voxkit.socketio.client.util.bytesOrNull
import io.voxkit.socketio.client.util.decodeJsonOrNull
import io.voxkit.socketio.client.util.jsonElement
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
import kotlin.test.assertContentEquals
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
        val socket = socket()

        connectSocket(socket)

        assertTrue(socket.connected, "Socket should be connected to the default namespace")

        socket.close()
    }

    @Test
    fun testTwoSocketsWithSameNamespace() = runTest(timeout = timeout) {
        val socket1 = socket()
        val socket2 = socket()

        connectSocket(socket1)
        connectSocket(socket2)

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")

        socket1.close()
        socket2.close()
    }

    @Test
    fun testTwoSocketsWithSameNamespaceAndDifferentQueryStrings() = runTest(timeout = timeout) {
        val socket1 = socket(queryString = "param1=value1")
        val socket2 = socket(queryString = "param2=value2")

        connectSocket(socket1)
        connectSocket(socket2)

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")

        socket1.close()
        socket2.close()
    }

    @Test
    fun testSendAck() = runTest(timeout = timeout) {
        val socket = socket()

        launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = socket.on("ack").first()
            ev.ack?.invoke(*argsOf(5, mapOf("test" to true)))
        }
        val ackBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.on("ackBack").first() }

        connectSocket(socket)
        socket.send("callAck")

        val ackBack = ackBackDeferred.await()

        assertIs<Socket.Event.Custom>(ackBack)
        assertEquals(5, ackBack.args[0].decodeJsonOrNull<Int>())
        assertEquals(JsonObject(mapOf("test" to JsonPrimitive(true))), ackBack.args[1].jsonElement)

        socket.close()
    }

    @Test
    fun testReceiveDateWithAck() = runTest(timeout = timeout) {
        val socket = socket()
        connectSocket(socket)

        val ack = socket.sendWithAck("getAckDate", *argsOf(mapOf("test" to true)))

        val dateString = ack[0].decodeJsonOrNull<String>()
        val date = dateString?.runCatching { Instant.parse(this) }?.getOrNull()
        assertNotNull(dateString, "Date string should not be null")
        assertNotNull(date, "Date string should be a valid date")

        socket.close()
    }

    @Test
    fun testSendBinaryAck() = runTest(timeout = timeout) {
        val buf = "huehue".encodeToByteArray()
        val socket = socket()

        launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = socket.on("ack").first()
            ev.ack?.invoke(*argsOf(buf))
        }
        val ackBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.on("ackBack").first() }

        connectSocket(socket)
        socket.send("callAckBinary")

        val binaryAckBack = ackBackDeferred.await()

        assertIs<Socket.Event.Custom>(binaryAckBack)
        assertContentEquals(buf, binaryAckBack.args[0].bytesOrNull, "Binary ack should be equal to the sent buffer")

        socket.close()
    }

    @Test
    fun testReceiveBinaryAck() = runTest(timeout = timeout) {
        val buf = "huehue".encodeToByteArray()
        val socket = socket()
        connectSocket(socket)

        val binaryAck = socket.sendWithAck("getAckBinary", *argsOf(""))

        assertContentEquals(buf, binaryAck[0].bytesOrNull, "Binary ack should be equal to the sent buffer")

        socket.close()
    }

    @Test
    fun testWorkingWithFalse() = runTest(timeout = timeout) {
        val socket = socket()
        val echoBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.on("echoBack").first() }

        connectSocket(socket)
        socket.send("echo", *argsOf(false))

        val echoBack = echoBackDeferred.await()
        assertEquals(false, echoBack.args[0].decodeJsonOrNull<Boolean>())

        socket.close()
    }

    private suspend fun socket(namespace: String = "/", queryString: String  = ""): Socket {
        return withContext(Dispatchers.Default) { io.socket("http://localhost:3000$namespace?$queryString") }
    }

    private suspend fun connectSocket(socket: Socket) = withContext(Dispatchers.Default) {
        socket.connect()
    }
}
