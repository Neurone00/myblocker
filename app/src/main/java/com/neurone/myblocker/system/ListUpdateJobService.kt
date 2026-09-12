package com.neurone.myblocker.system

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.filter.ListRepository

/** Periodic blocklist refresh (every 12 hours, any network). */
class ListUpdateJobService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val prefs = Prefs.get(this)
        if (!prefs.autoUpdateLists) return false
        worker = Thread({
            var reschedule = false
            try {
                val results = ListRepository(this).updateEnabled()
                Log.i(TAG, "list update: $results")
                if (results.values.any { it == null }) FilterEngine.reload(this)
                reschedule = results.values.any { it != null }
            } catch (e: Exception) {
                Log.w(TAG, "update failed", e)
                reschedule = true
            } finally {
                jobFinished(params, reschedule)
            }
        }, "list-update").also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val TAG = "ListUpdateJob"
        private const val JOB_ID = 4242
        private const val PERIOD_MS = 12L * 60 * 60 * 1000

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ListUpdateJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setRequiresBatteryNotLow(true)
                .build()
            scheduler.schedule(job)
        }
    }
}
