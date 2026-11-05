package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

class ProviderMappingTest {
    @Test fun onlyAMatchedDifferentProviderCanBecomePersistentIdentity() {
        val original=Track("youtube:abcdefghijk","Song","Artist",durationMs=180000,source="youtube",sourceId="abcdefghijk")
        val candidate=Track("soundcloud:123","Song","Artist",durationMs=180000,source="soundcloud",sourceId="123")
        val mapping=ProviderMapping.verified(original,candidate)!!
        assertEquals(original.id,mapping.trackId)
        assertEquals(candidate,mapping.candidate().copy(addedAt=candidate.addedAt))
        assertNull(ProviderMapping.verified(original,original))
        assertNull(ProviderMapping.verified(original,candidate.copy(title="Completely unrelated recording",artist="Different artist")))
    }
}
