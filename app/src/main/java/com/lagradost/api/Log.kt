package com.lagradost.api

/** Logging facade used by CloudStream extensions. Delegates to android.util.Log. */
object Log {
    fun d(tag: String, msg: String) { android.util.Log.d(tag, msg) }
    fun i(tag: String, msg: String) { android.util.Log.i(tag, msg) }
    fun w(tag: String, msg: String) { android.util.Log.w(tag, msg) }
    fun e(tag: String, msg: String) { android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable) { android.util.Log.e(tag, msg, tr) }
    fun v(tag: String, msg: String) { android.util.Log.v(tag, msg) }
    fun wtf(tag: String, msg: String) { android.util.Log.wtf(tag, msg) }
}
