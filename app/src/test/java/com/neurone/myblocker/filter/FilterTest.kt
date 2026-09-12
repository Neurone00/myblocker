package com.neurone.myblocker.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterTest {
    @Test fun parsesHostsAndPlainAndAdblockLines() {
        assertEquals("ads.example.com", ListParser.parseLine("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", ListParser.parseLine("127.0.0.1\tads.example.com  # comment"))
        assertEquals("ads.example.com", ListParser.parseLine("ADS.Example.com."))
        assertEquals("ads.example.com", ListParser.parseLine("||ads.example.com^"))
        assertEquals("ads.example.com", ListParser.parseLine("||ads.example.com^\$important"))
        assertEquals("ads.example.com", ListParser.parseLine("*.ads.example.com"))
        assertNull(ListParser.parseLine("# just a comment"))
        assertNull(ListParser.parseLine("! adblock comment"))
        assertNull(ListParser.parseLine("[Adblock Plus 2.0]"))
        assertNull(ListParser.parseLine("@@||allowed.example.com^"))
        assertNull(ListParser.parseLine("||example.com/path^"))
        assertNull(ListParser.parseLine("127.0.0.1 localhost"))
        assertNull(ListParser.parseLine("::1 ip6-localhost"))
        assertNull(ListParser.parseLine("0.0.0.0"))
        assertNull(ListParser.parseLine("justaword"))
        assertNull(ListParser.parseLine("bad domain.com"))
        assertNull(ListParser.parseLine("1.2.3.4"))
        assertNull(ListParser.parseLine("ex ample.com extra"))
    }

    @Test fun parsesStreams() {
        val text = """
            # Title: test
            0.0.0.0 a.example.com
            0.0.0.0 b.example.com
            b.example.com
            ||c.example.com^
        """.trimIndent()
        val set = HashSet<String>()
        val added = ListParser.parse(text.byteInputStream(), set)
        assertEquals(3, added)
        assertEquals(setOf("a.example.com", "b.example.com", "c.example.com"), set)
    }

    @Test fun suffixMatching() {
        val set = DomainSet.of(listOf("doubleclick.net", "ads.example.com"))
        assertEquals("doubleclick.net", set.matches("googleads.g.doubleclick.net"))
        assertEquals("doubleclick.net", set.matches("doubleclick.net"))
        assertEquals("ads.example.com", set.matches("x.ads.example.com"))
        assertNull(set.matches("example.com"))
        assertNull(set.matches("notdoubleclick.net"))
        assertNull(set.matches("net"))
    }

    @Test fun userRulesAllowTlds() {
        assertEquals("xyz", ListParser.normalizeDomain("xyz", allowTld = true))
        assertNull(ListParser.normalizeDomain("xyz", allowTld = false))
    }
}
