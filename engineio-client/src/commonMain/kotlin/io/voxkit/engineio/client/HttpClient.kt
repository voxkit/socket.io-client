package io.voxkit.engineio.client

import io.ktor.client.*

/**
 * Creates a new [HttpClient] instance for Engine.IO.
 */
public fun engineIOHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = platformHttpClient(block)

internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient
