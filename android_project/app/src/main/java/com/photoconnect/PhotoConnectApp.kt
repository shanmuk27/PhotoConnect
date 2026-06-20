package com.photoconnect

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.photoconnect.debug.DebugAgentLog
import com.photoconnect.debug.ErrorConsoleRecorder
import com.photoconnect.utils.AppLocaleManager
import com.photoconnect.utils.CacheMaintenance
import com.photoconnect.utils.forceLeftToRightTree
import com.photoconnect.utils.SessionManager
import com.photoconnect.utils.UiRuntimeTranslationManager
import com.photoconnect.workers.PostUploadWorker
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
        AppLocaleManager.applySavedLocale(this)
        ErrorConsoleRecorder.init(this)
        CacheMaintenance.pruneAsync(this)
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activity.window.decorView.forceLeftToRightTree()
                UiRuntimeTranslationManager.install(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                activity.window.decorView.forceLeftToRightTree()
                UiRuntimeTranslationManager.schedule(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                UiRuntimeTranslationManager.uninstall(activity)
            }
        })
        if (SessionManager(this).isTaker()) {
            PostUploadWorker.resumePendingUploads(this)
        }

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
