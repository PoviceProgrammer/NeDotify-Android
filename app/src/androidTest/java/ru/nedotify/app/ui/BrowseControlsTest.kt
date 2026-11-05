package ru.nedotify.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.nedotify.app.data.Playlist
import ru.nedotify.app.data.PlaylistWithCount
import ru.nedotify.app.data.Track

/** Tests reusable browse controls without network, a live player or the user's library. */
class BrowseControlsTest {
    @get:Rule val compose=createComposeRule()

    @Test fun emptyHeroOffersSearchAndImportAtLargeTextSize() {
        var searches=0;var imports=0;var plays=0
        content(largeText=true) {CollectionHero(null,{plays++},{searches++},{imports++})}
        compose.onNodeWithText("Найти музыку").performScrollTo().performClick()
        compose.onNodeWithText("Добавить файлы").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,searches);assertEquals(1,imports);assertEquals(0,plays)}
    }

    @Test fun actualTrackHeroPlaysAndDoesNotInventCollectionCounts() {
        var plays=0
        content {CollectionHero(Track("local:fixture","Тихий океан","Исполнитель",source="local"),{plays++},{},{})}
        compose.onNodeWithText("Тихий океан").assertIsDisplayed()
        compose.onNodeWithText("Исполнитель").assertIsDisplayed()
        compose.onNodeWithText("Слушать снова").performClick()
        compose.onNodeWithText("Найти музыку").assertDoesNotExist()
        compose.runOnIdle {assertEquals(1,plays)}
    }

    @Test fun deletingRecentQueryDoesNotRunSearch() {
        var searches=0;var removals=0
        content {SearchHistoryRow("Длинный запрос на русском языке",{searches++},{removals++})}
        compose.onNodeWithContentDescription("Удалить запрос «Длинный запрос на русском языке»").performClick()
        compose.runOnIdle {assertEquals(0,searches);assertEquals(1,removals)}
        compose.onNodeWithText("Длинный запрос на русском языке").performClick()
        compose.runOnIdle {assertEquals(1,searches)}
    }

    @Test fun playlistMenuActionsDoNotStartPlaybackOrOpenPlaylist() {
        var opens=0;var renames=0;var deletes=0
        content {LibraryPlaylistRow(PlaylistWithCount(Playlist("fixture","Вечерняя атмосфера"),12),{opens++},{renames++},{deletes++})}
        compose.onNodeWithContentDescription("Действия с плейлистом Вечерняя атмосфера").performClick()
        compose.onNodeWithText("Переименовать").performClick()
        compose.runOnIdle {assertEquals(0,opens);assertEquals(1,renames)}
        compose.onNodeWithContentDescription("Действия с плейлистом Вечерняя атмосфера").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.runOnIdle {assertEquals(0,opens);assertEquals(1,deletes)}
    }

    private fun content(largeText:Boolean=false,body:@Composable ()->Unit) {
        compose.setContent {
            val density=LocalDensity.current
            NeDotifyTheme(dark=true) {
                CompositionLocalProvider(LocalGlass provides false,LocalDensity provides Density(density.density,if(largeText)2f else 1f)) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {body()}
                }
            }
        }
    }
}
