package examples

import kotlinx.coroutines.experimental.Here
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch

fun main(args: Array<String>) {
    launch(Here) { // create new coroutine without an explicit threading policy
        delay(1000L) // non-blocking delay for 1 second
        println("World!") // print after delay
    }
    println("Hello,") // main function continues while coroutine is delayed
}
