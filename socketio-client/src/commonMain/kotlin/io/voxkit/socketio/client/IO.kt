package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.http.*
import io.voxkit.socketio.client.util.namespace
import io.voxkit.socketio.client.util.withoutNamespace
import io.voxkit.socketio.logging.VoxKitLoggerFactory
import io.voxkit.socketio.logging.defaultLogger
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
) : SynchronizedObject(), AutoCloseable {

    private val loggerFactory = VoxKitLoggerFactory(ioOptions.logger ?: defaultLogger(), ioOptions.loggingLevel)
    private val logger = loggerFactory.createLogger("IO")
    private val scope = CoroutineScope(SupervisorJob() + ioOptions.dispatcher + CoroutineName("IO"))
    private var defaultManager: VoxKitManager? = null
    private val managers = mutableSetOf<VoxKitManager>()
    private val namespaces = mutableSetOf<String>()

    public fun socket(urlString: String, block: ManagerOptionsBuilder.() -> Unit = {}): Socket {
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

    private fun manager(url: Url, options: ManagerOptions): VoxKitManager {
        return VoxKitManager(
            serverUrl = url.withoutNamespace,
            ioOptions = ioOptions,
            options = options,
            scope = scope,
            httpClient = httpClient,
            loggerFactory = loggerFactory,
        )
    }

    private fun defaultOrCreateManager(url: Url, options: ManagerOptions): VoxKitManager = synchronized(this) {
        if (namespaces.contains(url.namespace)) {
            return manager(url.withoutNamespace, options)
        }
        namespaces.add(url.namespace)
        defaultManager ?: manager(url, options).also { defaultManager = it }
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
