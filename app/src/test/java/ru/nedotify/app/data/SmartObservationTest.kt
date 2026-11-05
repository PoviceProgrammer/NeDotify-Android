package ru.nedotify.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartObservationTest {
    @Test fun damagedRulesRecoverAfterEditingTheSamePlaylistAndKeepReactingToLibraryChanges()=runTest {
        val smart=SmartPlaylist("same-id","Мой плейлист","broken",1)
        val playlist=MutableStateFlow<SmartPlaylist?>(smart)
        val a=Track("local:a","Первый",source="local",favorite=true,addedAt=1)
        val b=Track("local:b","Второй",source="local",favorite=false,addedAt=1)
        val library=MutableStateFlow(listOf(DiscoveryCandidate(a),DiscoveryCandidate(b)))
        val clock=MutableStateFlow(1_000L)
        val errors=mutableListOf<String>()
        val selected=observeSmartPlaylistTracks(playlist,{library},clock) {errors+=it}
            .stateIn(backgroundScope,SharingStarted.Eagerly,emptyList())
        runCurrent()
        assertTrue(errors.isNotEmpty());assertTrue(selected.value.isEmpty())
        playlist.value=smart.copy(rulesJson=SmartRules(favorite=true).encode())
        runCurrent()
        assertEquals(listOf(a),selected.value)
        library.value=listOf(DiscoveryCandidate(a.copy(favorite=false)),DiscoveryCandidate(b.copy(favorite=true)))
        runCurrent()
        assertEquals(listOf(b.copy(favorite=true)),selected.value)
        playlist.value=null
        runCurrent()
        assertTrue(selected.value.isEmpty())
    }
}
