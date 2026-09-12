package com.neurone.myblocker.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.neurone.myblocker.Prefs
import com.neurone.myblocker.filter.FilterEngine
import com.neurone.myblocker.filter.ListParser

/** Personal allow and block rules. A rule matches the domain and everything below it. */
class RulesActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Allow & block rules"
        prefs = Prefs.get(this)
        col = Ui.page(this)
        render()
    }

    private fun render() {
        col.removeAllViews()
        col.addView(Ui.muted(this, "A rule covers the domain and all its subdomains. Your allowlist always wins over every blocklist; use it to fix an app that stopped working."))
        col.addView(section("Allowlist (never block)", prefs.userAllow, { prefs.addAllow(it) }, { prefs.userAllow = prefs.userAllow - it }))
        col.addView(section("Blocklist (always block)", prefs.userBlock, { prefs.addBlock(it) }, { prefs.userBlock = prefs.userBlock - it }))
    }

    private fun section(title: String, entries: Set<String>, add: (String) -> Unit, remove: (String) -> Unit): LinearLayout {
        val ctx = this
        val card = Ui.card(ctx)
        card.addView(Ui.heading(ctx, title))
        val input = Ui.row(ctx)
        val edit = EditText(ctx)
        edit.hint = "example.com"
        edit.setSingleLine()
        input.addView(Ui.weight(edit, 1f))
        val addBtn = Button(ctx)
        addBtn.text = "Add"
        addBtn.setOnClickListener {
            val d = ListParser.normalizeDomain(edit.text.toString(), allowTld = true)
            if (d == null) {
                Toast.makeText(ctx, "That does not look like a domain", Toast.LENGTH_SHORT).show()
            } else {
                add(d)
                FilterEngine.reloadUserRules(ctx)
                render()
            }
        }
        input.addView(addBtn)
        card.addView(input)
        if (entries.isEmpty()) card.addView(Ui.muted(ctx, "No rules yet."))
        for (d in entries.sorted()) {
            val row = Ui.row(ctx)
            row.addView(Ui.weight(Ui.body(ctx, d), 1f))
            val rm = Button(ctx)
            rm.text = "Remove"
            rm.setOnClickListener {
                remove(d)
                FilterEngine.reloadUserRules(ctx)
                render()
            }
            row.addView(rm)
            card.addView(row)
        }
        return card
    }
}
