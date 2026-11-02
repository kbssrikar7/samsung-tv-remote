package com.kbs.tvremote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AsyncResultTest {
    @Test fun cancelledWorkCannotBecomeAnOrdinaryNetworkFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking { cancellableResult<Unit> { throw CancellationException("superseded") } }
        }
    }

    @Test fun networkFailuresRemainReportable() = runBlocking {
        val error = IOException("offline")
        assertSame(error, cancellableResult<Unit> { throw error }.exceptionOrNull())
    }
}
