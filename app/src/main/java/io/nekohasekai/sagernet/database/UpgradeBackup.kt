package io.nekohasekai.sagernet.database

import android.content.Context
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import java.io.File

/**
 * One-shot safety snapshot of the profile and settings databases, taken on
 * the first launch after an app upgrade, before any new-version code opens
 * them. A broken release (for example a serialization change that crashes
 * on existing data) can then be rolled back without losing subscriptions.
 *
 * The last-seen version is tracked in a plain marker file instead of
 * DataStore on purpose: reading DataStore would open the settings database
 * with the new version's code before the backup exists. Only the most
 * recent snapshot is kept. Everything is best-effort: any failure is
 * swallowed so startup is never blocked.
 */
object UpgradeBackup {

    private const val MARKER_FILE = "last_seen_version_code"
    private const val BACKUP_DIR = "upgrade-backup"

    private val DB_NAMES = listOf(Key.DB_PROFILE, Key.DB_PUBLIC)
    private val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    fun runIfUpgraded(context: Context) {
        runCatching {
            val current = BuildConfig.VERSION_CODE
            val marker = File(context.filesDir, MARKER_FILE)
            val lastSeen = runCatching { marker.readText().trim().toInt() }.getOrNull()
            if (lastSeen != null && lastSeen >= current) return

            val sources = DB_NAMES.flatMap { name ->
                DB_SUFFIXES.map { suffix -> context.getDatabasePath(name + suffix) }
            }.filter { it.isFile }

            // No database files means a fresh install, not an upgrade:
            // just record the version and skip the snapshot.
            if (sources.isNotEmpty()) {
                val dir = File(context.filesDir, BACKUP_DIR)
                dir.listFiles()?.forEach { it.delete() }
                dir.mkdirs()
                for (src in sources) {
                    // Copy via a temp file so an interrupted copy never
                    // leaves a torn file under the final name.
                    val tmp = File(dir, src.name + ".tmp")
                    src.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(File(dir, src.name))) {
                        src.copyTo(File(dir, src.name), overwrite = true)
                        tmp.delete()
                    }
                }
            }

            // Write the marker last: if anything above failed, the next
            // launch retries the snapshot instead of skipping it forever.
            marker.writeText(current.toString())
        }
    }
}
