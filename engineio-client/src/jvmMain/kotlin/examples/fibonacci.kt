package examples

import io.voxkit.engineio.client.engineIO
import io.voxkit.engineio.client.ioHttpClient
import io.voxkit.engineio.parser.data
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val PORT = 3000
private const val NUMBER_OF_FIBONACCI = 5

public fun main(): Unit = runBlocking {
    val process = ServerLauncher.startServer("fibonacci", PORT)
    val httpClient = ioHttpClient()
    val session = engineIO(httpClient) {
        port = PORT
    }

    launch(start = CoroutineStart.UNDISPATCHED) {
        repeat(NUMBER_OF_FIBONACCI) { n ->
            val packet = session.incoming.receive()
            if (n > 0) print(", ")
            print(packet.data())
        }
        println()
        session.close()
    }

    while (runCatching { session.send("next") }.map { true }.getOrDefault(false)) {
        delay(500)
    }

    httpClient.close()
    ServerLauncher.stopServer(process)
}
