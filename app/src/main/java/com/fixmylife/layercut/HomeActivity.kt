package com.fixmylife.layercut

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** CapCut-style landing page: New video + previous projects. */
class HomeActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val thumbs = HashMap<String, Bitmap>()

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AudioExtractor.runWithUi(this, uri, 0L, null, "extracted_audio") {}
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProjectStore.migrate(this)
        buildUi()
        if (savedInstanceState == null) Updater.check(this, silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
        Updater.resumePending(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0E0F12")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scroll.addView(col)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "LayerCut"; textSize = 28f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, wrap, 1f))
        header.addView(TextView(this).apply {
            text = "build ${Updater.currentBuild()}"
            textSize = 12f
            setTextColor(Color.parseColor("#8A8F9B"))
        })
        col.addView(header)

        // Big "New video" card
        val newCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#2BD4C0"), Color.parseColor("#6B4FD8"))
            ).apply { cornerRadius = dp(20).toFloat() }
            addView(TextView(context).apply {
                text = "\uFF0B"; textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "New video"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            setOnClickListener { openEditor(ProjectStore.create(this@HomeActivity), autoPick = true) }
        }
        col.addView(newCard, LinearLayout.LayoutParams(match, dp(160)).apply { topMargin = dp(16) })

        // Tools row
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(toolCard("\u266B", "Extract audio") { audioPicker.launch(arrayOf("video/*")) },
            LinearLayout.LayoutParams(0, dp(96), 1f).apply { rightMargin = dp(6) })
        tools.addView(toolCard("\u21BB", "Check updates") { Updater.check(this, silent = false) },
            LinearLayout.LayoutParams(0, dp(96), 1f).apply { leftMargin = dp(6) })
        col.addView(tools, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })

        col.addView(TextView(this).apply {
            text = "Projects"; textSize = 20f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(24); bottomMargin = dp(8) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(scroll)
    }

    private fun toolCard(icon: String, label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(Color.parseColor("#1B1E24"), 16)
        addView(TextView(context).apply { text = icon; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        addView(TextView(context).apply {
            text = label; textSize = 14f; setTextColor(Color.parseColor("#D6D9E0")); gravity = Gravity.CENTER
        })
        setOnClickListener { onClick() }
    }

    private fun openEditor(id: String, autoPick: Boolean) {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ID, id)
            .putExtra(MainActivity.EXTRA_AUTOPICK, autoPick))
    }

    private fun refreshList() {
        list.removeAllViews()
        val infos = ProjectStore.list(this)
        if (infos.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No projects yet. Tap New video to start."
                setTextColor(Color.parseColor("#8A8F9B"))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (info in infos) list.addView(projectRow(info))
    }

    private fun projectRow(info: ProjectStore.Info): LinearLayout {
        val p = info.project
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.parseColor("#16181D"), 14)
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.parseColor("#262A33"), 10)
            clipToOutline = true
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(72), dp(96)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(TextView(this).apply {
            text = p.name; textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            maxLines = 2
        })
        val clips = p.main.size + p.overlays.size
        val edited = DateUtils.getRelativeTimeSpanString(info.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        texts.addView(TextView(this).apply {
            text = "${TimelineView.formatTime(p.totalDurationMs(), false)} \u00B7 $clips clip${if (clips == 1) "" else "s"} \u00B7 ${p.aspect}\nEdited $edited"
            textSize = 13f; setTextColor(Color.parseColor("#8A8F9B"))
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "\u22EE"; textSize = 22f; setTextColor(Color.parseColor("#B8BCC6"))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { projectMenu(info) }
        })
        row.setOnClickListener { openEditor(info.id, autoPick = false) }
        row.setOnLongClickListener { projectMenu(info); true }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }

        val first = p.main.firstOrNull() ?: p.overlays.firstOrNull()
        if (first != null) {
            val key = info.id + ":" + first.id
            val cached = thumbs[key]
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                io.execute {
                    val b = MediaProbe.frame(this, first, first.trimStartMs, 240)
                    ui.post {
                        if (b != null) {
                            thumbs[key] = b
                            thumb.setImageBitmap(b)
                        }
                    }
                }
            }
        }
        return row
    }

    private fun projectMenu(info: ProjectStore.Info) {
        MaterialAlertDialogBuilder(this)
            .setTitle(info.project.name)
            .setItems(arrayOf("Rename", "Duplicate", "Delete")) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(info.project.name); setSelection(text.length) }
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Rename project")
                            .setView(input)
                            .setPositiveButton("Save") { _, _ ->
                                val n = input.text.toString().trim()
                                if (n.isNotEmpty()) ProjectStore.rename(this, info.id, n)
                                refreshList()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> { ProjectStore.duplicate(this, info.id); refreshList() }
                    2 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Delete \"${info.project.name}\"?")
                        .setMessage("Your original videos and photos are not deleted.")
                        .setPositiveButton("Delete") { _, _ -> ProjectStore.delete(this, info.id); refreshList() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }
}
