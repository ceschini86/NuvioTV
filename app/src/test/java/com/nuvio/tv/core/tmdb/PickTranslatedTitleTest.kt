package com.nuvio.tv.core.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickTranslatedTitleTest {
    @Test
    fun `a translated title is kept`() {
        assertEquals(
            "A Imperatriz Se Casou Novamente",
            pickTranslatedTitle("A Imperatriz Se Casou Novamente", "재혼 황후", "ko", "pt-BR")
        )
    }

    @Test
    fun `the original title is not a translation for a viewer in another language`() {
        assertNull(pickTranslatedTitle("12 Rounds", "12 Rounds", "en", "pt-BR"))
        assertNull(pickTranslatedTitle("재혼 황후", "재혼 황후", "ko", "pt-BR"))
    }

    @Test
    fun `the original title counts when the title was made in the viewer's language`() {
        assertEquals("Cidade de Deus", pickTranslatedTitle("Cidade de Deus", "Cidade de Deus", "pt", "pt-BR"))
    }

    @Test
    fun `a title in another script is dropped for a viewer who does not read it`() {
        assertNull(pickTranslatedTitle("재혼 황후 2", "재혼 황후", "ko", "pt-BR"))
        assertEquals("재혼 황후 2", pickTranslatedTitle("재혼 황후 2", "재혼 황후", "ko", "ko-KR"))
    }
}
