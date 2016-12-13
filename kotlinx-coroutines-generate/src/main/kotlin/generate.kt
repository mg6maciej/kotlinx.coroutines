package kotlinx.coroutines

/**
 * Creates a Sequence object based on received coroutine [c].
 *
 * Each call of 'yield' suspend function within the coroutine lambda generates
 * next element of resulting sequence.
 */
interface Generator<in T> {
    suspend fun yield(value: T)
}

fun <T> generate(block: @Suspend() (Generator<T>.() -> Unit)): Sequence<T> = GeneratedSequence(block)

class GeneratedSequence<out T>(private val block: @Suspend() (Generator<T>.() -> Unit)) : Sequence<T> {
    override fun iterator(): Iterator<T> = GeneratedIterator(block)
}

class GeneratedIterator<T>(block: @Suspend() (Generator<T>.() -> Unit)) : AbstractIterator<T>(), Generator<T> {
    private var nextStep: Continuation<Unit> = block.createCoroutine(this, object : Continuation<Unit> {
        override fun resume(data: Unit) {
            done()
        }

        override fun resumeWithException(exception: Throwable) {
            throw exception
        }
    })

    override fun computeNext() {
        nextStep.resume(Unit)
    }
    suspend override fun yield(value: T) = suspendWithCurrentContinuation<Unit> { c ->
        setNext(value)
        nextStep = c

        SUSPENDED
    }
}