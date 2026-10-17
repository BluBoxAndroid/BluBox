package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.AssetsActivity
import java.io.File
import java.util.concurrent.TimeUnit

object RouteAssetsUpdater {

    private const val WORK_NAME = "RouteAssetsUpdater"
    private const val CHECK_INTERVAL_MS = 3 * 24 * 60 * 60 * 1000L

    fun schedule() {
        val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, 3, TimeUnit.DAYS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            request
        )
        // Check on app open: trigger immediately if stale for over 3 days (WorkManager timing is unreliable, fallback)
        checkAndTriggerIfStale()
    }

    private fun checkAndTriggerIfStale() {
        try {
            val filesDir = app.filesDir
            val stampFile = File(filesDir, "route_assets_last_check.txt")
            val lastCheck = if (stampFile.exists()) {
                stampFile.readText().trim().toLongOrNull() ?: 0L
            } else {
                0L
            }
            if (System.currentTimeMillis() - lastCheck > CHECK_INTERVAL_MS) {
                val oneTime = androidx.work.OneTimeWorkRequest.Builder(UpdateTask::class.java)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
                RemoteWorkManager.getInstance(app).enqueue(oneTime)
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    internal fun markChecked() {
        try {
            File(app.filesDir, "route_assets_last_check.txt").writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    fun cancel() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            try {
                val filesDir = applicationContext.filesDir
                for (name in arrayOf("geoip.db", "geosite.db")) {
                    val file = File(filesDir, name)
                    val versionFile = File(filesDir, "$name.version.txt")
                    val localVersion = if (versionFile.exists()) {
                        versionFile.readText().trim()
                    } else {
                        ""
                    }
                    // Reuse AssetsActivity update logic
                    AssetsActivity.updateAssetStatic(file, versionFile, localVersion)
                }
                markChecked()
            } catch (e: Exception) {
                Logs.w(e)
            }
            return Result.success()
        }
    }
}
