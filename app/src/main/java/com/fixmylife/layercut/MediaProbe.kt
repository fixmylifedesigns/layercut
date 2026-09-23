package com.fixmylife.layercut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri

object MediaProbe {

    const val DEFAULT_IMAGE_MS = 3000L

    fun probe(ctx: Context, uri: Uri): Clip? {
        val mime = ctx.contentResolver.getType(uri) ?: ""
        return if (mime.startsWith("image/")) probeImage(ctx, uri, mime) else probeVideo(ctx, uri, mime)
    }

    private fun exifOrientation(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun probeImage(ctx: Context, uri: Uri, mime: String): Clip? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            return null
        }
        var w = opts.outWidth
        var h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        if (exifOrientation(ctx, uri) in 5..8) { val t = w; w = h; h = t }
        return Clip(
            uri = uri.toString(), mime = mime, isImage = true, sourceDurationMs = 0L,
            width = w, height = h, hasAudio = false, trimStartMs = 0L, trimEndMs = DEFAULT_IMAGE_MS,
        )
    }

    private fun probeVideo(ctx: Context, uri: Uri, mime: String): Clip? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            return Clip(
                uri = uri.toString(), mime = mime.ifEmpty { "video/mp4" }, isImage = false,
                sourceDurationMs = dur, width = w, height = h, hasAudio = hasAudio,
                trimStartMs = 0L, trimEndMs = dur,
            )
        } catch (e: Exception) {
            return null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** A frame of the clip at [atMs] (source time), long side about [maxSide]. */
    fun frame(ctx: Context, clip: Clip, atMs: Long, maxSide: Int): Bitmap? {
        val uri = Uri.parse(clip.uri)
        return try {
            if (clip.isImage) decodeImage(ctx, uri, maxSide) else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    val scale = maxSide.toFloat() / maxOf(clip.width, clip.height)
                    val tw = maxOf(2, (clip.width * scale).toInt())
                    val th = maxOf(2, (clip.height * scale).toInt())
                    r.getScaledFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
                        ?: r.getFrameAtTime(atMs * 1000)
                } finally {
                    try { r.release() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val m = Matrix()
        when (exifOrientation(ctx, uri)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
