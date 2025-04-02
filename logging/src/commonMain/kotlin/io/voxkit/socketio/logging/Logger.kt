package io.voxkit.socketio.logging

public interface Logger {
    public fun log(message: String, throwable: Throwable?, level: LoggingLevel)
}

public enum class LoggingLevel { DEBUG, INFO, WARN, ERROR, NONE }
