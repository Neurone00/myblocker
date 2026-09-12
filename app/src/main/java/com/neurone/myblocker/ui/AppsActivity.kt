package com.neurone.myblocker.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.vpn.BlockerVpnService

/** Apps that bypass the filter completely (their DNS goes straight to the network). */
class AppsActivity : Activity() {
    private class AppItem(val pkg: String, val label: String, val system: Boolean)

    private lateinit var prefs: Prefs
    private var all: List<AppItem> = emptyList()
    private lateinit var adapter: Adapter
    private var changed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bypass apps"
        prefs = Prefs.get(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 12)
        root.setPadding(p, p, p, 0)
        root.addView(Ui.muted(this, "Checked apps skip MyBlocker entirely. Use this for an app that misbehaves with filtering (banking, some games). Changes apply when you leave this screen."))
        val search = EditText(this)
        search.hint = "Search apps"
        search.setSingleLine()
        root.addView(search)
        val list = ListView(this)
        adapter = Adapter()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString() ?: "")
        })
        Thread {
            val items = loadApps()
            runOnUiThread { all = items; adapter.filter("") }
        }.start()
    }

    override fun onPause() {
        super.onPause()
        if (changed) {
            changed = false
            BlockerVpnService.restartIfRunning(this)
        }
    }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val bypass = prefs.bypassApps
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val out = ArrayList<AppItem>()
        for (ai in apps) {
            if (ai.packageName == packageName) continue
            val hasInternet = pm.checkPermission(Manifest.permission.INTERNET, ai.packageName) == PackageManager.PERMISSION_GRANTED
            if (!hasInternet && ai.packageName !in bypass) continue
            val system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
            if (system && !launchable && ai.packageName !in bypass) continue
            out.add(AppItem(ai.packageName, pm.getApplicationLabel(ai).toString(), system))
        }
        return out.sortedWith(compareBy({ it.pkg !in bypass }, { it.label.lowercase() }))
    }

    private inner class Adapter : BaseAdapter() {
        private var items: List<AppItem> = emptyList()

        fun filter(q: String) {
            val needle = q.trim().lowercase()
            items = if (needle.isEmpty()) all else all.filter { it.label.lowercase().contains(needle) || it.pkg.contains(needle) }
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx = this@AppsActivity
            val row = (convertView as? LinearLayout) ?: Ui.row(ctx).also {
                it.setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8))
                val cb = CheckBox(ctx)
                cb.tag = "cb"
                it.addView(cb)
                val text = Ui.column(ctx)
                text.addView(Ui.body(ctx, "").also { t -> t.tag = "label" })
                text.addView(Ui.muted(ctx, "").also { t -> t.tag = "pkg" })
                it.addView(Ui.weight(text, 1f))
            }
            val item = items[position]
            val cb = row.findViewWithTag<CheckBox>("cb")
            cb.setOnCheckedChangeListener(null)
            cb.isChecked = item.pkg in prefs.bypassApps
            cb.setOnCheckedChangeListener { _, checked ->
                prefs.bypassApps = if (checked) prefs.bypassApps + item.pkg else prefs.bypassApps - item.pkg
                changed = true
            }
            row.findViewWithTag<TextView>("label").text = item.label
            row.findViewWithTag<TextView>("pkg").text = item.pkg + if (item.system) " · system" else ""
            row.setOnClickListener { cb.toggle() }
            return row
        }
    }
}
