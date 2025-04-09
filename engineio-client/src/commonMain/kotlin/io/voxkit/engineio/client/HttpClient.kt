package io.voxkit.engineio.client

import io.ktor.client.*

/**
 * Creates a new [HttpClient] instance for Engine.IO.
 *
 * @param block a lambda function to configure the [HttpClientConfig]
 */
public fun ioHttpClient(block: HttpClientConfig<*>.() -> Unit = {}): HttpClient = platformHttpClient(block)

internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient
