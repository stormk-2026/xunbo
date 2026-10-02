package com.stormg.xunbo.core.testing

import com.stormg.xunbo.core.model.Clock
import kotlinx.coroutines.yield

class FakeClock : Clock {
    private var elapsed = 0L

    override fun nowMs(): Long = elapsed

    override suspend fun sleep(ms: Long) {
        require(ms >= 0)
        elapsed += ms
        yield()
    }
}
