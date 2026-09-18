/*
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Offers new AnkiDroid Quest builds from the GitHub releases of the fork.
 *
 * Releases are tagged `quest-<n>` and CI bakes `<n>` into [BuildConfig.ANKIQUEST_RELEASE].
 * Local builds carry 0 and never check.
 */
object AnkiquestUpdater {
    private const val LATEST_URL = "https://api.github.com/repos/float3/Anki-Android/releases/latest"
    private const val TAG_PREFIX = "quest-"
    private const val CHECKED_AT_KEY = "ankiquestUpdateCheckedAt"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    @Volatile
    private var busy = false

    private data class Release(
        val number: Int,
        val name: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    fun maybeCheck(activity: Activity) {
        if (BuildConfig.ANKIQUEST_RELEASE <= 0 || busy || activity !is DeckPicker) return
        val prefs = AnkiDroidApp.sharedPrefs()
        val now = TimeManager.time.intTimeMS()
        if (now - prefs.getLong(CHECKED_AT_KEY, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit { putLong(CHECKED_AT_KEY, now) }
        busy = true
        val host = WeakReference(activity)
        scope.launch {
            try {
                val release = latest()
                if (release != null && release.number > BuildConfig.ANKIQUEST_RELEASE) {
                    withContext(Dispatchers.Main) { host.get()?.let { offer(it, release) } }
                }
            } catch (e: Exception) {
                Timber.w(e, "ankiquest update check failed")
            } finally {
                busy = false
            }
        }
    }

    private fun latest(): Release? {
        val request =
            Request
                .Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
        val json =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                JSONObject(response.body.string())
            }
        val tag = json.getString("tag_name")
        val number = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: return null
        val assets = json.getJSONArray("assets")
        val apk =
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(
            number = number,
            name = json.optString("name").ifEmpty { tag },
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
        )
    }

    private fun offer(
        activity: Activity,
        release: Release,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog
            .Builder(activity)
            .setTitle(activity.getString(R.string.ankiquest_update_title, release.name))
            .setMessage(activity.getString(R.string.ankiquest_update_message, release.apkSize / (1024 * 1024)))
            .setPositiveButton(R.string.ankiquest_update_install) { _, _ -> download(activity, release) }
            .setNegativeButton(R.string.ankiquest_update_later, null)
            .show()
    }

    private fun download(
        activity: Activity,
        release: Release,
    ) {
        val progress =
            AlertDialog
                .Builder(activity)
                .setTitle(activity.getString(R.string.ankiquest_update_title, release.name))
                .setMessage(activity.getString(R.string.ankiquest_update_downloading, 0))
                .setCancelable(false)
                .show()
        val app = activity.applicationContext
        val host = WeakReference(activity)
        busy = true
        scope.launch {
            try {
                val file = File(File(app.cacheDir, "ankiquest").apply { mkdirs() }, "AnkiDroid-Quest.apk")
                fetch(release, file) { percent ->
                    withContext(Dispatchers.Main) {
                        progress.setMessage(app.getString(R.string.ankiquest_update_downloading, percent))
                    }
                }
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.apkgfileprovider", file)
                    val install =
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    (host.get() ?: app).startActivity(install)
                }
            } catch (e: Exception) {
                Timber.w(e, "ankiquest update download failed")
                withContext(Dispatchers.Main) {
                    progress.dismiss()
                    host.get()?.let {
                        AlertDialog
                            .Builder(it)
                            .setMessage(R.string.ankiquest_update_failed)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun fetch(
        release: Release,
        file: File,
        onProgress: suspend (Int) -> Unit,
    ) {
        val partial = File(file.path + ".part")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            check(response.isSuccessful) { "download returned ${response.code}" }
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var shown = -1
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            check(total <= 0 || done == total) { "download incomplete: $done of $total" }
        }
        file.delete()
        check(partial.renameTo(file)) { "cannot move the download into place" }
    }
}
