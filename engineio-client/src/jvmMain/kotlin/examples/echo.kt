package examples

import io.voxkit.engineio.client.ioHttpClient
import io.voxkit.engineio.client.engineIO
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

private const val PORT = 3000

public fun main(): Unit = runBlocking {
    val process = ServerLauncher.startServer("echo", PORT)
    val httpClient = ioHttpClient()
    val session = engineIO(httpClient) {
        port = PORT
    }

    val echo = async { session.incoming.receive() }
    session.send("Hello, Engine.IO!")
    println("Received: ${echo.await()}")

    session.close()
    httpClient.close()
    ServerLauncher.stopServer(process)
}
