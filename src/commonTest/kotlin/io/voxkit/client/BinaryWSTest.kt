package io.voxkit.client

import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import co.touchlab.kermit.platformLogWriter
import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.engineio.client.engineIOSession
import io.voxkit.engineio.parser.Packet
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class BinaryWSTest {
    @Test
    fun receiveBinaryData() = runTest(timeout = TIMEOUT) {
        val values = Channel<Any>()
        val binaryData = ByteArray(5) { it.toByte() }
        val httpClient = engineIOHttpClient()

        val session = httpClient.engineIOSession {
            port = PORT
            loggerConfig = loggerConfigInit(platformLogWriter(), minSeverity = Severity.Verbose)
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            for (packet in session.incoming) {
                if (packet !is Packet.Binary) continue
                values.send(packet.data)
            }
        }

        session.send(binaryData)

        assertContentEquals(binaryData, values.receive() as ByteArray)

        session.close()
    }

    @Test
    fun receiveBinaryDataAndMultibyteUTF8String() = runTest(timeout = TIMEOUT) {
        val channel = Channel<Any>()
        val binaryData = ByteArray(5) { it.toByte() }
        val utf8String = "cash money €€€"
        val httpClient = engineIOHttpClient()

        val session = httpClient.engineIOSession {
            port = PORT
            loggerConfig = loggerConfigInit(platformLogWriter(), minSeverity = Severity.Verbose)
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            for (packet in session.incoming) {
                if ((packet as? Packet.Message)?.data == "hi") continue
                channel.send(packet)
            }
        }

        session.send(binaryData)
        session.send(utf8String)

        assertEquals(Packet.Binary(binaryData), channel.receive() as Packet.Binary )
        assertEquals(Packet.Message(utf8String), channel.receive() as  Packet.Message )

        session.close()
    }
}
