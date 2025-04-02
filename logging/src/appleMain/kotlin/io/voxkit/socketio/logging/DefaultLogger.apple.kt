package io.voxkit.socketio.logging

public actual fun defaultLogger(): Logger = object : Logger {
    private val tag = "io.voxkit.socketio"

    override fun log(message: String, throwable: Throwable?, level: LoggingLevel) {
        val levelString = when (level) {
            LoggingLevel.DEBUG -> "DEBUG"
            LoggingLevel.INFO -> "INFO"
            LoggingLevel.WARN -> "WARN"
            LoggingLevel.ERROR -> "ERROR"
            LoggingLevel.NONE -> null
        } ?: return

        val stackTrace = throwable?.stackTraceToString()?.let { "\n$it" } ?: ""
        val formattedMessage = "[$tag] $levelString: $message$stackTrace"
        println(formattedMessage)
    }
}
