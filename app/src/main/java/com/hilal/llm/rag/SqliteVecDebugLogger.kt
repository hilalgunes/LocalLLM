package com.hilal.llm.rag

import android.util.Log
import com.hilal.llm.BuildConfig

internal object SqliteVecDebugLogger {
    const val TAG = "SQLITE_VEC_DEBUG"
    private const val PREFIX = "SQLITE_VEC_DEBUG | "

    fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, PREFIX + message)
    }

    fun error(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(TAG, PREFIX + message, throwable)
    }
}
