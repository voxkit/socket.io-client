package io.voxkit.socketio.client

import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

class ConnectionTest {
    @Test
    fun testConnectToDefaultNamespace() = runTest {
        val io = io {
            loggerConfig = loggerConfigInit(platformLogWriter(), minSeverity = Severity.Verbose)
            dispatcher = Dispatchers.IO
        }
        val socket = withContext(Dispatchers.Default.limitedParallelism(1)) { io.socket("http://localhost:3000/") }

        assertTrue(socket.connected, "Socket should be connected to the default namespace")
    }
}
