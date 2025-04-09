package io.voxkit.socketio.client.util

import io.ktor.http.*

internal val Url.namespace: String get() = encodedPath.takeIf { it.isNotEmpty() } ?: "/"

internal val Url.withoutNamespace: Url
    get() = URLBuilder(this).apply { encodedPath = "/" }.build()
