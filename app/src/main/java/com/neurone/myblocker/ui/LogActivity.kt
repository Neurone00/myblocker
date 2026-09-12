package com.neurone.myblocker.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.R
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.stats.AppNames
import com.neurone.myblocker.stats.LogEntry
import com.neurone.myblocker.stats.QueryLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live list of DNS lookups. Tap an entry to allow or block its domain. */
class LogActivity : Activity() {
    private lateinit var adapter: LogAdapter
    private lateinit var appNames: AppNames
    private var filter = ""
    private var onlyBlocked = false
    private var lastVersion = -1L
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            if (QueryLog.version != lastVersion) reload()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Query log"
        appNames = AppNames(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val p = Ui.dp(this, 12)
        root.setPadding(p, p, p, 0)

        val search = EditText(this)
        search.hint = "Filter by domain or app"
        search.setSingleLine()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim()?.lowercase() ?: ""
                reload()
            }
        })
        root.addView(search)

        val controls = Ui.row(this)
        val toggleBlocked = android.widget.CheckBox(this)
        toggleBlocked.text = "Blocked only"
        toggleBlocked.setOnCheckedChangeListener { _, checked -> onlyBlocked = checked; reload() }
        controls.addView(Ui.weight(toggleBlocked, 1f))
        val clear = android.widget.Button(this)
        clear.text = "Clear"
        clear.setOnClickListener { QueryLog.clear(); reload() }
        controls.addView(clear)
        root.addView(controls)

        if (!Prefs.get(this).logEnabled) {
            root.addView(Ui.muted(this, "Query logging is off. Enable it in Settings to see lookups here."))
        }

        val list = ListView(this)
        adapter = LogAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showActions(adapter.getItem(position)) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun reload() {
        lastVersion = QueryLog.version
        var items = QueryLog.snapshot()
        if (onlyBlocked) items = items.filter { it.blocked }
        if (filter.isNotEmpty()) {
            items = items.filter {
                it.host.contains(filter) || (it.app?.let { a -> a.contains(filter) || appNames.labelFor(a).lowercase().contains(filter) } ?: false)
            }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
    }

    private fun showActions(e: LogEntry) {
        val prefs = Prefs.get(this)
        val parent = e.host.substringAfter('.', "")
        val options = ArrayList<String>()
        options.add(if (e.blocked) "Allow ${e.host}" else "Block ${e.host}")
        if (parent.contains('.')) options.add(if (e.blocked) "Allow whole $parent" else "Block whole $parent")
        options.add("Copy domain")
        val rule = e.rule?.let { "\nMatched rule: $it" } ?: ""
        val app = e.app?.let { "\nApp: ${appNames.labelFor(it)}" } ?: ""
        AlertDialog.Builder(this)
            .setTitle(e.host)
            .setMessage("${if (e.blocked) "Blocked" else "Allowed"} · ${e.reason.label}$rule$app")
            .setItems(options.toTypedArray()) { _, which ->
                val chosen = options[which]
                when {
                    chosen.startsWith("Allow whole") -> prefs.addAllow(parent)
                    chosen.startsWith("Block whole") -> prefs.addBlock(parent)
                    chosen.startsWith("Allow") -> prefs.addAllow(e.host)
                    chosen.startsWith("Block") -> prefs.addBlock(e.host)
                    else -> {
                        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("domain", e.host))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                        return@setItems
                    }
                }
                FilterEngine.reloadUserRules(this)
                Toast.makeText(this, "Rule saved. Apps may cache the old answer for up to a minute.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private inner class LogAdapter : BaseAdapter() {
        var items: List<LogEntry> = emptyList()
        private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): LogEntry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.row_log, parent, false)
            val e = items[position]
            val host = view.bind<TextView>(R.id.host)
            host.text = e.host
            host.setTextColor(getColor(if (e.blocked) R.color.blocked else R.color.allowed))
            val meta = view.bind<TextView>(R.id.meta)
            val app = e.app?.let { appNames.labelFor(it) } ?: ""
            meta.text = "${time.format(Date(e.time))} · ${e.type} · ${if (e.blocked) "blocked" else "allowed"}${if (app.isNotEmpty()) " · $app" else ""}"
            val mark = view.bind<TextView>(R.id.mark)
            mark.text = if (e.blocked) "⛔" else "✓"
            mark.gravity = Gravity.CENTER
            return view
        }
    }
}
