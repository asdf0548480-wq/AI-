package com.example.voicedial

import android.app.Application
import java.io.PrintWriter
import java.io.StringWriter

class CrashApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val fullText = "${throwable.javaClass.name}: ${throwable.message}\n\n$sw"

                getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", fullText)
                    .apply()
            } catch (ignored: Exception) {
                // never let the crash-logger itself crash
            }
            // let the normal system crash handling continue (app will still close)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
