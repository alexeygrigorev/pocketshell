package com.pocketshell.app.snippets

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnippetTemplateAndroidRegexTest {

    @Test
    fun placeholderRegex_initializesAndMatchesOnAndroid() {
        assertEquals(
            listOf("message", "tag_name"),
            snippetTemplateParameters("git commit -m '{{message}}'\ngit tag {{ tag_name }}"),
        )
    }
}
