package io.novafoundation.nova.runtime.names

import org.junit.Assert.assertEquals
import org.junit.Test

class KurdLookalikesTest {

    @Test
    fun `a zone flattened into the label suggests the zone name`() {
        assertEquals(listOf("serok.gov.kurd"), candidates("serok-gov.kurd"))
        assertEquals(listOf("serok.gov.kurd"), candidates("serokgov.kurd"))
        assertEquals(listOf("zanko.edu.kurd"), candidates("zanko-edu.kurd"))
        assertEquals(listOf("nuce.darayi.gov.kurd"), candidates("nuce.darayi-gov.kurd"))
    }

    @Test
    fun `ordinary and official names suggest nothing`() {
        assertEquals(emptyList<String>(), candidates("dks.kurd"))
        assertEquals(emptyList<String>(), candidates("serok.gov.kurd"))
        assertEquals(emptyList<String>(), candidates("gov.kurd"))
    }

    @Test
    fun `a guess that would not be a valid name is dropped`() {
        // five labels are too deep
        assertEquals(emptyList<String>(), candidates("a.b.c.d-gov.kurd"))
    }

    private fun candidates(name: String): List<String> = KurdName.parse(name)?.let { KurdLookalikes.candidates(it).map(KurdName::name) } ?: emptyList()
}
