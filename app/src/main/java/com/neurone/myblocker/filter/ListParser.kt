package com.neurone.myblocker.filter

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses hosts files, plain domain lists and the "||domain^" subset of AdBlock
 * syntax into a set of domains. Anything it cannot understand is skipped.
 */
object ListParser {
    private val IGNORED = hashSetOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        "ip6-allhosts", "0.0.0.0", "255.255.255.255",
    )

    /** Reads [input] line by line and adds every domain to [into]. Returns the number of entries added. */
    fun parse(input: InputStream, into: HashSet<String>): Int {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16)
        var added = 0
        reader.useLines { lines ->
            for (raw in lines) {
                val d = parseLine(raw) ?: continue
                if (into.add(d)) added++
            }
        }
        return added
    }

    /** Returns the domain on this line, or null if the line has none. */
    fun parseLine(raw: String): String? {
        var line = raw
        val hash = line.indexOf('#')
        if (hash >= 0) line = line.substring(0, hash)
        line = line.trim()
        if (line.isEmpty()) return null
        val c = line[0]
        if (c == '!' || c == '[' || c == '/' || c == '@') return null

        if (line.startsWith("||")) {
            // AdBlock domain rule: ||example.com^  (optionally with $modifiers, which we ignore unless they are exceptions)
            var end = line.indexOf('^', 2)
            if (end < 0) end = line.length
            val body = line.substring(2, end)
            if (body.any { it == '/' || it == '*' || it == '$' || it == '|' }) return null
            return normalizeDomain(body, allowTld = false)
        }

        // hosts format: "<ip> <domain> [<domain>...]" or a bare domain
        val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val first = parts[0]
        val candidate = if (looksLikeIp(first)) {
            if (parts.size < 2) return null
            parts[1]
        } else {
            if (parts.size != 1) return null
            first
        }
        return normalizeDomain(candidate, allowTld = false)
    }

    /**
     * Lowercases, strips a trailing dot and validates a domain name.
     * Returns null for garbage, IPs and (unless [allowTld]) names without a dot.
     */
    fun normalizeDomain(input: String, allowTld: Boolean): String? {
        var d = input.trim().lowercase()
        if (d.startsWith("*.")) d = d.substring(2)
        if (d.endsWith(".")) d = d.dropLast(1)
        if (d.isEmpty() || d.length > 253) return null
        if (d in IGNORED) return null
        if (looksLikeIp(d)) return null
        if (!allowTld && d.indexOf('.') < 0) return null
        if (d.startsWith(".") || d.contains("..")) return null
        for (ch in d) {
            val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '-' || ch == '_'
            if (!ok) return null
        }
        return d
    }

    private fun looksLikeIp(s: String): Boolean {
        if (s.indexOf(':') >= 0) return true // IPv6
        var digits = 0
        var dots = 0
        for (ch in s) {
            when {
                ch in '0'..'9' -> digits++
                ch == '.' -> dots++
                else -> return false
            }
        }
        return digits > 0 && dots == 3
    }
}
