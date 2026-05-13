package com.photoconnect

import android.app.Application
import com.photoconnect.debug.DebugAgentLog
import com.photoconnect.debug.ErrorConsoleRecorder
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PhotoConnectApp : Application() {
    override fun onCreate() {
        // #region agent log
        DebugAgentLog.log(
            hypothesisId = "H1",
            location = "PhotoConnectApp.kt:onCreate",
            message = "application_on_create_entry",
            data = mapOf("pid" to android.os.Process.myPid()),
        )
        // #endregion

        super.onCreate()
        ErrorConsoleRecorder.init(this)

        // #region agent log + crash handler
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            ErrorConsoleRecorder.appendUncaught(thread, exception)
            DebugAgentLog.log(
                hypothesisId = "H2",
                location = "PhotoConnectApp.kt:uncaught",
                message = "uncaught_exception",
                data = mapOf(
                    "thread" to thread.name,
                    "type" to exception.javaClass.simpleName,
                    "msg" to (exception.message ?: ""),
                ),
            )
            prev?.uncaughtException(thread, exception)
        }
        DebugAgentLog.log(
            hypothesisId = "H1",
            location = "PhotoConnectApp.kt:onCreate",
            message = "application_on_create_after_super",
            data = emptyMap(),
        )
        // #endregion
    }
}