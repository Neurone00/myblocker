package com.neurone.myblocker.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** EasyList element-hiding rules, as supplied to the Samsung Internet content blocker. */
class CosmeticRulesTest {
    @Test fun cosmeticRulesParse() {
        val f = Files.createTempFile("easylist", ".txt").toFile()
        f.writeText(
            """
            [Adblock Plus 2.0]
            ! comment
            ##.ad-banner
            ##div[id^="ad-"]
            example.com,news.example.org##.sponsored
            ~example.com##.not-here
            example.com#@#.sponsored
            example.com#?#div:-abp-has(> .ad)
            ##.bad{selector}
            """.trimIndent(),
        )
        val r = CosmeticRules.parse(f)
        assertEquals(2, r.genericCount)
        assertTrue(r.genericCss.contains(".ad-banner{display:none!important}"))
        assertFalse(r.genericCss.contains("bad{selector"))
        assertTrue(r.siteCss("shop.example.com").contains(".sponsored{display:none!important}"))
        assertTrue(r.siteCss("news.example.org").contains(".sponsored"))
        assertEquals("", r.siteCss("other.net"))
        assertEquals(16, r.version.length)
        f.delete()
    }
}
