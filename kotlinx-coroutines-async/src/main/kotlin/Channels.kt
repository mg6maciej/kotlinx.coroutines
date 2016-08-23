import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

interface InputChannel<out T> {
    fun receive(continuation: (T) -> Unit, exceptionalContinuation: (Throwable) -> Unit)
}

interface OutputChannel<in T> {
    // This function is needed to support the case when the sender doesn't want to start computing anything before some
    // receiver wants the first computed value
    fun registerSender(continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit)

    fun send(data: T, continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit)
}

// TODO: Which threads to run handlers on?
class SimpleChannel<T> : InputChannel<T>, OutputChannel<T> {
    companion object {
        private val STATE_UPDATER = AtomicReferenceFieldUpdater.newUpdater(SimpleChannel::class.java, Any::class.java, "state")
    }

    private sealed class State {
        // No sender has sent anything and there's no receiver waiting
        object NoValue : State()
        // A sender is waiting for a receiver to come to start computing the first value
        class SenderRegistered(val senderContinuation: () -> Unit): State()
        // A sender has sent a value and is waiting for a receiver to take it
        class SenderWaiting<out T>(val valueSent: T, val senderContinuation: () -> Unit): State()
        // Receiver is waiting for a sender to send something
        class ReceiverWaiting<in T>(val receiverContinuation: (T) -> Unit): State()
    }

    @Volatile
    private var state: Any = State.NoValue

    override fun registerSender(continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, continuation) {
            _state, _continuation ->
            when (_state) {
                State.NoValue -> @Suppress("UNCHECKED_CAST") State.SenderRegistered(_continuation as () -> Unit)
                else -> _state
            }
        }

        when (oldState) {
            State.NoValue -> {}
            is State.SenderRegistered -> exceptionalContinuation(
                IllegalStateException("Illegal attempt by \"$continuation\" to send while another sender \"${oldState.senderContinuation}\" is waiting")
            )
            is State.SenderWaiting<*> -> exceptionalContinuation(
                IllegalStateException("Illegal attempt by \"$continuation\" to send while another sender \"${oldState.senderContinuation}\" is waiting")
            )
            is State.ReceiverWaiting<*> -> {
                // TODO: Run on sender's thread
                continuation()
            }
        }
    }

    override fun send(data: T, continuation: () -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, State.SenderWaiting(data, continuation)) {
            _state, _senderWaiting ->
            when (_state as State) {
                is State.NoValue,
                is State.SenderRegistered -> _senderWaiting
                is State.SenderWaiting<*> -> _state
                is State.ReceiverWaiting<*> -> State.NoValue
            }
        }

        when (oldState) {
            State.NoValue,
            is State.SenderRegistered -> {}
            is State.SenderWaiting<*> -> exceptionalContinuation(
                IllegalStateException("Illegal attempt by \"$continuation\" to send while another sender \"${oldState.senderContinuation}\" is waiting")
            )
            is State.ReceiverWaiting<*> -> {
                // TODO: Run on receiver's thread
                @Suppress("UNCHECKED_CAST")
                (oldState as State.ReceiverWaiting<T>).receiverContinuation(data)
            }
        }
    }

    override fun receive(continuation: (T) -> Unit, exceptionalContinuation: (Throwable) -> Unit) {
        val oldState = STATE_UPDATER.getAndAccumulate(this, continuation) {
            _state, _continuation ->
            when (_state) {
                State.NoValue,
                is State.SenderRegistered -> @Suppress("UNCHECKED_CAST") State.ReceiverWaiting(_continuation as (T) -> Unit)
                else -> State.NoValue
            }
        }

        when (oldState) {
            State.NoValue -> {}
            is State.ReceiverWaiting<*> -> exceptionalContinuation(
                    IllegalStateException(
                        "Illegal attempt by \"$continuation\" to receive when another reader \"${oldState.receiverContinuation}\" is waiting"
                    )
            )
            // TODO: Execute on the sender's thread/scheduler
            is State.SenderRegistered -> oldState.senderContinuation()
            is State.SenderWaiting<*> -> {
                // TODO: Execute on receiver's thread
                @Suppress("UNCHECKED_CAST")
                continuation(oldState.valueSent as T)

                // TODO: Execute on sender's thread
                oldState.senderContinuation()
            }
        }
    }
}