package io.voxkit.socketio.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

public fun main(): Unit = runBlocking {
    val flow = MutableSharedFlow<Int>()

    launch {
        repeat(Int.MAX_VALUE) { i ->
            println("Emitting value: $i")
            flow.emit(i)
            delay(100)
        }
    }

    delay(1000)

    flow.take(10).collect { value ->
        println("Received value: $value")
        delay(1000)
    }
}
