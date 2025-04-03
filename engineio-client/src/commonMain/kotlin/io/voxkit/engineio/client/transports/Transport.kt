package io.voxkit.engineio.client.transports

import io.ktor.client.call.*
import io.voxkit.engineio.parser.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * Transport interface for Engine.IO client.
 */
internal interface Transport  {
    /**
     * Transport type.
     */
    val type: TransportType

    /**
     * Incoming packets channel.
     */
    val incoming: ReceiveChannel<Packet>

    /**
     * [StateFlow] of [HttpClientCall] associated with this transport.
     * For [TransportType.WEBSOCKET] it's emitted only once when the connection is established.
     * For [TransportType.POLLING] it's emitted every time a new polling request is made.
     */
    val call: StateFlow<HttpClientCall?>

    /**
     * Sends a packet to the Engine.IO server.
     *
     * @param packet The [Packet] to send.
     */
    suspend fun send(packet: Packet)

    /**
     * Closes the transport.
     */
    fun close()
}
