@file:OptIn(UnstableApi::class)

package com.fixmylife.layercut

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.IOException

/** Pulls the audio track out of a video and saves it as an .m4a in Music/LayerCut. */
object AudioExtractor {

    fun runWithUi(
        act: AppCompatActivity,
        uri: Uri,
        startMs: Long,
        endMs: Long?,
        baseName: String,
        onFinished: (Boolean) -> Unit,
    ) {
        val safe = baseName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "audio" }
        val out = File(act.cacheDir, "${safe}_${System.currentTimeMillis()}.m4a")
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
        if (endMs != null) clipping.setEndPositionMs(endMs)
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping.build()).build()
        val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()

        val d = act.resources.displayMetrics.density
        val box = FrameLayout(act).apply {
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), 0)
            addView(LinearProgressIndicator(act).apply { isIndeterminate = true })
        }
        lateinit var dlg: AlertDialog
        val transformer = Transformer.Builder(act)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val saved = saveToMusic(act, out)
                    out.delete()
                    dlg.dismiss()
                    if (saved == null) {
                        Toast.makeText(act, "Couldn't save the audio file", Toast.LENGTH_LONG).show()
                        onFinished(false)
                    } else {
                        onFinished(true)
                        doneDialog(act, saved)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    dlg.dismiss()
                    out.delete()
                    onFinished(false)
                    MaterialAlertDialogBuilder(act)
                        .setTitle("Couldn't extract audio")
                        .setMessage("Does this video have sound?\n\n${exportException.message ?: exportException}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            })
            .build()
        dlg = MaterialAlertDialogBuilder(act)
            .setTitle("Extracting audio\u2026")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                transformer.cancel()
                out.delete()
                onFinished(false)
            }
            .show()
        try {
            transformer.start(edited, out.absolutePath)
        } catch (e: Exception) {
            dlg.dismiss()
            onFinished(false)
            Toast.makeText(act, "Couldn't extract audio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun doneDialog(act: AppCompatActivity, uri: Uri) {
        MaterialAlertDialogBuilder(act)
            .setTitle("Audio saved")
            .setMessage("Saved to Music/LayerCut.")
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                act.startActivity(Intent.createChooser(send, "Share audio"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    fun saveToMusic(ctx: Context, file: File): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LayerCut")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                ?: throw IOException("no output stream")
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
