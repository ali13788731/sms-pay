package ir.smsforward

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** بررسی دوره‌ای نسخه در پس‌زمینه (هر چند ساعت یک بار، فقط وقتی اینترنت وصل است). */
class UpdateJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                UpdateChecker.check(app)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4201

        fun schedule(c: Context) {
            if (!UpdateChecker.configured()) return
            try {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJobService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(6L * 60 * 60 * 1000)
                        .setPersisted(true)
                        .build()
                )
            } catch (e: Exception) {
                // زمان‌بندی ناموفق؛ بررسی هنگام باز شدن برنامه و رسیدن پیامک همچنان انجام می‌شود
            }
        }
    }
}
