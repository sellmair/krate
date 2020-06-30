package krate.util

import java.util.concurrent.ConcurrentHashMap

fun <K : Any, V : Any> concurrentMapOf(vararg entries: Pair<K, V>) = ConcurrentHashMap<K, V>(entries.toMap())
