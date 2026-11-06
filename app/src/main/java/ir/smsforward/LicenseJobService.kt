package ir.smsforward

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * کار دوره‌ای (هر ۶ ساعت):
 * - یادآوری‌های اشتراک (چند روز مانده به پایان، و تکرار بعد از پایان) که فقط محلی‌اند؛
 * - پرسیدن وضعیت اشتراک از بازار؛ این‌طور پایان/تمدید اشتراک زود تشخیص داده می‌شود و
 *   اطلاعات قبلی اشتراک (مثلاً بعد از نصب مجدد) برمی‌گردد.
 */
class LicenseJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Premium.reconcile(app)
        Reminders.run(app)
        LicenseManager.refresh(app) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_PERIODIC = 4301
        private const val HOUR = 60L * 60 * 1000

        private const val JOB_EXPIRY = 4302

        /** یک کار یک‌باره در لحظه‌ی پایان اشتراک: قفل‌کردن قانون‌ها و پرسیدن دوباره از بازار (شاید تمدید شده) */
        fun scheduleAt(c: Context, expiry: Long) {
            try {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                val delay = (expiry - System.currentTimeMillis() + 2_000L).coerceAtLeast(0L)
                js.schedule(
                    JobInfo.Builder(JOB_EXPIRY, ComponentName(c, LicenseJobService::class.java))
                        .setMinimumLatency(delay)
                        .setOverrideDeadline(delay + 10 * 60 * 1000L)
                        .setPersisted(true)
                        .build()
                )
            } catch (e: Exception) {
                // قفل‌شدن در هر حال هنگام دریافت پیامک یا باز شدن برنامه اعمال می‌شود
            }
        }

        fun schedule(c: Context) {
            try {
                val js = c.getSystemService(JobScheduler::class.java) ?: return
                if (js.getPendingJob(JOB_PERIODIC) != null) return
                js.schedule(
                    JobInfo.Builder(JOB_PERIODIC, ComponentName(c, LicenseJobService::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(6 * HOUR)
                        .setPersisted(true)
                        .build()
                )
            } catch (e: Exception) {
                // با باز شدن برنامه و صفحه‌ی اصلی بررسی همچنان انجام می‌شود
            }
        }
    }
}
