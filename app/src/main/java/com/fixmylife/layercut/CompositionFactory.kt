@file:OptIn(UnstableApi::class)

package com.fixmylife.layercut

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a [Project] into a Media3 [Composition].
 *
 * Sequence layout (the compositor draws sequence 0 on top):
 *   0          invisible full-length canvas gap: primary stream, drives timing + output size
 *   1..N       overlay layers, top-most first, padded with gaps to the full length
 *   last       main track, scaled to the canvas
 */
object CompositionFactory {

    private const val IMAGE_FPS = 30

    fun build(project: Project, canvasW: Int, canvasH: Int): Composition? {
        val totalMs = project.totalDurationMs()
        if (totalMs <= 0L) return null
        val av = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)
        val sequences = ArrayList<EditedMediaItemSequence>()

        sequences.add(
            EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addGap(totalMs * 1000).build()
        )

        val overlaySettings = ArrayList<OverlaySettings>()
        for (o in project.overlays) {
            val (ow, oh) = overlayPixelSize(o, canvasW)
            val b = EditedMediaItemSequence.Builder(av)
            if (o.startMs > 0) b.addGap(o.startMs * 1000)
            b.addItem(
                editedItem(o, Presentation.createForWidthAndHeight(ow, oh, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            )
            val tail = totalMs - o.startMs - o.durationMs
            if (tail > 0) b.addGap(tail * 1000)
            sequences.add(b.build())
            overlaySettings.add(
                StaticOverlaySettings.Builder()
                    .setBackgroundFrameAnchor(
                        (o.cx * 2f - 1f).coerceIn(-1f, 1f),
                        (1f - o.cy * 2f).coerceIn(-1f, 1f)
                    )
                    .build()
            )
        }

        val mb = EditedMediaItemSequence.Builder(av)
        for (c in project.main) {
            val layout = if (c.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            mb.addItem(editedItem(c, Presentation.createForWidthAndHeight(canvasW, canvasH, layout)))
        }
        val mainMs = project.mainDurationMs()
        if (totalMs - mainMs > 0) mb.addGap((totalMs - mainMs) * 1000)
        sequences.add(mb.build())

        val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        val plain: OverlaySettings = StaticOverlaySettings.Builder().build()
        val outSize = Size(canvasW, canvasH)
        val compositorSettings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = outSize
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                if (inputId == 0) return hidden
                val idx = inputId - 1
                return if (idx in overlaySettings.indices) overlaySettings[idx] else plain
            }
        }

        return Composition.Builder(sequences)
            .setVideoCompositorSettings(compositorSettings)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    fun overlayPixelSize(o: Clip, canvasW: Int): Pair<Int, Int> {
        val w = (canvasW * o.widthFrac).roundToInt().coerceIn(2, canvasW * 3)
        val h = (w / o.croppedAspect).roundToInt().coerceAtLeast(2)
        return Project.even(w) to Project.even(h)
    }

    private fun editedItem(c: Clip, presentation: Presentation): EditedMediaItem {
        val videoEffects = ArrayList<Effect>()
        if (c.hasCrop) {
            videoEffects.add(Crop(c.cropL * 2f - 1f, c.cropR * 2f - 1f, 1f - c.cropB * 2f, 1f - c.cropT * 2f))
        }
        videoEffects.add(presentation)

        val audioProcessors = ArrayList<AudioProcessor>()
        if (!c.isImage && abs(c.effectiveVolume - 1f) > 0.001f) {
            audioProcessors.add(volumeProcessor(c.effectiveVolume))
        }

        return if (c.isImage) {
            val item = MediaItem.Builder()
                .setUri(Uri.parse(c.uri))
                .setMimeType(c.mime.ifEmpty { null })
                .setImageDurationMs(c.durationMs)
                .build()
            EditedMediaItem.Builder(item)
                .setFrameRate(IMAGE_FPS)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        } else {
            val end = c.trimEndMs.coerceAtMost(c.sourceDurationMs)
            val item = MediaItem.Builder()
                .setUri(Uri.parse(c.uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.trimStartMs)
                        .setEndPositionMs(end)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(item)
                .setDurationUs(c.sourceDurationMs * 1000)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }
    }

    private fun volumeProcessor(volume: Float): AudioProcessor {
        val p = ChannelMixingAudioProcessor()
        for (inCh in 1..6) {
            val m = if (inCh <= 2) {
                ChannelMixingMatrix.createForConstantGain(inCh, 2)
            } else {
                ChannelMixingMatrix.createForConstantPower(inCh, 2)
            }
            p.putChannelMixingMatrix(m.scaleBy(volume))
        }
        return p
    }
}
