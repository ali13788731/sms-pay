package ir.smsforward

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * هر روز یک بار بیدار می‌شود، نسخه‌ی جدید را بررسی می‌کند و (حداکثر هر ۳ روز) یک اعلان قشنگ می‌فرستد:
 * «بروزرسانی کنید، این امکانات اضافه می‌شود» یا معرفی یک امکان ویژه‌ی اشتراک.
 */
class NewsJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            try {
                UpdateChecker.check(app)
                PromoNotifier.run(app)
            } catch (e: Exception) {
                // اعلان پیشنهادی مهم نیست؛ بدون خطا رد می‌شویم
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 4401

        fun schedule(c: Context) {
            try {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(c, NewsJobService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(24L * 60 * 60 * 1000)
                        .setPersisted(true)
                        .build()
                )
            } catch (e: Exception) {
                // زمان‌بندی ناموفق؛ مهم نیست
            }
        }
    }
}
