package com.stormg.xunbo.core.navigation

import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.IrTransmitter
import com.stormg.xunbo.core.model.KeyExecutor
import com.stormg.xunbo.core.model.RemoteKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** Mutex waiters form a serial queue; cancellation invalidates every waiting generation. */
class SerialKeyExecutor(private val transmitter: IrTransmitter, private val codes: () -> Map<RemoteKey, IrCode>) : KeyExecutor {
    constructor(transmitter: IrTransmitter, codes: Map<RemoteKey, IrCode>) : this(transmitter, { codes })

    private val mutex = Mutex()
    private val generation = AtomicLong()

    override suspend fun send(key: RemoteKey) {
        val acceptedGeneration = generation.get()
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (acceptedGeneration != generation.get()) throw CancellationException("Pending key cancelled")
            check(transmitter.isReady) { "Transmitter unavailable" }
            val code = checkNotNull(codes()[key]) { "Key has no learned code" }
            transmitter.send(code)
        }
    }

    override fun cancelPending() {
        generation.incrementAndGet()
    }
}
