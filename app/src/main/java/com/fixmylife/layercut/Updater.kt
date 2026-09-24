package com.fixmylife.layercut

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app up to date from GitHub Releases.
 * [autoUpdate] runs on launch: if a newer build exists it downloads and installs it without asking.
 */
object Updater {

    // The GitHub repo is still named "layercut"; the app itself is FixMyCut.
    private const val LATEST = "https://api.github.com/repos/fixmylifedesigns/layercut/releases/latest"
    private const val UA = "FixMyCut-Android"

    class Release(val build: Int, val apkUrl: String)

    private var pendingInstall: File? = null
    private var busy = false

    fun currentBuild(): Int = BuildConfig.VERSION_CODE

    private fun fetchLatest(): Release? = try {
        val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (c.responseCode != 200) {
                null
            } else {
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val build = o.optString("tag_name").substringAfter("build-", "").toIntOrNull()
                var url: String? = null
                val assets = o.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (build == null || url == null) null else Release(build, url)
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun alive(act: AppCompatActivity) = !act.isFinishing && !act.isDestroyed

    /** Silent launch-time update: downloads and installs a newer build without asking. */
    fun autoUpdate(act: AppCompatActivity) {
        if (busy) return
        Thread {
            val r = fetchLatest()
            act.runOnUiThread {
                if (!alive(act) || r == null || r.build <= currentBuild()) return@runOnUiThread
                Toast.makeText(act, "Updating FixMyCut to build ${r.build}\u2026", Toast.LENGTH_LONG).show()
                download(act, r, showDialog = false)
            }
        }.start()
    }

    /** Manual check from a button. [silent] = only speak up when an update exists. */
    fun check(act: AppCompatActivity, silent: Boolean) {
        if (!silent) Toast.makeText(act, "Checking for updates\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            val r = fetchLatest()
            act.runOnUiThread {
                if (!alive(act)) return@runOnUiThread
                when {
                    r == null -> if (!silent) Toast.makeText(act, "Couldn't reach GitHub", Toast.LENGTH_LONG).show()
                    r.build > currentBuild() -> MaterialAlertDialogBuilder(act)
                        .setTitle("Update available")
                        .setMessage("Build ${r.build} is out (you have build ${currentBuild()}). Install it now? Your projects are kept.")
                        .setPositiveButton("Update") { _, _ -> download(act, r, showDialog = true) }
                        .setNegativeButton("Later", null)
                        .show()
                    else -> if (!silent) Toast.makeText(act, "You're on the latest build (${currentBuild()})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun download(act: AppCompatActivity, r: Release, showDialog: Boolean) {
        if (busy) return
        busy = true
        var bar: LinearProgressIndicator? = null
        var dlg: AlertDialog? = null
        if (showDialog) {
            val d = act.resources.displayMetrics.density
            val b = LinearProgressIndicator(act).apply { max = 100; isIndeterminate = true }
            val box = FrameLayout(act).apply {
                setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
                addView(b)
            }
            bar = b
            dlg = MaterialAlertDialogBuilder(act)
                .setTitle("Downloading build ${r.build}\u2026")
                .setView(box)
                .setCancelable(false)
                .show()
        }
        val progressBar = bar
        val progressDialog = dlg
        Thread {
            val file: File? = try {
                val dir = File(act.cacheDir, "update").apply { mkdirs() }
                val out = File(dir, "FixMyCut.apk")
                val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            o.write(buf, 0, read)
                            done += read
                            if (progressBar != null && total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    act.runOnUiThread { progressBar.isIndeterminate = false; progressBar.setProgressCompat(pct, true) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && out.length() != total) null else out
            } catch (e: Exception) {
                null
            }
            act.runOnUiThread {
                busy = false
                progressDialog?.dismiss()
                if (!alive(act)) return@runOnUiThread
                if (file == null) Toast.makeText(act, "Update download failed", Toast.LENGTH_LONG).show() else install(act, file)
            }
        }.start()
    }

    private fun install(act: AppCompatActivity, file: File) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            MaterialAlertDialogBuilder(act)
                .setTitle("One-time permission")
                .setMessage("To update itself, FixMyCut needs permission to install apps. Turn on \"Allow from this source\" and come back; the update will continue.")
                .setPositiveButton("Open settings") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        pendingInstall = null
        if (!installWithSession(act, file)) installWithIntent(act, file)
    }

    /**
     * PackageInstaller session. On Android 12+, once FixMyCut has installed itself once,
     * later updates can go through without a confirmation screen.
     */
    private fun installWithSession(act: AppCompatActivity, file: File): Boolean = try {
        val installer = act.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(act.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(act, sessionId, Intent(act, UpdateReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun installWithIntent(act: AppCompatActivity, file: File) {
        val uri = FileProvider.getUriForFile(act, act.packageName + ".files", file)
        act.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Call from onResume: finishes an install that was waiting on the permission. */
    fun resumePending(act: AppCompatActivity) {
        val f = pendingInstall ?: return
        if (f.exists() && act.packageManager.canRequestPackageInstalls()) install(act, f)
    }
}
