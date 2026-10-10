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

    fun schedule() {
        val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, 7, TimeUnit.DAYS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .build()
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            request
        )
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
                    // 复用 AssetsActivity 的更新逻辑
                    AssetsActivity.updateAssetStatic(file, versionFile, localVersion)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
            return Result.success()
        }
    }
}
