package ru.nedotify.app.ui

import androidx.compose.material3.NavigationRail
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
/** Production navigation controls use fixture state; MainActivity and user stores are never opened. */
class NavigationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun bottomNavigationChangesTheSelectedRootAndRestoresItAfterRecreation() {
        val restore=StateRestorationTester(compose)
        var selected=RootTab.HOME
        restore.setContent {
            var encoded by rememberSaveable {mutableStateOf(encodeNavigation(AppNavigationState()))}
            val navigation=decodeNavigation(encoded)
            NeDotifyTheme(true) {
                RootNavigation(navigation.selectedTab,false) {
                    selected=it
                    encoded=encodeNavigation(navigation.selectTab(it))
                }
            }
        }
        compose.onNodeWithText("Библиотека").performClick().assertIsSelected()
        compose.runOnIdle {assertEquals(RootTab.LIBRARY,selected)}
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Библиотека").assertIsSelected()
        compose.onNodeWithText("Поиск").performClick().assertIsSelected()
        compose.runOnIdle {assertEquals(RootTab.SEARCH,selected)}
    }

    @Test fun railExposesTheSameThreeDestinationsAndSelection() {
        var selected=RootTab.HOME
        compose.setContent {
            var tab by rememberSaveable {mutableStateOf(RootTab.HOME.name)}
            NeDotifyTheme(true) {
                NavigationRail {
                    RootNavigation(RootTab.valueOf(tab),true) {selected=it;tab=it.name}
                }
            }
        }
        compose.onNodeWithText("Главная").assertIsSelected()
        compose.onNodeWithText("Поиск").performClick().assertIsSelected()
        compose.onNodeWithText("Библиотека").performClick().assertIsSelected()
        compose.runOnIdle {assertEquals(RootTab.LIBRARY,selected)}
    }
}
