package io.voxkit.socketio.logging

public class VoxKitLoggerFactory(public val logger: Logger, public val level: LoggingLevel) {
    public fun createLogger(tag: String): VoxKitLogger = VoxKitLogger(tag, level, logger)
}
