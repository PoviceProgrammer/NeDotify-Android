package ru.nedotify.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.nedotify.app.data.DailyListening
import ru.nedotify.app.data.LrcLine
import java.time.LocalDate

/** Components run without creating a MediaController, repository or network client. */
class ProfilePlayerComponentsTest {
    @get:Rule val compose=createComposeRule()

    @Test fun lyricsFromPreviousTrackAreNeverDisplayed() {
        content {LyricsContent(LyricsState(trackId="old",plain="Чужой текст"),"new",0,100_000,true,Modifier.fillMaxSize(),{},{})}
        compose.onNodeWithText("Чужой текст").assertDoesNotExist()
        compose.onNodeWithText("Ищем текст…").assertExists()
    }

    @Test fun failedLyricsOfferRealRetry() {
        var retries=0
        content {LyricsContent(LyricsState(trackId="same",error="Источник недоступен"),"same",0,100_000,true,Modifier.fillMaxSize(),{},{retries++})}
        compose.onNodeWithText("Источник недоступен").assertExists()
        compose.onNodeWithText("Повторить").performClick()
        compose.runOnIdle {assertEquals(1,retries)}
    }

    @Test fun instrumentalStateIsDifferentFromMissingLyrics() {
        content {LyricsContent(LyricsState(trackId="same",instrumental=true),"same",0,100_000,true,Modifier.fillMaxSize(),{},{})}
        compose.onNodeWithText("Без слов").assertExists()
        compose.onNodeWithText("Текст не найден").assertDoesNotExist()
    }

    @Test fun syncedLineSeeksToItsTimestamp() {
        var seek=-1L
        val lines=listOf(LrcLine(0,"Начало"),LrcLine(12_000,"Вторая строка"))
        content {LyricsContent(LyricsState(trackId="same",lines=lines),"same",0,100_000,false,Modifier.fillMaxSize(),{seek=it},{})}
        compose.onNodeWithText("Вторая строка").performClick()
        compose.runOnIdle {assertEquals(12_000L,seek)}
    }

    @Test fun lineSeekingIsDisabledWhenDurationIsUnknown() {
        content {LyricsContent(LyricsState(trackId="same",lines=listOf(LrcLine(0,"Начало"))),"same",0,0,false,Modifier.fillMaxSize(),{},{})}
        compose.onNodeWithText("Начало").assertIsNotEnabled()
    }

    @Test fun miniControlsDoNotAlsoOpenPlayer() {
        var opened=0
        var toggled=0
        var liked=0
        content {MiniPlayerBar(NowPlaying(id="track",title="Трек",artist="Артист",playing=true,duration=100_000),40_000,false,{opened++},{toggled++},{liked++})}
        compose.onNodeWithContentDescription("Пауза").performClick()
        compose.onNodeWithContentDescription("В избранное").performClick()
        compose.runOnIdle {assertEquals(1,toggled);assertEquals(1,liked);assertEquals(0,opened)}
        compose.onNodeWithText("Трек").performClick()
        compose.runOnIdle {assertEquals(1,opened)}
    }

    @Test fun emptyTransportCannotSendPlaybackActions() {
        content {PlayerTransport(NowPlaying(),{},{},{},{},{})}
        compose.onNodeWithContentDescription("Воспроизвести").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Следующий трек").assertIsNotEnabled()
    }

    @Test fun bufferingTransportExposesPauseActionToTalkBack() {
        var toggled=0
        content {PlayerTransport(NowPlaying(id="track",buffering=true,playWhenReady=true),{},{},{toggled++},{},{})}
        compose.onNodeWithContentDescription("Пауза").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(1,toggled)}
    }

    @Test fun pausedBufferingMiniPlayerExposesPlayWithoutOpeningTheSheet() {
        var toggled=0;var opened=0
        content {MiniPlayerBar(NowPlaying(id="track",buffering=true,playWhenReady=false),0,false,{opened++},{toggled++},{})}
        compose.onNodeWithContentDescription("Воспроизвести").performClick()
        compose.runOnIdle {assertEquals(1,toggled);assertEquals(0,opened)}
    }

    @Test fun zeroBaselineDoesNotInventPercentageGrowth() {
        content {ReportComparison(0,null)}
        compose.onNodeWithText("В прошлом периоде прослушиваний не было").assertExists()
        compose.onNode(hasText("%",substring=true)).assertDoesNotExist()
    }

    @Test fun chartExposesDayAndTimeToScreenReader() {
        content {ListeningChart(listOf(DailyListening(LocalDate.of(2026,10,18),120_000)))}
        compose.onNode(hasContentDescription("2 мин",substring=true)).assertExists()
    }

    private fun content(block:@Composable ()->Unit) {
        compose.setContent {CompositionLocalProvider(LocalGlass provides false) {MaterialTheme {Box(Modifier.fillMaxSize()){block()}}}}
    }
}
