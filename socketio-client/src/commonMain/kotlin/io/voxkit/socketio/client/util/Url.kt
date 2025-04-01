package io.voxkit.socketio.client.util

import io.ktor.http.*

internal val Url.namespace: String get() = encodedPath

internal val Url.withoutNamespace: Url
    get() = buildUrl {
        protocol = this@withoutNamespace.protocol
        host = this@withoutNamespace.host
        port = this@withoutNamespace.port
    }
