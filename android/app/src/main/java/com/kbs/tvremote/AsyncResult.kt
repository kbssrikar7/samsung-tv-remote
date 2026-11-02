package com.kbs.tvremote

import kotlinx.coroutines.CancellationException

/** Cancellation belongs to the caller; it must never become a visible network error. */
internal suspend fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
