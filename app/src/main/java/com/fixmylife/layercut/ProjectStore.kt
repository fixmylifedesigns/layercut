package com.fixmylife.layercut

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved projects live in filesDir/projects/<id>.json. */
object ProjectStore {

    class Info(val id: String, val project: Project, val modified: Long)

    private fun dir(ctx: Context) = File(ctx.filesDir, "projects").apply { mkdirs() }

    fun file(ctx: Context, id: String) = File(dir(ctx), "$id.json")

    /** Moves the single project from builds 1-3 into the new projects folder. */
    fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, "project.json")
        if (!old.exists()) return
        val p = Project()
        try { p.loadFrom(JSONObject(old.readText())) } catch (_: Exception) {}
        if (p.main.isNotEmpty() || p.overlays.isNotEmpty()) {
            if (p.name == Project.DEFAULT_NAME) p.name = "My first project"
            save(ctx, newId(), p)
        }
        old.delete()
    }

    private fun newId() = System.currentTimeMillis().toString()

    fun create(ctx: Context): String {
        val id = newId()
        val p = Project()
        p.name = "Project " + SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
        save(ctx, id, p)
        return id
    }

    fun save(ctx: Context, id: String, p: Project) {
        file(ctx, id).writeText(p.toJson().toString())
    }

    fun loadInto(ctx: Context, id: String, p: Project): Boolean = try {
        val f = file(ctx, id)
        if (f.exists()) { p.loadFrom(JSONObject(f.readText())); true } else false
    } catch (e: Exception) {
        false
    }

    /** Newest first. Empty projects are cleaned up. */
    fun list(ctx: Context): List<Info> {
        val out = ArrayList<Info>()
        for (f in dir(ctx).listFiles() ?: emptyArray()) {
            if (f.extension != "json") continue
            try {
                val p = Project().apply { loadFrom(JSONObject(f.readText())) }
                if (p.main.isEmpty() && p.overlays.isEmpty()) { f.delete(); continue }
                out.add(Info(f.nameWithoutExtension, p, f.lastModified()))
            } catch (_: Exception) {}
        }
        return out.sortedByDescending { it.modified }
    }

    fun delete(ctx: Context, id: String) { file(ctx, id).delete() }

    fun duplicate(ctx: Context, id: String) {
        val p = Project()
        if (!loadInto(ctx, id, p)) return
        p.name = p.name + " copy"
        save(ctx, newId(), p)
    }

    fun rename(ctx: Context, id: String, name: String) {
        val p = Project()
        if (!loadInto(ctx, id, p)) return
        p.name = name
        save(ctx, id, p)
    }
}
