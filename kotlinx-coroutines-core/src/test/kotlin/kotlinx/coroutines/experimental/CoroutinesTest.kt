package kotlinx.coroutines.experimental

import org.junit.After
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoroutinesTest {
    var actionIndex = AtomicInteger()
    var finished = AtomicBoolean()
    var error = AtomicReference<Throwable>()

    fun expect(index: Int) {
        val wasIndex = actionIndex.incrementAndGet()
        check(index == wasIndex) { "Expecting action index $index but it is actually $wasIndex" }
    }

    fun expectUnreached() {
        throw IllegalStateException("Should not be reached").also { error.compareAndSet(null, it) }
    }

    fun finish(index: Int) {
        expect(index)
        finished.set(true)
    }

    @After
    fun onCompletion() {
        error.get()?.let { throw it }
        check(finished.get()) { "Expecting that 'finish(...)' was invoked, but it was not" }
    }

    @Test
    fun testSimple() = runBlocking {
        expect(1)
        finish(2)
    }

    @Test
    fun testYield() = runBlocking {
        expect(1)
        yield() // effectively does nothing, as we don't have other coroutines
        finish(2)
    }

    @Test
    fun testLaunchAndYieldJoin() = runBlocking {
        expect(1)
        val job = launch(context) {
            expect(2)
            yield()
            expect(4)
        }
        expect(3)
        job.join()
        finish(5)
    }

    @Test
    fun testNested() = runBlocking {
        expect(1)
        launch(context) {
            expect(2)
            launch(context) {
                expect(3)
            }
            expect(4)
        }
        finish(5)
    }

    @Test
    fun testNestedAndYieldJoin() = runBlocking {
        expect(1)
        val j1 = launch(context) {
            expect(2)
            val j2 = launch(context) {
                expect(3)
                yield()
                expect(6)
            }
            expect(4)
            j2.join()
            expect(7)
        }
        expect(5)
        j1.join()
        finish(8)
    }

    @Test
    fun testCancelChildImplicit() = runBlocking {
        expect(1)
        launch(context) {
            expect(2)
            yield() // parent finishes earlier, does not wait for us
            expectUnreached()
        }
        finish(3)
    }

    @Test
    fun testCancelChildExplicit() = runBlocking {
        expect(1)
        val job = launch(context) {
            expect(2)
            yield()
            expectUnreached()
        }
        expect(3)
        job.cancel()
        finish(4)
    }

    @Test
    fun testCancelNestedImplicit() = runBlocking {
        expect(1)
        val j1 = launch(context) {
            expect(2)
            val j2 = launch(context) {
                expect(3)
                yield() // parent finishes earlier, does not wait for us
                expectUnreached()
            }
            expect(4)
        }
        finish(5)
    }

    @Test
    fun testCancelNestedImplicit2() = runBlocking {
        expect(1)
        val j1 = launch(context) {
            expect(2)
            val j2 = launch(context) {
                expect(3)
                yield() // parent finishes earlier, does not wait for us
                expectUnreached()
            }
            expect(4)
            yield() // does not go further, because already cancelled
            expectUnreached()
        }
        finish(5)
    }

    @Test(expected = IOException::class)
    fun testExceptionPropagation(): Unit = runBlocking {
        finish(1)
        throw IOException()
    }

    @Test(expected = CancellationException::class)
    fun testCancelParentOnChildException(): Unit = runBlocking {
        expect(1)
        launch(context) {
            expect(2)
            throw IOException() // does not propagate exception to launch, but cancels parent (!)
        }
        finish(3)
    }

    @Test(expected = CancellationException::class)
    fun testCancelParentOnChildException2(): Unit = runBlocking {
        expect(1)
        launch(context) {
            expect(2)
            throw IOException()
        }
        finish(3)
        yield() // parent is already cancelled (so gets CancellationException on this suspend attempt)
        expectUnreached()
    }

    @Test(expected = CancellationException::class)
    fun testCancelParentOnNestedException(): Unit = runBlocking {
        expect(1)
        launch(context) {
            expect(2)
            launch(context) {
                expect(3)
                throw IOException() // unhandled exception kills all parents
            }
            expect(4)
        }
        finish(5)
    }

    @Test
    fun testDeferSimple(): Unit = runBlocking {
        expect(1)
        val d = defer(context) {
            expect(2)
            42
        }
        expect(3)
        check(d.await() == 42)
        finish(4)
    }

    @Test
    fun testDeferAndYield(): Unit = runBlocking {
        expect(1)
        val d = defer(context) {
            expect(2)
            yield()
            expect(4)
            42
        }
        expect(3)
        check(d.await() == 42)
        finish(5)
    }

    @Test(expected = IOException::class)
    fun testDeferSimpleException(): Unit = runBlocking {
        expect(1)
        val d = defer(context) {
            expect(2)
            throw IOException()
        }
        finish(3)
        d.await() // will throw IOException
    }

    @Test(expected = IOException::class)
    fun testDeferAndYieldException(): Unit = runBlocking {
        expect(1)
        val d = defer(context) {
            expect(2)
            yield()
            throw IOException()
        }
        finish(3)
        d.await() // will throw IOException
    }
}
