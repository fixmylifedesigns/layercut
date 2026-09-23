@file:OptIn(UnstableApi::class)

package com.fixmylife.layercut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException

class Exporter(private val ctx: Context) {

    private var transformer: Transformer? = null

    fun start(
        comp: Composition,
        out: File,
        bitrate: Int,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        val encoderFactory = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    onDone(out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    transformer = null
                    val cause = exportException.cause?.message?.let { "\n$it" } ?: ""
                    onError((exportException.message ?: exportException.toString()) + cause)
                }
            })
            .build()
        transformer = t
        t.start(comp, out.absolutePath)
    }

    /** 0..100, or -1 if unknown. */
    fun progress(): Int {
        val t = transformer ?: return -1
        val h = ProgressHolder()
        return if (t.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) h.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    companion object {
        fun saveToGallery(ctx: Context, file: File): Uri? {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LayerCut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("no output stream")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }
    }
}
