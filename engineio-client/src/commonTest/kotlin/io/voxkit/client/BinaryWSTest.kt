package io.voxkit.client

import io.voxkit.engineio.client.engineIO
import io.voxkit.engineio.client.ioHttpClient
import io.voxkit.engineio.parser.Packet
import io.voxkit.socketio.logging.LoggingLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BinaryWSTest {
    @Test
    fun receiveBinaryData() = runTest(timeout = TIMEOUT) {
        val values = Channel<Any>()
        val binaryData = ByteArray(5) { it.toByte() }
        val httpClient = ioHttpClient()

        val engine = engineIO(httpClient) {
            port = PORT
            loggingLevel = LoggingLevel.DEBUG
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            for (packet in engine.incoming) {
                if (packet !is Packet.Binary) continue
                values.send(packet.data)
            }
        }

        engine.send(binaryData)

        assertContentEquals(binaryData, values.receive() as ByteArray)

        engine.close()
    }

    @Test
    fun receiveBinaryDataAndMultibyteUTF8String() = runTest(timeout = TIMEOUT) {
        val channel = Channel<Any>()
        val binaryData = ByteArray(5) { it.toByte() }
        val utf8String = "cash money €€€"
        val httpClient = ioHttpClient()

        val engine = engineIO(httpClient) {
            port = PORT
            loggingLevel = LoggingLevel.DEBUG
        }
        launch(start = CoroutineStart.UNDISPATCHED) {
            for (packet in engine.incoming) {
                if ((packet as? Packet.Message)?.data == "hi") continue
                channel.send(packet)
            }
        }

        engine.send(binaryData)
        engine.send(utf8String)

        assertEquals(Packet.Binary(binaryData), channel.receive() as Packet.Binary)
        assertEquals(Packet.Message(utf8String), channel.receive() as Packet.Message)

        engine.close()
    }
}
