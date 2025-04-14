package io.voxkit.socketio.logging

public class VoxKitLogger(private val tag: String, private val level: LoggingLevel, logger: Logger) : Logger by logger {
    public fun d(throwable: Throwable? = null, message: () -> String) {
        if (LoggingLevel.DEBUG >= level) {
            log("$tag: ${message()}", throwable, LoggingLevel.DEBUG)
        }
    }

    public fun i(throwable: Throwable? = null, message: () -> String) {
        if (LoggingLevel.INFO >= level) {
            log("$tag: ${message()}", throwable, LoggingLevel.INFO)
        }
    }

    public fun w(throwable: Throwable? = null, message: () -> String) {
        if (LoggingLevel.WARN >= level) {
            log("$tag: ${message()}", throwable, LoggingLevel.WARN)
        }
    }

    public fun e(throwable: Throwable? = null, message: () -> String) {
        if (LoggingLevel.ERROR >= level) {
            log("$tag: ${message()}", throwable, LoggingLevel.ERROR)
        }
    }
}
