package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class ProfileSettingsTest {
    @Test fun persistedSystemAccentIsAcceptedWithoutReplacingExistingChoices() {
        assertTrue("system" in PreferenceValues.accents)
        assertTrue(PreferenceValues.accents.containsAll(listOf("cyan","blue","violet","mint")))
        assertEquals(PreferenceValues.accents.size,PreferenceValues.accents.distinct().size)
        assertFalse("unsupported-accent" in PreferenceValues.accents)
    }
    @Test fun actualSearchesAreBoundedRecentFirstAndCaseInsensitiveInCyrillic() {
        val old=(1..25).map {"Запрос $it"}
        val history=PreferenceValues.rememberSearch(old,"  запрос 20  ")
        assertEquals(20,history.size);assertEquals("запрос 20",history.first())
        assertEquals(1,history.count {it.equals("Запрос 20",true)})
        assertEquals(history,PreferenceValues.decodeSearchHistory(PreferenceValues.encodeSearchHistory(history)))
    }
    @Test fun malformedHistoryAndUnsupportedValuesDoNotBreakPreferences() {
        assertTrue(PreferenceValues.decodeSearchHistory("not json").isEmpty())
        assertTrue(PreferenceValues.decodeSearchHistory("x".repeat(32_001)).isEmpty())
        assertEquals(listOf("Музыка"),PreferenceValues.decodeSearchHistory("[\"Музыка\",null,7,\"музыка\",\"\",\"bad\\nline\"]"))
        assertEquals(listOf("Запрос"),PreferenceValues.rememberSearch(listOf("Запрос"),"\n"))
    }
    @Test fun localProfileKeepsCyrillicAndRejectsInvalidInput() {
        assertEquals(UserProfile("Артур","Моя музыка"),PreferenceValues.profile(" Артур "," Моя музыка "))
        assertThrows(IllegalArgumentException::class.java) {PreferenceValues.profile(" ","")}
        assertThrows(IllegalArgumentException::class.java) {PreferenceValues.profile("x".repeat(41),"")}
        assertThrows(IllegalArgumentException::class.java) {PreferenceValues.profile("A\u0000B","")}
        assertThrows(IllegalArgumentException::class.java) {PreferenceValues.profile("A","b".repeat(161))}
    }
    @Test fun boundedSearchHistoryNeverPersistsHalfAnEmoji() {
        val history=PreferenceValues.rememberSearch(emptyList(),"a".repeat(199)+"🎵")
        assertEquals(listOf("a".repeat(199)),history)
        assertEquals(history,PreferenceValues.decodeSearchHistory(PreferenceValues.encodeSearchHistory(history)))
    }
    /**
     * The dialog must not offer a value the model refuses: the control
     * characters are filtered out of the field.
     */
    @Test fun theDraftFiltersTheControlCharactersTheModelRefuses() {
        val draft=ProfileDraft.of("Ар\u0000тур","О себе\n\u0007")
        assertEquals("Артур",draft.name)
        assertEquals("О себе\n",draft.bio)
        assertNull(draft.error)
        assertTrue(draft.valid)
    }
    @Test fun theDraftCarriesTheMessageOfTheModel() {
        val draft=ProfileDraft.of("   ","")
        assertNotNull("a blank name is refused by the model",draft.error)
        assertFalse(draft.valid)
        assertEquals("Имя должно содержать от 1 до 40 символов",draft.error)
    }
    @Test fun theProfileErrorIsTheMessageOfTheModel() {
        val thrown=assertThrows(IllegalArgumentException::class.java) {PreferenceValues.profile(" ","")}
        assertEquals(thrown.message,PreferenceValues.profileError(" ",""))
        assertNull(PreferenceValues.profileError("Артур","Моя музыка"))
    }
    @Test fun theFieldFilterKeepsTheBoundsOfTheModel() {
        assertEquals("abc",PreferenceValues.profileField("a\u0000b\u0007c",PreferenceValues.MAX_NAME))
        assertEquals("a\nb",PreferenceValues.profileField("a\nb\u0007",PreferenceValues.MAX_BIO,allowNewline=true))
        assertEquals(40,PreferenceValues.profileField("x".repeat(100),PreferenceValues.MAX_NAME).length)
        assertEquals(40,ProfileDraft.of("x".repeat(100),"").name.length)
        assertTrue(ProfileDraft.of("x".repeat(100),"").valid)
    }
}
