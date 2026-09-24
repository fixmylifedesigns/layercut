package com.fixmylife.layercut

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer build and installs it. */
object Updater {

    private const val LATEST = "https://api.github.com/repos/fixmylifedesigns/layercut/releases/latest"

    class Release(val build: Int, val apkUrl: String)

    private var pendingInstall: File? = null

    fun currentBuild(): Int = BuildConfig.VERSION_CODE

    private fun fetchLatest(): Release? = try {
        val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LayerCut-Android")
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

    /** [silent] = only speak up when an update exists. */
    fun check(act: AppCompatActivity, silent: Boolean) {
        if (!silent) Toast.makeText(act, "Checking for updates\u2026", Toast.LENGTH_SHORT).show()
        Thread {
            val r = fetchLatest()
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                when {
                    r == null -> if (!silent) Toast.makeText(act, "Couldn't reach GitHub", Toast.LENGTH_LONG).show()
                    r.build > currentBuild() -> MaterialAlertDialogBuilder(act)
                        .setTitle("Update available")
                        .setMessage("Build ${r.build} is out (you have build ${currentBuild()}). Download and install it now? Your projects are kept.")
                        .setPositiveButton("Update") { _, _ -> download(act, r) }
                        .setNegativeButton("Later", null)
                        .show()
                    else -> if (!silent) Toast.makeText(act, "You're on the latest build (${currentBuild()})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun download(act: AppCompatActivity, r: Release) {
        val d = act.resources.displayMetrics.density
        val bar = LinearProgressIndicator(act).apply { max = 100; isIndeterminate = true }
        val box = FrameLayout(act).apply {
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
            addView(bar)
        }
        val dlg = MaterialAlertDialogBuilder(act)
            .setTitle("Downloading build ${r.build}\u2026")
            .setView(box)
            .setCancelable(false)
            .show()
        Thread {
            val file: File? = try {
                val dir = File(act.cacheDir, "update").apply { mkdirs() }
                val out = File(dir, "LayerCut.apk")
                val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "LayerCut-Android")
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
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    act.runOnUiThread { bar.isIndeterminate = false; bar.setProgressCompat(pct, true) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                out
            } catch (e: Exception) {
                null
            }
            act.runOnUiThread {
                dlg.dismiss()
                if (file == null) Toast.makeText(act, "Download failed", Toast.LENGTH_LONG).show() else install(act, file)
            }
        }.start()
    }

    private fun install(act: AppCompatActivity, file: File) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            MaterialAlertDialogBuilder(act)
                .setTitle("One-time permission")
                .setMessage("To update itself, LayerCut needs permission to install apps. Turn on \"Allow from this source\" and come back; the install will continue.")
                .setPositiveButton("Open settings") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        pendingInstall = null
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
