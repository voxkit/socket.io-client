package io.voxkit.socketio.client

import io.ktor.client.*
import io.voxkit.engineio.client.ioHttpClient
import io.voxkit.socketio.client.util.argsOf
import io.voxkit.socketio.client.util.bytesOrNull
import io.voxkit.socketio.client.util.decodeJsonOrNull
import io.voxkit.socketio.client.util.jsonElement
import io.voxkit.socketio.logging.LoggingLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SocketTest {
    private val serverPort = 3000
    private val serverUrl = "http://localhost:$serverPort"
    private val timeout = 10.seconds

    private lateinit var httpClient: HttpClient

    @BeforeTest
    fun setup() {
        httpClient = ioHttpClient()
    }

    @AfterTest
    fun tearDown() {
        httpClient.close()
    }

    @Test
    fun testConnectToDefaultNamespace() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket = io.socket()

        socket.connect()

        assertTrue(socket.connected, "Socket should be connected to the default namespace")

        socket.disconnect()

        io.close()
    }

    @Test
    fun testTwoSocketsWithSameNamespace() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket1 = io.socket()
        val socket2 = io.socket()

        socket1.connect()
        socket2.connect()

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")

        socket1.disconnect()
        socket2.disconnect()

        io.close()
    }

    @Test
    fun testTwoSocketsWithSameNamespaceAndDifferentQueryStrings() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket1 = io.socket(queryString = "param1=value1")
        val socket2 = io.socket(queryString = "param2=value2")

        socket1.connect()
        socket2.connect()

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")

        socket1.disconnect()
        socket2.disconnect()

        io.close()
    }

    @Test
    fun testSendAck() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket = io.socket()

        launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = socket.on("ack").first()
            ev.ack?.invoke(*argsOf(5, mapOf("test" to true)))
        }
        val ackBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.once("ackBack") }

        socket.connect()
        socket.send("callAck")

        val ackBack = ackBackDeferred.await()

        assertIs<Socket.Event.Custom>(ackBack)
        assertEquals(5, ackBack.args[0].decodeJsonOrNull<Int>())
        assertEquals(JsonObject(mapOf("test" to JsonPrimitive(true))), ackBack.args[1].jsonElement)

        socket.disconnect()

        io.close()
    }

    @Test
    fun testReceiveDateWithAck() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket = io.socket()

        socket.connect()

        // use Dispatchers.Default for realtime timeout
        val ack = withContext(Dispatchers.Default) {
            socket.sendWithAck("getAckDate", *argsOf(mapOf("test" to true)))
        }

        val dateString = ack[0].decodeJsonOrNull<String>()
        val date = dateString?.runCatching { Instant.parse(this) }?.getOrNull()
        assertNotNull(dateString, "Date string should not be null")
        assertNotNull(date, "Date string should be a valid date")

        socket.disconnect()

        io.close()
    }

    @Test
    fun testSendBinaryAck() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val buf = "huehue".encodeToByteArray()
        val socket = io.socket()

        launch(start = CoroutineStart.UNDISPATCHED) {
            val ev = socket.once("ack")
            ev.ack?.invoke(*argsOf(buf))
        }
        val ackBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.once("ackBack") }

        socket.connect()
        socket.send("callAckBinary")

        val binaryAckBack = ackBackDeferred.await()

        assertIs<Socket.Event.Custom>(binaryAckBack)
        assertContentEquals(buf, binaryAckBack.args[0].bytesOrNull, "Binary ack should be equal to the sent buffer")

        socket.disconnect()

        io.close()
    }

    @Test
    fun testReceiveBinaryAck() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val buf = "huehue".encodeToByteArray()
        val socket = io.socket()
        socket.connect()

        // use Dispatchers.Default for realtime timeout
        val binaryAck = withContext(Dispatchers.Default) {
            socket.sendWithAck("getAckBinary", *argsOf(""))
        }

        assertContentEquals(buf, binaryAck[0].bytesOrNull, "Binary ack should be equal to the sent buffer")

        socket.disconnect()

        io.close()
    }

    @Test
    fun testWorkingWithFalse() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket = io.socket()
        val echoBackDeferred = async(start = CoroutineStart.UNDISPATCHED) { socket.once("echoBack") }

        socket.connect()
        socket.send("echo", *argsOf(false))

        val echoBack = echoBackDeferred.await()
        assertEquals(false, echoBack.args[0].decodeJsonOrNull<Boolean>())

        socket.disconnect()

        io.close()
    }

    @Test
    fun testReceiveUtf8MultibyteCharacters() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val expected = listOf(
            "てすと",
            "Я Б Г Д Ж Й",
            "Ä ä Ü ü ß",
            "utf8 — string",
            "utf8 — string",
        )
        val socket = io.socket()

        val echoBackDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            socket.on("echoBack").take(expected.size).toList()
        }

        socket.connect()
        expected.forEach { str -> socket.send("echo", *argsOf(str)) }

        val echoBacks = echoBackDeferred.await()
        assertEquals(expected, echoBacks.map { it.args[0].decodeJsonOrNull<String>() })

        socket.disconnect()
        io.close()
    }

    @Test
    fun testConnectToNamespaceAfterConnectionEstablished() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.once<Socket.Event.Connect>()
            val foo = io.socket("/foo")
            foo.connect()
            foo.disconnect()
            socket.disconnect()
        }

        socket.connect()

        job.join()
        io.close()
    }

    @Test
    fun testConnectToNamespaceAfterConnectionGetsClosed() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket = io.socket()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.once<Socket.Event.Disconnect>()
            val foo = io.socket("/foo")
            foo.connect()
            foo.disconnect()
        }

        launch {
            socket.connect()
            socket.disconnect()
        }

        job.join()
        io.close()
    }

    @Test
    fun testReconnectByDefault() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.Reconnect>().first()
        }

        socket.connect()
        withContext(Dispatchers.Default) { delay(500.milliseconds) }

        (socket.io as VKManager).engine.value?.close()

        job.join()
        io.close()
    }

    @Test
    fun testReconnectManually() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.once<Socket.Event.Disconnect>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                socket.once<Socket.Event.Connect>()
            }

            socket.connect()
            job.join()
        }

        socket.connect()
        socket.disconnect()

        job.join()
        io.close()
    }

    @Test
    fun testReconnectAutomaticallyAfterReconnectingManually() = runTest(timeout = timeout) {
        val io = io(httpClient)
        val socket = io.socket()

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.once<Socket.Event.Disconnect>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                socket.io.events.filterIsInstance<Manager.Event.Reconnect>().first()
            }

            socket.connect()
            withContext(Dispatchers.Default) { delay(500) }
            (socket.io as VKManager).engine.value?.close()
            job.join()
        }

        socket.connect()
        socket.disconnect()

        job.join()
        io.close()
    }

    @Test
    fun testAttemptReconnectsAfterAFailedReconnect() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket("/timeout") {
            timeout = ZERO
            reconnectionAttempts = 2
            reconnectionDelay = 10.milliseconds
        }

        val reconnectAttempts = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().collect {
                reconnectAttempts += it.attempt
            }
        }

        val throwable = assertFails { socket.connect() }
        assertIs<TimeoutCancellationException>(throwable)
        assertEquals(listOf(1, 2), reconnectAttempts)

        job.cancel()
        io.close()
    }

    @Test
    fun testReconnectDelayShouldIncreaseEveryTime() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket("/timeout") {
            timeout = ZERO
            reconnectionAttempts = 3
            reconnectionDelay = 100.milliseconds
            randomizationFactor = 0.2
            autoConnect = false
        }

        var reconnects = 0
        var increasingDelay = false
        var startTime = Instant.DISTANT_PAST
        var prevDelay = Duration.ZERO
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            launch {
                socket.io.events.filterIsInstance<Manager.Event.Error>().collect {
                    startTime = Clock.System.now()
                }
            }
            launch {
                socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().collect {
                    reconnects++
                    val curDelay = Clock.System.now() - startTime
                    increasingDelay = curDelay > prevDelay
                    prevDelay = curDelay
                }
            }
        }

        assertFails { socket.connect() }
        assertEquals(3, reconnects)
        assertTrue { increasingDelay }

        job.cancel()
        io.close()
    }

    @Test
    fun testStopReconnectingWhenForceClosed() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket("/invalid") {
            timeout = ZERO
            reconnectionDelay = 10.milliseconds
            autoConnect = false
        }

        var reconnects = 0
        val job1 = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.Error>().collect {
                socket.disconnect()
            }
        }
        val job2 = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().collect {
                reconnects++
            }
        }

        assertFails { socket.connect() }
        // set a timer to let reconnection possibly fire
        withContext(Dispatchers.Default) { delay(500) }
        assertEquals(0, reconnects)

        job1.cancel()
        job2.cancel()
        io.close()
    }

    @Test
    fun testReconnectAfterStoppingReconnection() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket("/timeout") {
            timeout = ZERO
            reconnectionAttempts = 2
            reconnectionDelay = 10.milliseconds
            autoConnect = false
        }

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().first()
            socket.disconnect()
            socket.connect()
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().first()
        }

        assertFails { socket.connect() }

        socket.disconnect()
        job.cancel()
        io.close()
    }

    @Test
    fun testStopReconnectingOnASocketAndKeepToReconnectOnAnother() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket1 = io.socket(namespace = "/")
        val socket2 = io.socket(namespace = "/asd")
        assertEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be the same")
        val manger = (socket1.io as VKManager)


        var testPassed: Boolean? = null
        val job1 = launch(start = CoroutineStart.UNDISPATCHED) {
            socket1.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().first()
            socket1.disconnect()

            testPassed = select {
                launch {
                    socket1.once<Socket.Event.Connect>()
                }.onJoin { false }

                launch {
                    socket2.once<Socket.Event.Connect>()
                    withContext(Dispatchers.Default) { delay(500) }
                }.onJoin { true }
            }.also {
                coroutineContext.cancelChildren()
            }
        }

        runCatching { socket1.connect() }
        runCatching { socket2.connect() }

        withContext(Dispatchers.Default) { delay(1000) }
        manger.engine.value?.close()

        job1.join()

        assertEquals(true, testPassed)
        assertFalse { socket1.connected }
        assertTrue { socket2.connected }

        io.close()
    }

    @Test
    fun testTryToReconnectTwiceAndFailWithIncorrectAddress() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket("http://localhost:3940/asd") {
            reconnectionAttempts = 2
            reconnectionDelay = 10.milliseconds
        }

        var reconnectAttempts = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().collect {
                reconnectAttempts++
            }
        }

        assertFails { socket.connect() }
        assertEquals(2, reconnectAttempts)

        job.cancel()
        io.close()
    }

    @Test
    fun testTryToReconnectTwiceAndFailWithImmediateTimeout() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket(namespace = "/timeout") {
            timeout = ZERO
            reconnectionAttempts = 2
            reconnectionDelay = 10.milliseconds
        }

        var reconnectAttempts = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().collect {
                reconnectAttempts++
            }
        }

        assertFails { socket.connect() }
        assertEquals(2, reconnectAttempts)

        job.cancel()
        io.close()
    }

    @Test
    fun testNotTryToReconnectWithIncorrectPortWhenReconnectionDisabled() = runTest(timeout = timeout) {
        val io = io(httpClient)

        val socket = io.socket("http://localhost:3940/asd") {
            reconnection = false
        }

        var reconnectAttempts = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            socket.io.events.filterIsInstance<Manager.Event.ReconnectAttempt>().collect {
                reconnectAttempts++
            }
        }

        assertFails { socket.connect() }
        assertEquals(0, reconnectAttempts)

        job.cancel()
        io.close()
    }

    private suspend fun IO.socket(
        namespace: String = "/",
        queryString: String = "",
        block: ManagerOptionsBuilder.() -> Unit = {},
    ): Socket {
        return socket("${serverUrl}$namespace?$queryString", block)
    }

    private fun io(httpClient: HttpClient, block: IOOptionsBuilder.() -> Unit = {}) = IO(httpClient) {
        block()
        loggingLevel = LoggingLevel.DEBUG
        dispatcher = Dispatchers.Default
    }
}
