package ru.nedotify.app.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
    @Test fun independentTabHistorySurvivesSwitchingAndBackReturnsToItsActualParent() {
        val search=AppNavigationState().selectTab(RootTab.SEARCH)
            .open(AppDestination(AppPage.PROFILE)).open(AppDestination(AppPage.SETTINGS))
        val library=search.selectTab(RootTab.LIBRARY)
            .open(AppDestination(AppPage.COLLECTION,"playlist-01","playlist"))
        var returned=library.selectTab(RootTab.SEARCH)
        assertEquals(AppPage.SETTINGS,returned.current.page)
        returned=returned.back();assertEquals(AppPage.PROFILE,returned.current.page)
        returned=returned.back();assertEquals(AppPage.SEARCH,returned.current.page)
        returned=returned.back();assertEquals(RootTab.HOME,returned.selectedTab)
        assertEquals("playlist-01",returned.selectTab(RootTab.LIBRARY).current.itemId)
        assertFalse(AppNavigationState().canGoBack)
    }

    @Test fun backClosesPlayerBeforeChangingTheUnderlyingDestination() {
        val underlying=AppNavigationState().selectTab(RootTab.LIBRARY).open(AppDestination(AppPage.DOWNLOADS))
        val open=underlying.showPlayer()
        assertTrue(open.canGoBack)
        assertEquals(underlying,open.back())
        assertEquals(AppPage.LIBRARY,open.back().back().current.page)
        assertEquals(underlying,open.showPlayer(false))
    }

    @Test fun openingARootSelectsAndResetsOnlyItsOwnStackAndKeepsTheRequestedFilter() {
        val before=AppNavigationState().selectTab(RootTab.SEARCH).open(AppDestination(AppPage.PROFILE))
            .selectTab(RootTab.LIBRARY).open(AppDestination(AppPage.DOWNLOADS))
        val after=before.open(AppDestination(AppPage.LIBRARY,filter="favorites"))
        assertEquals(RootTab.LIBRARY,after.selectedTab)
        assertEquals(listOf(AppDestination(AppPage.LIBRARY,filter="favorites")),after.stacks[RootTab.LIBRARY])
        assertEquals(before.stacks[RootTab.SEARCH],after.stacks[RootTab.SEARCH])
    }

    @Test fun repeatedDestinationDoesNotCreateADuplicateBackStep() {
        val destination=AppDestination(AppPage.COLLECTION,"playlist-01","playlist")
        val once=AppNavigationState().open(destination)
        assertEquals(once,once.open(destination))
        assertEquals(AppNavigationState(),once.back())
    }

    @Test fun deepNavigationKeepsItsRootAndOnlyTheSevenNewestChildren() {
        var state=AppNavigationState().selectTab(RootTab.LIBRARY)
        repeat(40) {state=state.open(AppDestination(AppPage.COLLECTION,"playlist-$it","playlist"))}
        val stack=state.stacks.getValue(RootTab.LIBRARY)
        assertEquals(8,stack.size)
        assertEquals(AppDestination(AppPage.LIBRARY),stack.first())
        assertEquals((33..39).map {"playlist-$it"},stack.drop(1).map {it.itemId})
    }

    @Test fun saveAndRestoreRetainsIndependentStacksTheSelectedTabAndPlayerVisibility() {
        val state=AppNavigationState().open(AppDestination(AppPage.PROFILE))
            .selectTab(RootTab.SEARCH).open(AppDestination(AppPage.SETTINGS))
            .selectTab(RootTab.LIBRARY).open(AppDestination(AppPage.COLLECTION,"playlist-01","playlist"))
            .showPlayer()
        assertEquals(state,decodeNavigation(encodeNavigation(state)))
        assertEquals(AppPage.SETTINGS,decodeNavigation(encodeNavigation(state)).selectTab(RootTab.SEARCH).current.page)
    }

    @Test fun savedNavigationHasOnlyBoundedPrimitiveArgumentsAndNoDomainPayload() {
        var state=AppNavigationState()
        for(tab in RootTab.entries) {
            state=state.selectTab(tab)
            repeat(7) {state=state.open(AppDestination(AppPage.COLLECTION,"p".repeat(125)+"-$it","f".repeat(32)))}
        }
        val raw=encodeNavigation(state)
        assertTrue(raw.length<=8192)
        assertEquals(state,decodeNavigation(raw))
        val document=JSONObject(raw)
        assertEquals(setOf("version","tab","player","stacks"),document.keys().asSequence().toSet())
        for(tab in RootTab.entries) {
            val rows=document.getJSONObject("stacks").getJSONArray(tab.name)
            for(index in 0 until rows.length()) {
                assertEquals(setOf("page","id","filter"),rows.getJSONObject(index).keys().asSequence().toSet())
            }
        }
    }

    @Test fun malformedUnknownAndOversizedSavedStateFallBackToCleanRoots() {
        val valid=encodeNavigation(AppNavigationState().open(AppDestination(AppPage.PROFILE)))
        for(raw in listOf("", "not json", "[]", "x".repeat(8193),
            JSONObject(valid).put("version",2).toString(),
            JSONObject(valid).put("tab","UNKNOWN").toString(),
            valid.replace("PROFILE","UNKNOWN"))) {
            assertEquals(raw.take(100),AppNavigationState(),decodeNavigation(raw))
        }
    }

    @Test fun restoreRejectsEmptyMismatchedOrUnboundedTabStacks() {
        val valid=encodeNavigation(AppNavigationState().selectTab(RootTab.SEARCH))
        fun changed(block:(JSONObject)->Unit):String = JSONObject(valid).also(block).toString()
        val empty=changed {it.getJSONObject("stacks").put("HOME",JSONArray())}
        val mismatch=changed {it.getJSONObject("stacks").getJSONArray("HOME").getJSONObject(0).put("page","SEARCH")}
        val nestedRoot=changed {it.getJSONObject("stacks").getJSONArray("SEARCH").put(JSONObject().put("page","HOME").put("id","").put("filter",""))}
        val tooDeep=changed {json->repeat(8) {json.getJSONObject("stacks").getJSONArray("SEARCH").put(JSONObject().put("page","PROFILE").put("id","").put("filter",""))}}
        for(raw in listOf(empty,mismatch,nestedRoot,tooDeep))assertEquals(AppNavigationState(),decodeNavigation(raw))
    }

    @Test fun invalidIdsFiltersUrlsAndControlCharactersCannotEnterOrRestoreNavigation() {
        val valid=AppNavigationState().open(AppDestination(AppPage.COLLECTION,"playlist-01","playlist"))
        val raw=encodeNavigation(valid)
        for(id in listOf("", " ","x".repeat(129),"https://cdn.example/audio","file:/private","data:secret","track\nnext")) {
            assertEquals(id,AppNavigationState(),AppNavigationState().open(AppDestination(AppPage.COLLECTION,id)))
            val changed=JSONObject(raw)
            changed.getJSONObject("stacks").getJSONArray("HOME").getJSONObject(1).put("id",id)
            assertEquals(id,AppNavigationState(),decodeNavigation(changed.toString()))
        }
        for(filter in listOf("x".repeat(33),"https://cdn.example/x","bad\u0000filter")) {
            assertEquals(AppNavigationState(),AppNavigationState().open(AppDestination(AppPage.LIBRARY,filter=filter)))
        }
        assertEquals(AppNavigationState(),AppNavigationState().open(AppDestination(AppPage.SETTINGS,"unexpected-id")))
    }

    @Test fun invalidConstructedStateIsNeverSerializedAsAnInvalidRestoration() {
        val invalid=AppNavigationState(stacks=rootStacks()+(RootTab.HOME to emptyList()))
        assertEquals(AppNavigationState(),decodeNavigation(encodeNavigation(invalid)))
    }
}
