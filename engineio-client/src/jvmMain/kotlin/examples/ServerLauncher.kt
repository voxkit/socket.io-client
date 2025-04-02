package examples

import io.voxkit.socketio.logging.LoggingLevel
import io.voxkit.socketio.logging.VoxKitLogger
import io.voxkit.socketio.logging.defaultLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

internal object ServerLauncher {
    private val logger = VoxKitLogger("ServerLauncher", LoggingLevel.DEBUG, defaultLogger())

    private val processes = mutableMapOf<Process, CoroutineScope>()

    suspend fun startServer(name: String, port: Int = 3000): Process = withContext(Dispatchers.IO) {
        logger.d { "Starting server..." }
        val env = arrayOf("PORT=$port")
        val process = Runtime.getRuntime().exec("node test_server/$name.js", env)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        processes[process] = scope

        val latch = CompletableDeferred<Unit>()
        scope.launch { printStream("stdout", process.inputStream, latch) }
        scope.launch { printStream("stderr", process.errorStream, latch) }
        latch.await()

        process
    }

    fun stopServer(process: Process) {
        logger.d { "Stopping server..." }
        val scope = processes.remove(process)
        process.destroy()
        scope?.cancel()
    }

    private fun printStream(streamName: String, stream: InputStream, latch: CompletableDeferred<Unit>) {
        stream.reader().buffered().use { reader ->
            logger.d { "[$streamName]: ${reader.readLine()}" }
            latch.complete(Unit)
        }
    }
}
