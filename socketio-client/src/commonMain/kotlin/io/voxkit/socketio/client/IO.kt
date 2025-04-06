package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.http.*
import io.voxkit.socketio.client.util.namespace
import io.voxkit.socketio.client.util.withoutNamespace
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import io.voxkit.socketio.logging.defaultLogger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public fun IO(
    httpClient: HttpClient,
    block: IOOptionsBuilder.() -> Unit = {}
): IO {
    return IO(
        httpClient = httpClient,
        ioOptions = IOOptionsBuilder().apply(block).build(),
    )
}

public class IO internal constructor(
    private val httpClient: HttpClient,
    private val ioOptions: IOOptions,
) : AutoCloseable {

    private val loggerFactory = VoxKitLoggerFactory(ioOptions.logger ?: defaultLogger(), ioOptions.loggingLevel)
    private val logger = loggerFactory.createLogger("IO")
    private val scope = CoroutineScope(SupervisorJob() + ioOptions.dispatcher + CoroutineName("IO"))
    private var defaultManager: Manager? = null
    private val managers = mutableSetOf<Manager>()
    private val namespaces = mutableSetOf<String>()
    private val mutex = Mutex()

    public suspend fun socket(urlString: String, block: ManagerOptionsBuilder.() -> Unit = {}): Socket {
        val url = Url(urlString)
        val options = ManagerOptionsBuilder().apply(block).build()
        val manager = if (ioOptions.forceNew) {
            manager(url, options)
        } else {
            defaultOrCreateManager(url, options)
        }
        managers += manager

        return manager.socket(url.namespace, auth = options.socketOption.auth)
    }

    private fun manager(url: Url, options: ManagerOptions): Manager {
        return VKManager(
            serverUrl = url.withoutNamespace,
            ioOptions = ioOptions,
            options = options,
            scope = scope,
            httpClient = httpClient,
            loggerFactory = loggerFactory,
        )
    }

    private suspend fun defaultOrCreateManager(url: Url, options: ManagerOptions): Manager {
        mutex.withLock {
            if (namespaces.contains(url.namespace)) {
                return manager(url.withoutNamespace, options)
            }
            namespaces.add(url.namespace)
            return defaultManager ?: manager(url, options).also { defaultManager = it }
        }
    }

    override fun close() {
        logger.d { "Close IO" }
        defaultManager?.close()
        defaultManager = null
        managers.forEach { it.close() }
        managers.clear()
        namespaces.clear()
        scope.cancel()
    }
}
