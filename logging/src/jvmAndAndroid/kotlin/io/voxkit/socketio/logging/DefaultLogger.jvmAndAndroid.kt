package io.voxkit.socketio.logging

import org.slf4j.LoggerFactory

public actual fun defaultLogger(): Logger = object : Logger {
    private val logger = LoggerFactory.getLogger("io.voxkit.socketio")

    override fun log(message: String, throwable: Throwable?, level: LoggingLevel) {
        when (level) {
            LoggingLevel.DEBUG -> logger.debug(message, throwable)
            LoggingLevel.INFO -> logger.info(message, throwable)
            LoggingLevel.WARN -> logger.warn(message, throwable)
            LoggingLevel.ERROR -> logger.error(message, throwable)
            LoggingLevel.NONE -> Unit
        }
    }
}
