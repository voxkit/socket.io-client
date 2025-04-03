package io.voxkit.socketio.client

import io.ktor.client.*
import io.ktor.http.*
import io.voxkit.socketio.client.util.namespace
import io.voxkit.socketio.client.util.withoutNamespace
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public fun HttpClient.IO(block: IOOptionsBuilder.() -> Unit = {}): IO {
    return IO(this, IOOptionsBuilder().apply(block).build())
}

public class IO internal constructor(
    private val httpClient: HttpClient,
    private val factoryOptions: IOOptions
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + factoryOptions.dispatcher + CoroutineName("socket.io"))
    private var defaultManager: Manager? = null

    private val namespaces = mutableSetOf<String>()
    private val mutex = Mutex()

    public suspend fun socket(urlString: String, block: ManagerOptionsBuilder.() -> Unit = {}): Socket {
        val url = Url(urlString)
        val options = ManagerOptionsBuilder().apply(block).build()
        val manager = if (factoryOptions.forceNew) {
            manager(url, options)
        } else {
            defaultOrCreateManager(url, options)
        }

        return manager.socket(url.namespace, auth = options.socketOption.auth)
    }

    private fun manager(url: Url, options: ManagerOptions): Manager {
        return VKManager(
            serverUrl = url.withoutNamespace,
            options = options,
            scope = scope,
            httpClient = httpClient,
            loggerFactory = factoryOptions.loggerFactory,
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
        scope.cancel()
    }
}
