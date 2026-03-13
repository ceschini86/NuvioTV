@file:Suppress("unused")

package com.lagradost.cloudstream3.mvvm

import android.util.Log

private const val TAG = "CS3mvvm"

fun logError(throwable: Throwable) {
    Log.e(TAG, "Error: ${throwable.message}", throwable)
}

suspend fun <T> safeApiCall(block: suspend () -> T): T? {
    return try {
        block()
    } catch (e: Exception) {
        Log.e(TAG, "safeApiCall error: ${e.message}", e)
        null
    }
}
