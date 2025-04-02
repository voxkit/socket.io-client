package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.client.plugins.logging.*
import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.socketio.logging.LoggingLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConnectionTest {
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
    fun testConnectToDefaultNamespace() = runTest {
        val socket = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }

        assertTrue(socket.connected, "Socket should be connected to the default namespace")
    }

    @Test
    fun testTwoSocketsWithSameNamespace() = runTest {
        val socket1 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }
        val socket2 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/") }

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")
    }

    @Test
    fun testTwoSocketsWithSameNamespaceAndDifferentQueryStrings() = runTest {
        val socket1 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/?param1=value1") }
        val socket2 = withContext(Dispatchers.Default) { io.socket("http://localhost:3000/?param2=value2") }

        assertTrue(socket1.connected, "Socket 1 should be connected to the default namespace")
        assertTrue(socket2.connected, "Socket 2 should be connected to the default namespace")
        assertNotEquals(socket1.io, socket2.io, "Socket 1 and Socket 2 managers should be different")
    }
}
