package ru.nedotify.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.nedotify.app.data.Playlist
import ru.nedotify.app.data.PlaylistWithCount
import ru.nedotify.app.data.Track

/** Fixture callbacks exercise real row/dialog semantics without a repository or player. */
class TrackRowContentTest {
    @get:Rule val compose=createComposeRule()
    private val track=Track("fixture:row","Трек для проверки","Исполнитель",source="soundcloud")

    @Test fun menuFavoriteDoesNotAlsoPlayTheTrack() {
        var plays=0;var favorites=0
        content(TrackRowUiState(track),TrackRowActions({plays++},{favorites++},{}))
        compose.onNodeWithContentDescription("Действия с треком").performClick()
        compose.onNodeWithText("В избранное").performClick()
        compose.runOnIdle {assertEquals(0,plays);assertEquals(1,favorites)}
        compose.onNodeWithText(track.title).performClick()
        compose.runOnIdle {assertEquals(1,plays)}
    }

    @Test fun completedDownloadCannotBeStartedAgainFromTheMenu() {
        var downloads=0
        content(TrackRowUiState(track,downloadState="complete"),TrackRowActions({},{},{},download={downloads++}))
        compose.onNodeWithText("Скачано").assertExists()
        compose.onNodeWithContentDescription("Действия с треком").performClick()
        compose.onNodeWithText("Скачать для офлайн").assertDoesNotExist()
        compose.onNodeWithText("Скачано на устройство").assertIsNotEnabled()
        compose.runOnIdle {assertEquals(0,downloads)}
    }

    @Test fun deletingRequiresConfirmationAndCancelPerformsNoMutation() {
        var deletions=0;var plays=0
        content(TrackRowUiState(track),TrackRowActions({plays++},{},{},delete={deletions++}))
        compose.onNodeWithContentDescription("Действия с треком").performClick()
        compose.onNodeWithText("Удалить из библиотеки").performClick()
        compose.onNodeWithText("Удалить трек из библиотеки?").assertExists()
        compose.runOnIdle {assertEquals(0,deletions)}
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle {assertEquals(0,deletions)}
        compose.onNodeWithContentDescription("Действия с треком").performClick()
        compose.onNodeWithText("Удалить из библиотеки").performClick()
        compose.onNodeWithText("Удалить").performClick()
        compose.runOnIdle {assertEquals(1,deletions);assertEquals(0,plays)}
    }

    @Test fun playlistChoiceSendsTheStableIdAndDoesNotStartPlayback() {
        var selected="";var plays=0
        val playlists=listOf(PlaylistWithCount(Playlist("stable-id","Мой плейлист"),0))
        content(TrackRowUiState(track,playlists=playlists),TrackRowActions({plays++},{},{selected=it}))
        compose.onNodeWithContentDescription("Действия с треком").performClick()
        compose.onNodeWithText("Добавить в плейлист").performClick()
        compose.onNodeWithText("Мой плейлист").performClick()
        compose.runOnIdle {assertEquals("stable-id",selected);assertEquals(0,plays)}
    }

    private fun content(state:TrackRowUiState,actions:TrackRowActions) {
        compose.setContent {NeDotifyTheme(true) {CompositionLocalProvider(LocalGlass provides false) {TrackRowContent(state,actions)}}}
    }
}
