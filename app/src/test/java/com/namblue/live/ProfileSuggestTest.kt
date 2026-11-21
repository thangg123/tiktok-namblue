package com.namblue.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [ProfileSuggest]: TikTok search suggestions → likely usernames. */
class ProfileSuggestTest {

    @Test
    fun `parse reads the suggestion texts in order`() {
        val body = """{"sug_list":[{"content":"khabylame","sug_type":""},{"content":"khabylame ✓"},{"content":""},{"nope":1}],"status_code":0}"""

        assertEquals(listOf("khabylame", "khabylame ✓"), ProfileSuggest.parse(body))
    }

    @Test
    fun `parse of garbage is empty`() {
        assertEquals(emptyList<String>(), ProfileSuggest.parse("<html/>"))
        assertEquals(emptyList<String>(), ProfileSuggest.parse(null))
    }

    @Test
    fun `toHandle strips accents, spaces, badges and symbols`() {
        assertEquals("khanhduy71", ProfileSuggest.toHandle("khánh duy 71"))
        assertEquals("khabylame", ProfileSuggest.toHandle("khabylame ✓"))
        assertEquals("dangvanlam", ProfileSuggest.toHandle("Đặng Văn Lâm"))
        assertEquals("khaby.lame", ProfileSuggest.toHandle("@khaby.lame"))
        assertEquals("khanhduy", ProfileSuggest.toHandle("Khánh Duy🪂"))
    }

    @Test
    fun `candidates put the typed text first, drop duplicates and junk, and cap the list`() {
        val suggestions = listOf(
            "khabylame", "khaby", "khabylame ✓", "khabylame imran khan", "k", "khabi", "khavy",
            "khabynlame", "khabylame edit", "a very long phrase that cannot possibly be a username",
        )

        val candidates = ProfileSuggest.candidates("khaby", suggestions)

        assertEquals("khaby", candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertTrue(candidates.size <= ProfileSuggest.MAX_CANDIDATES)
        assertTrue("k" !in candidates)
        assertTrue(candidates.all { it.length <= 24 })
        assertEquals(listOf("khaby", "khabylame", "khabylameimrankhan", "khabi", "khavy", "khabynlame", "khabylameedit"), candidates)
    }

    @Test
    fun `several typed words are also tried joined by a dot and an underscore`() {
        assertEquals(listOf("khabylame", "khaby.lame", "khaby_lame"), ProfileSuggest.candidates("Khaby Lame", emptyList()))
        assertEquals(listOf("khanhduy", "khanh.duy", "khanh_duy", "khanhduy71"), ProfileSuggest.candidates("khánh duy", listOf("khánh duy 71")))
    }

    @Test
    fun `suggestion url encodes the keyword`() {
        assertEquals(
            "https://www.tiktok.com/api/search/general/sug/?aid=1988&from_page=search&keyword=kh%C3%A1nh+duy",
            ProfileSuggest.suggestUrl("khánh duy"),
        )
    }
}
