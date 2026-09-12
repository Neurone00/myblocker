package com.neurone.myblocker.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.neurone.myblocker.R

/** Small programmatic-layout helpers so secondary screens need no XML. */
object Ui {
    fun dp(context: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    /** A vertical, scrollable column with standard page padding. Returns the column. */
    fun page(activity: Activity): LinearLayout {
        val scroll = ScrollView(activity)
        scroll.isFillViewport = true
        val col = LinearLayout(activity)
        col.orientation = LinearLayout.VERTICAL
        val p = dp(activity, 16)
        col.setPadding(p, p, p, p)
        scroll.addView(col, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(scroll)
        return col
    }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(context: Context): LinearLayout {
        val c = column(context)
        val p = dp(context, 14)
        c.setPadding(p, p, p, p)
        c.background = cardBackground(context)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(context, 12)
        c.layoutParams = lp
        return c
    }

    fun cardBackground(context: Context): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(context, 16).toFloat()
        setColor(context.getColor(R.color.card))
    }

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 8), 0, dp(context, 8))
    }

    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        isAllCaps = true
        setTextColor(context.getColor(R.color.accent))
        setPadding(0, dp(context, 12), 0, dp(context, 6))
    }

    fun body(context: Context, text: CharSequence, size: Float = 15f): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setPadding(0, dp(context, 2), 0, dp(context, 2))
    }

    fun muted(context: Context, text: CharSequence): TextView = body(context, text, 13f).apply {
        setTextColor(context.getColor(R.color.muted))
    }

    fun button(context: Context, text: String, onClick: (View) -> Unit): Button = Button(context).apply {
        this.text = text
        setOnClickListener(onClick)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(context, 6)
        layoutParams = lp
    }

    fun spacer(context: Context, height: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, height))
    }

    fun weight(view: View, w: Float): View {
        view.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        return view
    }

    fun format(n: Long): String = String.format(java.util.Locale.getDefault(), "%,d", n)

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.0f MB", b / (1024.0 * 1024))
        else -> String.format(java.util.Locale.getDefault(), "%.0f KB", b / 1024.0)
    }
}

/** Null-safe findViewById; the view must exist in the layout. */
inline fun <reified T : View> Activity.bind(id: Int): T = requireNotNull(findViewById<T>(id)) { "view $id missing" }

/** Null-safe findViewById on a view tree; the child must exist. */
inline fun <reified T : View> View.bind(id: Int): T = requireNotNull(findViewById<T>(id)) { "view $id missing" }
