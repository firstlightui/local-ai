package dev.firstlightui.plugins.local_llm.probe

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

fun initializeLocalLLMProbe(context: Context) {
    LocalLLMProbeRuntime.initialize(context.applicationContext)
    (context.applicationContext as Application).registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            private var startedActivities = 0

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                if (startedActivities == 1) {
                    LocalLLMProbeRuntime.setBackgrounded(false)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    LocalLLMProbeRuntime.setBackgrounded(true)
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
    )
}
