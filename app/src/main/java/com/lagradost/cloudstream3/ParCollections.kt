@file:Suppress("unused")

package com.lagradost.cloudstream3

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// ── Parallel collection helpers ──
// These MUST live in this file (ParCollections.kt) so the JVM class name is
// ParCollectionsKt — CloudStream extensions reference them by that name.
//
// Extensions are compiled with receiver type List<T> (not Iterable<T>),
// so the JVM descriptor uses Ljava/util/List as the first parameter.

suspend fun <T, R> List<T>.amap(transform: suspend (T) -> R): List<R> = coroutineScope {
    map { async { transform(it) } }.awaitAll()
}

suspend fun <T, R> List<T>.amapIndexed(transform: suspend (index: Int, T) -> R): List<R> = coroutineScope {
    mapIndexed { index, item -> async { transform(index, item) } }.awaitAll()
}

// Also provide Iterable overloads for internal use
suspend fun <T, R> Iterable<T>.amap(transform: suspend (T) -> R): List<R> = coroutineScope {
    map { async { transform(it) } }.awaitAll()
}

suspend fun runAllAsync(vararg blocks: suspend () -> Unit) = coroutineScope {
    blocks.map { async { it() } }.awaitAll()
}

// ── Deprecated aliases used by older extensions ──

@Deprecated("Use amap", ReplaceWith("amap(transform)"))
suspend fun <T, R> List<T>.apmap(transform: suspend (T) -> R): List<R> = amap(transform)

@Deprecated("Use amapIndexed", ReplaceWith("amapIndexed(transform)"))
suspend fun <T, R> List<T>.apmapIndexed(transform: suspend (index: Int, T) -> R): List<R> = amapIndexed(transform)

@Deprecated("Use runAllAsync", ReplaceWith("runAllAsync(*blocks)"))
suspend fun argamap(vararg blocks: suspend () -> Unit) = runAllAsync(*blocks)
