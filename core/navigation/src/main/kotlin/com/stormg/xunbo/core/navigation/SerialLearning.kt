package com.stormg.xunbo.core.navigation

import com.stormg.xunbo.core.model.IrCode
import com.stormg.xunbo.core.model.RemoteKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

object SerialLearning {
    suspend fun read(
        key: RemoteKey,
        readFragment: suspend () -> ByteArray,
    ): IrCode =
        withTimeout(5_000) {
            val packet = LearnedBytes(key)
            var code: IrCode? = null
            while (code == null) {
                currentCoroutineContext().ensureActive()
                code = packet.accept(readFragment())
                yield()
            }
            currentCoroutineContext().ensureActive()
            code
        }
}
