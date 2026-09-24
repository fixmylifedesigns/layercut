@file:OptIn(UnstableApi::class)

package com.fixmylife.layercut

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The editor. Opened from [HomeActivity] with a project id. */
class MainActivity : AppCompatActivity(), TimelineView.Listener, OverlayEditView.Listener {

    companion object {
        const val EXTRA_ID = "projectId"
        const val EXTRA_AUTOPICK = "autoPick"
    }

    private val project = Project()
    private lateinit var projectId: String
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var engine: PreviewEngine
    private lateinit var canvasView: FrameLayout
    private lateinit var previewFrame: AspectFrameLayout
    private lateinit var timeline: TimelineView
    private lateinit var overlayView: OverlayEditView
    private lateinit var timeText: TextView
    private lateinit var playBtn: TextView
    private lateinit var toolRow: LinearLayout
    private lateinit var aspectBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var emptyHint: TextView
    private lateinit var statusText: TextView
    private var selectedId: String? = null
    private var positionMs = 0L
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var addAsOverlay = false
    private val loadingThumbs = HashSet<String>()
    private var exporter: Exporter? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(uris)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectId = intent.getStringExtra(EXTRA_ID) ?: ProjectStore.create(this)
        loadProject()
        buildUi()
        engine = PreviewEngine(this, canvasView).also { e ->
            e.project = project
            e.onTime = { t ->
                positionMs = t
                syncTime()
            }
            e.onPlayingChanged = { playing ->
                playBtn.text = if (playing) "\u275A\u275A" else "\u25B6"
            }
            e.onStatus = { s ->
                statusText.text = s ?: ""
                statusText.visibility = if (s == null) View.GONE else View.VISIBLE
            }
        }
        refreshAll()
        rebuildPreview()
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTOPICK, false) && project.totalDurationMs() == 0L) {
            pick(false)
        }
    }

    override fun onStop() {
        super.onStop()
        if (::engine.isInitialized) engine.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exporter?.cancel()
        if (::engine.isInitialized) engine.release()
        io.shutdown()
    }

    // ------------------------------------------------------------ UI

    private fun selectable(v: View) {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        v.setBackgroundResource(tv.resourceId)
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        selectable(this)
        setOnClickListener { onClick() }
    }

    private fun toolBtn(icon: String, label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        minimumWidth = dp(64)
        addView(TextView(context).apply {
            text = icon; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            text = label; textSize = 11f; setTextColor(Color.parseColor("#B8BCC6")); gravity = Gravity.CENTER
        })
        selectable(this)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0F12"))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(6), dp(8), dp(6))
        }
        top.addView(smallBtn("\u2039") { finish() }.apply { textSize = 26f })
        titleView = TextView(this).apply {
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setOnClickListener { renameDialog() }
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(smallBtn("\u21B6") { doUndo() })
        top.addView(smallBtn("\u21B7") { doRedo() })
        aspectBtn = smallBtn(project.aspect) { chooseAspect() }
        top.addView(aspectBtn)
        top.addView(smallBtn("\u22EF") { moreMenu() })
        top.addView(MaterialButton(this).apply {
            text = "Export"
            setOnClickListener { chooseExport() }
        })
        root.addView(top)

        val previewBox = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#08090B")) }
        previewFrame = AspectFrameLayout(this).apply { aspect = project.aspectRatio() }
        canvasView = FrameLayout(this)
        previewFrame.addView(canvasView, FrameLayout.LayoutParams(match, match))
        overlayView = OverlayEditView(this).also { it.project = project; it.listener = this }
        previewFrame.addView(overlayView, FrameLayout.LayoutParams(match, match))
        previewBox.addView(previewFrame, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        emptyHint = TextView(this).apply {
            text = "Tap  \uFF0B Media  to add your main video\nthen  Overlay  to layer videos or images on top"
            setTextColor(Color.parseColor("#8A8F9B"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        previewBox.addView(emptyHint, FrameLayout.LayoutParams(match, match))
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#CC202329"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }
        previewBox.addView(statusText, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = dp(8); leftMargin = dp(8); rightMargin = dp(8) })
        root.addView(previewBox, LinearLayout.LayoutParams(match, 0, 1f))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(12), dp(2))
        }
        playBtn = smallBtn("\u25B6") { togglePlay() }.apply { textSize = 20f }
        transport.addView(playBtn)
        timeText = TextView(this).apply {
            setTextColor(Color.parseColor("#B8BCC6")); textSize = 13f; typeface = Typeface.MONOSPACE
        }
        transport.addView(timeText)
        root.addView(transport)

        timeline = TimelineView(this).also { it.project = project; it.listener = this }
        root.addView(timeline, LinearLayout.LayoutParams(match, dp(200)))

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#15171C"))
        }
        toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(12))
        }
        scroll.addView(toolRow)
        root.addView(scroll, LinearLayout.LayoutParams(match, wrap))
        setContentView(root)
    }

    private fun refreshTools() {
        toolRow.removeAllViews()
        val c = project.find(selectedId)
        if (c == null) {
            toolRow.addView(toolBtn("\uFF0B", "Media") { pick(false) })
            toolRow.addView(toolBtn("\u29C9", "Overlay") { pick(true) })
            toolRow.addView(toolBtn("\u25AD", "Canvas") { chooseAspect() })
            toolRow.addView(toolBtn("\u2913", "Export") { chooseExport() })
            return
        }
        val ov = project.isOverlay(c.id)
        toolRow.addView(toolBtn("\u2715", "Done") { select(null) })
        if (!c.isImage) {
            toolRow.addView(toolBtn("\uD83D\uDD0A", "Volume") { volumeDialog(c) })
            toolRow.addView(toolBtn("\u266B", "Extract audio") { extractAudio(c) })
        }
        toolRow.addView(toolBtn("\u2702", "Crop") { cropDialog(c) })
        toolRow.addView(toolBtn("\u23F1", if (c.isImage) "Duration" else "Trim") { trimDialog(c) })
        toolRow.addView(toolBtn("\u2AFD", "Split") { split(c) })
        if (ov) {
            toolRow.addView(toolBtn("\u2295", "Center") { edit { c.cx = 0.5f; c.cy = 0.5f } })
            toolRow.addView(toolBtn("\u26F6", "Full size") { edit { c.cx = 0.5f; c.cy = 0.5f; c.widthFrac = fullWidthFrac(c) } })
            toolRow.addView(toolBtn("\u2191", "Layer up") { moveLayer(c, -1) })
            toolRow.addView(toolBtn("\u2193", "Layer down") { moveLayer(c, 1) })
            toolRow.addView(toolBtn("\u21E9", "To main") { toMain(c) })
        } else {
            toolRow.addView(toolBtn(if (c.fill) "\u2B1A" else "\u2B1B", if (c.fill) "Fit" else "Fill") { edit { c.fill = !c.fill } })
            toolRow.addView(toolBtn("\u25C0", "Move left") { moveMain(c, -1) })
            toolRow.addView(toolBtn("\u25B6", "Move right") { moveMain(c, 1) })
            toolRow.addView(toolBtn("\u21E7", "To overlay") { toOverlay(c) })
        }
        toolRow.addView(toolBtn("\u2750", "Duplicate") { duplicate(c) })
        toolRow.addView(toolBtn("\uD83D\uDDD1", "Delete") { delete(c) })
    }

    private fun refreshAll() {
        if (project.find(selectedId) == null) selectedId = null
        timeline.selectedId = selectedId
        overlayView.selectedId = if (project.isOverlay(selectedId)) selectedId else null
        titleView.text = project.name
        aspectBtn.text = project.aspect
        previewFrame.aspect = project.aspectRatio()
        val total = project.totalDurationMs()
        emptyHint.visibility = if (total > 0) View.GONE else View.VISIBLE
        positionMs = positionMs.coerceIn(0L, maxOf(0L, total))
        syncTime()
        refreshTools()
        loadThumbs()
        timeline.invalidate()
        overlayView.invalidate()
    }

    private fun updateTimeText() {
        timeText.text = "${TimelineView.formatTime(positionMs, true)} / ${TimelineView.formatTime(project.totalDurationMs(), true)}"
    }

    private fun syncTime() {
        timeline.setTime(positionMs)
        overlayView.timeMs = positionMs
        updateTimeText()
    }

    private fun loadThumbs() {
        for (c in project.main + project.overlays) {
            if (timeline.thumbs.containsKey(c.id) || loadingThumbs.contains(c.id)) continue
            loadingThumbs.add(c.id)
            val id = c.id
            val clip = c
            io.execute {
                val b = MediaProbe.frame(this, clip, clip.trimStartMs, 160)
                ui.post {
                    loadingThumbs.remove(id)
                    if (b != null) {
                        timeline.thumbs[id] = b
                        timeline.invalidate()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ state

    private fun saveProject() {
        try { ProjectStore.save(this, projectId, project) } catch (_: Exception) {}
    }

    private fun loadProject() {
        ProjectStore.loadInto(this, projectId, project)
    }

    private fun snapshot() {
        undo.addLast(project.toJson().toString())
        if (undo.size > 60) undo.removeFirst()
        redo.clear()
    }

    private fun commit() {
        saveProject()
        refreshAll()
        schedulePreview()
    }

    private fun edit(block: () -> Unit) {
        snapshot()
        block()
        commit()
    }

    private fun doUndo() {
        if (undo.isEmpty()) { toast("Nothing to undo"); return }
        redo.addLast(project.toJson().toString())
        project.loadFrom(org.json.JSONObject(undo.removeLast()))
        commit()
    }

    private fun doRedo() {
        if (redo.isEmpty()) { toast("Nothing to redo"); return }
        undo.addLast(project.toJson().toString())
        project.loadFrom(org.json.JSONObject(redo.removeLast()))
        commit()
    }

    private fun select(id: String?) {
        selectedId = id
        timeline.selectedId = id
        overlayView.selectedId = if (project.isOverlay(id)) id else null
        refreshTools()
    }

    // ------------------------------------------------------------ preview

    private val previewRunnable = Runnable { rebuildPreview() }

    private fun schedulePreview() {
        ui.removeCallbacks(previewRunnable)
        ui.postDelayed(previewRunnable, 60)
    }

    private fun rebuildPreview() {
        engine.rebuild()
        engine.seekTo(positionMs)
    }

    private fun togglePlay() {
        if (project.totalDurationMs() <= 0L) return
        if (engine.isPlaying) {
            engine.pause()
        } else {
            engine.seekTo(positionMs)
            engine.play()
        }
    }

    // ------------------------------------------------------------ listeners

    override fun onScrub(ms: Long) {
        if (engine.isPlaying) engine.pause()
        positionMs = ms
        overlayView.timeMs = ms
        updateTimeText()
        engine.seekTo(ms)
    }

    override fun onSelect(id: String?) = select(id)

    override fun onEditBegin() = snapshot()

    override fun onEdited() = commit()

    override fun onLiveChange() = engine.refreshLayout()

    // ------------------------------------------------------------ media import

    private fun pick(overlay: Boolean) {
        addAsOverlay = overlay
        picker.launch(arrayOf("video/*", "image/*"))
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val asOverlay = addAsOverlay
        toast("Importing ${uris.size} item(s)\u2026")
        io.execute {
            val clips = uris.mapNotNull { u ->
                try {
                    contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {}
                MediaProbe.probe(this, u)
            }
            ui.post {
                if (clips.size < uris.size) toast("${uris.size - clips.size} file(s) couldn't be read")
                if (clips.isNotEmpty()) {
                    snapshot()
                    if (asOverlay) {
                        for (c in clips) {
                            c.startMs = positionMs
                            c.widthFrac = 0.5f
                            project.overlays.add(0, c)
                        }
                    } else {
                        project.main.addAll(clips)
                    }
                    selectedId = clips.last().id
                    commit()
                }
            }
        }
    }

    // ------------------------------------------------------------ clip actions

    private fun fullWidthFrac(c: Clip): Float {
        val canvasAspect = project.aspectRatio()
        val a = c.croppedAspect
        return if (a >= canvasAspect) 1f else a / canvasAspect
    }

    private fun moveLayer(c: Clip, dir: Int) {
        val i = project.overlays.indexOf(c)
        val j = i + dir
        if (i < 0 || j < 0 || j >= project.overlays.size) {
            toast(if (dir < 0) "Already the top layer" else "Already the bottom overlay layer")
            return
        }
        edit { Collections.swap(project.overlays, i, j) }
    }

    private fun moveMain(c: Clip, dir: Int) {
        val i = project.main.indexOf(c)
        val j = i + dir
        if (i < 0 || j < 0 || j >= project.main.size) return
        edit { Collections.swap(project.main, i, j) }
    }

    private fun toMain(c: Clip) = edit {
        project.overlays.remove(c)
        c.startMs = 0L
        project.main.add(c)
    }

    private fun toOverlay(c: Clip) = edit {
        project.main.remove(c)
        c.startMs = positionMs.coerceAtLeast(0L)
        c.cx = 0.5f; c.cy = 0.5f; c.widthFrac = 0.5f
        project.overlays.add(0, c)
    }

    private fun duplicate(c: Clip) {
        val b = c.duplicate()
        edit {
            if (project.isOverlay(c.id)) {
                b.startMs = c.startMs + c.durationMs
                project.overlays.add(project.overlays.indexOf(c), b)
            } else {
                project.main.add(project.main.indexOf(c) + 1, b)
            }
            selectedId = b.id
        }
    }

    private fun delete(c: Clip) = edit {
        project.main.remove(c)
        project.overlays.remove(c)
        selectedId = null
    }

    private fun split(c: Clip) {
        val ov = project.isOverlay(c.id)
        val start = if (ov) c.startMs else project.mainStartOf(c)
        val off = positionMs - start
        if (off < 150 || off > c.durationMs - 150) {
            toast("Move the playhead inside the selected clip to split it")
            return
        }
        snapshot()
        val b = c.duplicate()
        if (c.isImage) {
            b.trimStartMs = 0L
            b.trimEndMs = c.durationMs - off
            c.trimStartMs = 0L
            c.trimEndMs = off
        } else {
            val cut = c.trimStartMs + off
            b.trimStartMs = cut
            c.trimEndMs = cut
        }
        if (ov) {
            b.startMs = start + off
            project.overlays.add(project.overlays.indexOf(c), b)
        } else {
            project.main.add(project.main.indexOf(c) + 1, b)
        }
        selectedId = b.id
        commit()
    }

    private fun extractAudio(c: Clip) {
        if (!c.hasAudio) { toast("This clip has no audio track"); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Extract audio")
            .setItems(arrayOf("Save audio file", "Save audio file and mute this clip")) { _, which ->
                val end = if (c.trimEndMs >= c.sourceDurationMs - 50) null else c.trimEndMs
                engine.pause()
                AudioExtractor.runWithUi(this, Uri.parse(c.uri), c.trimStartMs, end, project.name + "_audio") { ok ->
                    if (ok && which == 1) edit { c.muted = true }
                }
            }
            .show()
    }

    // ------------------------------------------------------------ dialogs

    private fun dialogBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(4))
    }

    private fun renameDialog() {
        val box = dialogBox()
        val input = EditText(this).apply { setText(project.name); setSelection(text.length) }
        box.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename project")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) edit { project.name = n }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun volumeDialog(c: Clip) {
        if (!c.hasAudio) toast("This clip has no audio track")
        val box = dialogBox()
        val label = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 200f
            stepSize = 1f
            value = (c.volume * 100).roundToInt().coerceIn(0, 200).toFloat()
        }
        val mute = MaterialSwitch(this).apply { text = "Mute"; isChecked = c.muted }
        val update = {
            label.text = if (mute.isChecked) "Muted" else "Volume ${slider.value.toInt()}%"
        }
        slider.addOnChangeListener { _, _, _ -> update() }
        mute.setOnCheckedChangeListener { _, _ -> update() }
        update()
        box.addView(label)
        box.addView(slider)
        box.addView(mute)
        box.addView(TextView(this).apply {
            text = "Preview plays up to 100%. Boosts above 100% are applied in the export."
            textSize = 12f; setTextColor(Color.parseColor("#8A8F9B"))
        })
        MaterialAlertDialogBuilder(this)
            .setTitle("Clip volume")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                edit { c.volume = slider.value / 100f; c.muted = mute.isChecked }
            }
            .setNeutralButton("Reset") { _, _ -> edit { c.volume = 1f; c.muted = false } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun trimDialog(c: Clip) {
        val box = dialogBox()
        val label = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        box.addView(label)
        if (c.isImage) {
            val cur = c.durationMs / 1000f
            val slider = Slider(this).apply {
                valueFrom = 0.5f
                valueTo = maxOf(30f, cur)
                value = cur.coerceIn(0.5f, maxOf(30f, cur))
            }
            val update = { label.text = "Show for %.1f s".format(slider.value) }
            slider.addOnChangeListener { _, _, _ -> update() }
            update()
            box.addView(slider)
            MaterialAlertDialogBuilder(this)
                .setTitle("Image duration")
                .setView(box)
                .setPositiveButton("Apply") { _, _ ->
                    edit { c.trimStartMs = 0L; c.trimEndMs = (slider.value * 1000).toLong() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            val maxS = c.sourceDurationMs / 1000f
            val range = RangeSlider(this).apply {
                valueFrom = 0f
                valueTo = maxS
                setValues((c.trimStartMs / 1000f).coerceIn(0f, maxS), (c.trimEndMs / 1000f).coerceIn(0f, maxS))
            }
            val update = {
                val v = range.values
                label.text = "%.1fs \u2192 %.1fs   (%.1fs)".format(v[0], v[1], v[1] - v[0])
            }
            range.addOnChangeListener { _, _, _ -> update() }
            update()
            box.addView(range)
            MaterialAlertDialogBuilder(this)
                .setTitle("Trim")
                .setView(box)
                .setPositiveButton("Apply") { _, _ ->
                    val v = range.values
                    val s = (v[0] * 1000).toLong().coerceIn(0L, c.sourceDurationMs)
                    val e = (v[1] * 1000).toLong().coerceIn(0L, c.sourceDurationMs)
                    if (e - s < 200) toast("Clip must be at least 0.2s") else edit { c.trimStartMs = s; c.trimEndMs = e }
                }
                .setNeutralButton("Reset") { _, _ -> edit { c.trimStartMs = 0L; c.trimEndMs = c.sourceDurationMs } }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun cropDialog(c: Clip) {
        io.execute {
            val bmp = MediaProbe.frame(this, c, c.trimStartMs, 1080)
            ui.post {
                if (bmp == null) {
                    toast("Couldn't load a frame to crop")
                } else {
                    showCrop(c, bmp)
                }
            }
        }
    }

    private fun showCrop(c: Clip, bmp: android.graphics.Bitmap) {
        val cv = CropView(this, bmp).apply { l = c.cropL; t = c.cropT; r = c.cropR; b = c.cropB }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        val chipsScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
        val presets = listOf<Pair<String, Float?>>(
            "Free" to null, "1:1" to 1f, "9:16" to 9f / 16f, "16:9" to 16f / 9f, "4:5" to 0.8f,
            "Canvas" to project.aspectRatio()
        )
        for ((name, ratio) in presets) chips.addView(smallBtn(name) { cv.setRatio(ratio) })
        chips.addView(smallBtn("Reset") { cv.reset() })
        chipsScroll.addView(chips)
        box.addView(chipsScroll)
        MaterialAlertDialogBuilder(this)
            .setTitle("Crop")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                edit { c.cropL = cv.l; c.cropT = cv.t; c.cropR = cv.r; c.cropB = cv.b }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun chooseAspect() {
        val labels = arrayOf(
            "9:16  \u00B7  Reels / TikTok / Shorts", "16:9  \u00B7  YouTube", "1:1  \u00B7  Square",
            "4:5  \u00B7  Instagram post", "3:4"
        )
        val values = arrayOf("9:16", "16:9", "1:1", "4:5", "3:4")
        MaterialAlertDialogBuilder(this)
            .setTitle("Canvas")
            .setItems(labels) { _, which -> edit { project.aspect = values[which] } }
            .show()
    }

    private fun moreMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle("LayerCut \u00B7 build ${Updater.currentBuild()}")
            .setItems(arrayOf("Rename project", "Check for updates", "Tips")) { _, which ->
                when (which) {
                    0 -> renameDialog()
                    1 -> Updater.check(this, silent = false)
                    else -> MaterialAlertDialogBuilder(this)
                        .setTitle("Tips")
                        .setMessage(
                            "\u2022 Drag the timeline to scrub, pinch it to zoom.\n" +
                                "\u2022 Tap a clip to select it; drag its white edges to trim.\n" +
                                "\u2022 Long-press any clip and drag it to rearrange: along the main row to reorder, " +
                                "up into the layer rows to make it an overlay, or down onto the main row to make it a main clip.\n" +
                                "\u2022 On the preview, drag an overlay to move it, pinch or drag its corner to resize.\n" +
                                "\u2022 Split cuts the selected clip at the playhead."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    // ------------------------------------------------------------ export

    private fun chooseExport() {
        if (project.totalDurationMs() <= 0L) { toast("Add some media first"); return }
        val opts = arrayOf("1080p (Full HD)", "4K (Ultra HD)")
        var choice = 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Export video")
            .setSingleChoiceItems(opts, 0) { _, which -> choice = which }
            .setPositiveButton("Export") { _, _ -> startExport(if (choice == 0) 1080 else 2160) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startExport(shortSide: Int) {
        engine.releasePlayers()
        val (w, h) = project.canvasSize(shortSide)
        val comp = try { CompositionFactory.build(project, w, h) } catch (e: Exception) { null }
        if (comp == null) { toast("Nothing to export"); rebuildPreview(); return }
        val safeName = project.name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        val out = File(cacheDir, "${safeName}_${System.currentTimeMillis()}.mp4")
        val bitrate = if (shortSide >= 2160) 45_000_000 else 16_000_000

        val box = dialogBox()
        val label = TextView(this).apply { text = "Starting\u2026"; setTextColor(Color.WHITE) }
        val bar = LinearProgressIndicator(this).apply { max = 100; isIndeterminate = false }
        box.addView(label)
        box.addView(bar)
        val ex = Exporter(this)
        exporter = ex
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Exporting ${w}\u00D7${h}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                ex.cancel()
                exporter = null
                out.delete()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                rebuildPreview()
            }
            .show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val poll = object : Runnable {
            override fun run() {
                if (!dlg.isShowing || exporter !== ex) return
                val p = ex.progress()
                if (p >= 0) {
                    bar.setProgressCompat(p, true)
                    label.text = "$p%  \u00B7  keep the app open"
                }
                ui.postDelayed(this, 400)
            }
        }

        try {
            ex.start(
                comp, out, bitrate,
                onDone = { f ->
                    exporter = null
                    label.text = "Saving to gallery\u2026"
                    io.execute {
                        val uri = Exporter.saveToGallery(this, f)
                        f.delete()
                        ui.post {
                            dlg.dismiss()
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            rebuildPreview()
                            if (uri == null) toast("Export finished but saving to the gallery failed") else doneDialog(uri)
                        }
                    }
                },
                onError = { msg -> exportFailed(dlg, out, msg) }
            )
        } catch (e: Exception) {
            exportFailed(dlg, out, e.message ?: e.toString())
            return
        }
        ui.post(poll)
    }

    private fun exportFailed(dlg: androidx.appcompat.app.AlertDialog, out: File, msg: String) {
        exporter = null
        dlg.dismiss()
        out.delete()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rebuildPreview()
        MaterialAlertDialogBuilder(this)
            .setTitle("Export failed")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun doneDialog(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Saved")
            .setMessage("Your video is in Movies/LayerCut.")
            .setPositiveButton("Share") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share video"))
            }
            .setNeutralButton("Open") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                } catch (_: Exception) {
                    toast("No video player found")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
