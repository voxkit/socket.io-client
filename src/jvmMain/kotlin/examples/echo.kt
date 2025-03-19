package examples

import io.voxkit.engineio.client.engineIOHttpClient
import io.voxkit.engineio.client.engineIOSession
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

private const val PORT = 3000

public fun main(): Unit = runBlocking {
    val process = ServerLauncher.startServer("echo", PORT)
    val httpClient = engineIOHttpClient()
    val session = httpClient.engineIOSession {
        port = PORT
    }

    val echo = async { session.incoming.receive() }
    session.send("Hello, Engine.IO!")
    println("Received: ${echo.await()}")

    session.close()
    httpClient.close()
    ServerLauncher.stopServer(process)
}
