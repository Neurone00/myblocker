package com.neurone.myblocker.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.filter.ListRepository
import com.neurone.myblocker.filter.ListSource
import com.neurone.myblocker.filter.ProtectionLevel
import java.text.DateFormat
import java.util.Date

/** Protection level presets and the individual blocklists behind them. */
class ListsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var repo: ListRepository
    private lateinit var col: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var updating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Blocklists"
        prefs = Prefs.get(this)
        repo = ListRepository(this)
        col = Ui.page(this)
        render()
    }

    private fun render() {
        col.removeAllViews()
        val ctx = this

        val levelCard = Ui.card(ctx)
        levelCard.addView(Ui.heading(ctx, "Protection level"))
        levelCard.addView(Ui.muted(ctx, "Presets choose which lists are active. Toggle any list below to switch to Custom."))
        val group = RadioGroup(ctx)
        val descriptions = mapOf(
            ProtectionLevel.OFF to "No lists. Only your own block rules apply.",
            ProtectionLevel.LIGHT to "Curated ad SDKs + HaGeZi Light. Nothing should break.",
            ProtectionLevel.BALANCED to "Curated ad SDKs + HaGeZi Pro. Strong blocking, rare breakage.",
            ProtectionLevel.AGGRESSIVE to "Pro + StevenBlack + Samsung and TikTok telemetry. Most blocking; use Query log to fix any breakage.",
            ProtectionLevel.CUSTOM to "Your own selection below.",
        )
        for (level in ProtectionLevel.entries) {
            val rb = RadioButton(ctx)
            rb.id = 1000 + level.ordinal
            rb.text = "${level.label} — ${descriptions[level]}"
            rb.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            group.addView(rb)
        }
        group.check(1000 + prefs.level.ordinal)
        group.setOnCheckedChangeListener { _, id ->
            val level = ProtectionLevel.entries[id - 1000]
            if (level == prefs.level) return@setOnCheckedChangeListener
            prefs.applyLevel(level)
            applyAndReload()
            render()
        }
        levelCard.addView(group)
        col.addView(levelCard)

        val listsCard = Ui.card(ctx)
        listsCard.addView(Ui.heading(ctx, "Lists"))
        val enabled = prefs.enabledSources
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (src in repo.allSources()) {
            val st = repo.status(src)
            val row = Ui.row(ctx)
            val cb = CheckBox(ctx)
            cb.isChecked = src.id in enabled
            cb.setOnCheckedChangeListener { _, checked ->
                prefs.enabledSources = if (checked) prefs.enabledSources + src.id else prefs.enabledSources - src.id
                prefs.level = ProtectionLevel.CUSTOM
                applyAndReload()
                if (checked && !st.downloaded && !st.bundled) updateNow(listOf(src))
                render()
            }
            row.addView(cb)
            val text = Ui.column(ctx)
            text.addView(Ui.body(ctx, src.name))
            text.addView(Ui.muted(ctx, src.description))
            val status = when {
                st.error != null && !st.downloaded && !st.bundled -> "Not downloaded yet · last error: ${st.error}"
                st.downloaded -> "${Ui.format(st.entries.toLong())} entries · updated ${fmt.format(Date(st.lastUpdated))}"
                st.bundled -> "${Ui.format(st.entries.toLong())} entries · bundled copy, tap Update to refresh"
                else -> "Not downloaded yet"
            }
            text.addView(Ui.muted(ctx, status))
            row.addView(Ui.weight(text, 1f))
            if (!src.builtin) {
                row.setOnLongClickListener {
                    AlertDialog.Builder(ctx).setTitle("Remove ${src.name}?")
                        .setPositiveButton("Remove") { _, _ -> repo.removeCustomSource(src.id); applyAndReload(); render() }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
            }
            row.setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            listsCard.addView(row)
        }
        col.addView(listsCard)

        val last = prefs.lastListUpdate
        col.addView(Ui.muted(ctx, if (last > 0) "Last update: ${fmt.format(Date(last))}. Lists refresh automatically every 12 hours." else "Lists refresh automatically every 12 hours."))
        col.addView(Ui.button(ctx, if (updating) "Updating…" else "Update lists now") { if (!updating) updateNow(null) })
        col.addView(Ui.button(ctx, "Add list from URL") { addCustom() })
        col.addView(Ui.muted(ctx, "Active rules: ${Ui.format(FilterEngine.blockedEntryCount.toLong())}"))
    }

    private fun applyAndReload() {
        FilterEngine.reloadAsync(this) { handler.post { if (!isFinishing) render() } }
    }

    private fun updateNow(only: List<ListSource>?) {
        updating = true
        render()
        Thread {
            val results = if (only == null) repo.updateEnabled() else only.associate { it.id to repo.download(it) }
            FilterEngine.reload(this)
            handler.post {
                updating = false
                if (isFinishing) return@post
                val failed = results.filterValues { it != null }
                val msg = if (failed.isEmpty()) "Lists updated" else "Some lists failed: " + failed.entries.joinToString { "${it.key}: ${it.value}" }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun addCustom() {
        val ctx = this
        val box = Ui.column(ctx)
        val pad = Ui.dp(ctx, 20)
        box.setPadding(pad, 0, pad, 0)
        val name = EditText(ctx).apply { hint = "Name" }
        val url = EditText(ctx).apply { hint = "https://… (hosts, domain list or ||domain^ rules)" }
        box.addView(name)
        box.addView(url)
        AlertDialog.Builder(ctx)
            .setTitle("Add blocklist")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val u = url.text.toString().trim()
                if (!u.startsWith("http")) {
                    Toast.makeText(ctx, "Enter a valid URL", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val src = repo.addCustomSource(name.text.toString(), u)
                updateNow(listOf(src))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
