package com.neurone.myblocker.web

import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Element-hiding rules from an Adblock Plus list ("##selector", optionally prefixed by
 * a domain list), turned into stylesheets: one generic sheet shared by every page and a
 * small per-site sheet. Procedural (#?#), exception (#@#) and snippet (#$#) rules are
 * not plain CSS and are skipped.
 */
class CosmeticRules private constructor(
    val genericCss: String,
    private val byDomain: Map<String, List<String>>,
    val version: String,
) {
    val genericCount: Int get() = genericCss.count { it == '\n' }
    val domainCount: Int get() = byDomain.size

    /** CSS for [host] and all of its parent domains. */
    fun siteCss(host: String): String {
        val sb = StringBuilder()
        var h = host.lowercase().trimEnd('.')
        while (true) {
            byDomain[h]?.forEach { sb.append(it).append("{display:none!important}\n") }
            val i = h.indexOf('.')
            if (i < 0) break
            h = h.substring(i + 1)
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "CosmeticRules"

        val EMPTY = CosmeticRules("", emptyMap(), "0")

        fun parse(file: File): CosmeticRules {
            if (!file.exists()) return EMPTY
            val generic = StringBuilder(1 shl 19)
            val byDomain = HashMap<String, MutableList<String>>(8192)
            val digest = MessageDigest.getInstance("SHA-256")
            var lines = 0
            file.bufferedReader().useLines { seq ->
                for (raw in seq) {
                    lines++
                    val line = raw.trim()
                    if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                    val idx = line.indexOf("##")
                    if (idx < 0) continue
                    // Skip "#@#", "#?#", "#$#" forms: the char before "##" would be '#', '@', '?', '$'.
                    if (idx > 0 && line[idx - 1] in "#@?$") continue
                    if (line.startsWith("#@#", idx) || line.startsWith("#?#", idx) || line.startsWith("#$#", idx)) continue
                    val selector = line.substring(idx + 2).trim()
                    if (!isSafeSelector(selector)) continue
                    val domains = line.substring(0, idx)
                    digest.update(line.toByteArray())
                    if (domains.isEmpty()) {
                        generic.append(selector).append("{display:none!important}\n")
                    } else {
                        for (d in domains.split(',')) {
                            val dom = d.trim().lowercase()
                            if (dom.isEmpty() || dom.startsWith("~")) continue
                            byDomain.getOrPut(dom) { ArrayList(2) }.add(selector)
                        }
                    }
                }
            }
            val version = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            Log.i(TAG, "cosmetic rules: ${generic.count { it == '\n' }} generic, ${byDomain.size} domains from $lines lines")
            return CosmeticRules(generic.toString(), byDomain, version)
        }

        /**
         * Keeps selectors that are plain CSS. Anything with Adblock extended syntax or
         * characters that would break a stylesheet is dropped, since one bad selector
         * would only invalidate its own rule but costs parse time on every page.
         */
        fun isSafeSelector(s: String): Boolean {
            if (s.isEmpty() || s.length > 400) return false
            if (s.contains(":-abp-") || s.contains(":has-text") || s.contains(":contains") || s.contains(":matches-css") ||
                s.contains(":xpath") || s.contains(":style(") || s.contains(":remove") || s.contains(":upward") ||
                s.contains(":min-text-length") || s.contains(":watch-attr") || s.contains(":nth-ancestor")
            ) return false
            if (s.contains('{') || s.contains('}') || s.contains('\n') || s.contains("/*") || s.contains("*/")) return false
            var depth = 0
            var bracket = 0
            for (c in s) {
                when (c) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth < 0) return false }
                    '[' -> bracket++
                    ']' -> { bracket--; if (bracket < 0) return false }
                }
            }
            return depth == 0 && bracket == 0
        }
    }
}
