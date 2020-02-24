package org.openchaos.android.fooping2

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import android.widget.ToggleButton
import androidx.fragment.app.FragmentActivity
import androidx.work.*
import java.time.Duration


class MainActivity : FragmentActivity() {

    private val TAG: String = this.javaClass.simpleName

    // TODO: maybe prefix work tags with class name

    private val workManager: WorkManager = WorkManager.getInstance(this)

    private val oneTimeWorkTag: String = "Vasily" // One ping only
    private val oneTimeWorkRequest = OneTimeWorkRequest.from(MainWorker::class.java)

    // TODO: get interval from preferences

    private val periodicWorkTag: String = "."
    private val periodicWorkRequest = PeriodicWorkRequest.Builder(
            MainWorker::class.java,
            Duration.ofMillis(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS),
            Duration.ofMillis(PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS)
        )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()


    @Suppress("UNUSED_PARAMETER")
    fun queueOneTimeWork(button: View) {
        Log.d(TAG, "queueOneTimeWork()")

        workManager.enqueueUniqueWork(
            oneTimeWorkTag,
            ExistingWorkPolicy.REPLACE,
            oneTimeWorkRequest
        ).result.addListener(Runnable {
            Toast.makeText(this, R.string.btnNowClicked, Toast.LENGTH_SHORT).show()
            //Snackbar.make(button, R.string.btnNowClicked, Snackbar.LENGTH_SHORT).show()
        }, mainExecutor)

        Log.i(TAG, "one-time work request queued")
    }

    fun togglePeriodicWork(button: View) {
        Log.d(TAG, "togglePeriodicWork()")

        if ((button as ToggleButton).isChecked) {
            workManager.enqueueUniquePeriodicWork(
                periodicWorkTag,
                ExistingPeriodicWorkPolicy.REPLACE,
                periodicWorkRequest
            )
            Log.i(TAG, "periodic work request queued")
        } else {
            workManager.cancelUniqueWork(periodicWorkTag)
            Log.i(TAG, "periodic work request cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate()")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // workManager.pruneWork() // debug

        val workInfos = workManager.getWorkInfosForUniqueWork(periodicWorkTag).get() // TODO: async?
        val enabled = (workInfos.size > 0 && workInfos[0].state != WorkInfo.State.CANCELLED) // TODO: multiple infos, different states?

        Log.i(TAG, "periodic work ${if (enabled) "en" else "dis"}abled")
        Log.d(TAG, workInfos.toString())

        val btnEnabled = findViewById<ToggleButton>(R.id.btnEnabled)
        btnEnabled.isChecked = enabled
        btnEnabled.isEnabled = true
    }
}
