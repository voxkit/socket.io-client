package io.voxkit.engineio.client

import io.ktor.client.*

/**
 * Creates a new [HttpClient] instance for Engine.IO.
 */
public fun engineIOHttpClient(): HttpClient = platformHttpClient()

internal expect fun platformHttpClient(): HttpClient
