package ru.nedotify.app.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.LrcLine

class UiPoliciesTest {
    @Test fun `back returns to the actual screen that opened settings and profile`() {
        var navigation=AppNavigationState().selectTab(RootTab.SEARCH)
            .open(AppDestination(AppPage.PROFILE)).open(AppDestination(AppPage.SETTINGS))
        navigation=navigation.back();assertEquals(AppPage.PROFILE,navigation.current.page)
        navigation=navigation.back();assertEquals(AppPage.SEARCH,navigation.current.page)
        navigation=navigation.back();assertEquals(AppNavigationState(),navigation)
        assertEquals(AppNavigationState(),navigation.back())
    }
    @Test fun `visiting current screen never adds a duplicate back step`() {
        val navigation=AppNavigationState().selectTab(RootTab.SEARCH).open(AppDestination(AppPage.PROFILE))
        assertEquals(navigation,navigation.open(AppDestination(AppPage.PROFILE)))
    }
    @Test fun `artwork accepts HTTPS and content but rejects executable and file schemes`() {
        assertEquals("https://images.example/a.jpg",artworkModel("https://images.example/a.jpg",false))
        assertEquals("content://media/external/audio/albumart/1",artworkModel("content://media/external/audio/albumart/1",true))
        for(value in listOf("http://images.example/a","file:///private/token","javascript:alert(1)","data:image/png,x","/sdcard/private","https://name:secret@example.test/x","https:///missing-host","content:/missing-authority","https://bad host/x")) {
            assertNull(value,artworkModel(value,false))
        }
    }
    @Test fun `offline artwork never submits a network request`() {
        assertNull(artworkModel("https://images.example/a.jpg",true))
        assertNull(artworkModel("https://images.example/"+"x".repeat(8192),false))
    }
    @Test fun `binary lyrics search covers boundaries and duplicate timestamps`() {
        val lines=listOf(LrcLine(100,"a"),LrcLine(200,"b"),LrcLine(200,"c"),LrcLine(500,"d"))
        assertEquals(-1,activeLyricLine(emptyList(),0))
        assertEquals(-1,activeLyricLine(lines,99))
        assertEquals(0,activeLyricLine(lines,100))
        assertEquals(2,activeLyricLine(lines,200))
        assertEquals(2,activeLyricLine(lines,499))
        assertEquals(3,activeLyricLine(lines,Long.MAX_VALUE))
    }
    @Test fun `twenty thousand lyrics need logarithmic timestamp reads`() {
        var reads=0
        val lines=object:AbstractList<LrcLine>() {
            override val size=20_000
            override fun get(index:Int):LrcLine {reads++;return LrcLine(index*1000L,"line")}
        }
        assertEquals(12345,activeLyricLine(lines,12_345_678))
        assertTrue("timestamp reads=$reads",reads<=16)
    }
    @Test fun `repeat order matches off all one`() {
        var mode=0
        mode=nextRepeatMode(mode);assertEquals(2,mode)
        mode=nextRepeatMode(mode);assertEquals(1,mode)
        assertEquals(0,nextRepeatMode(mode))
    }
    @Test fun `seek thumb keeps target until acknowledgement`() {
        assertEquals(90_000L,pendingSeekPosition(90_000,30_000,500))
        assertEquals(90_000L,pendingSeekPosition(90_000,89_000,900))
        assertNull(pendingSeekPosition(90_000,90_300,1_000))
        assertNull(pendingSeekPosition(90_000,30_000,2_000))
        assertNull(pendingSeekPosition(null,30_000,0))
    }
    @Test fun `queue identity stays attached to tracks after a reorder`() {
        val a=QueueRow("a","A");val b=QueueRow("b","B")
        val before=queueItemKeys(listOf(a,b))
        assertEquals(before.reversed(),queueItemKeys(listOf(b,a)))
        val duplicates=queueItemKeys(listOf(a,b,a))
        assertEquals(3,duplicates.distinct().size)
    }
    @Test fun `greeting follows clock boundaries`() {
        // Three windows: the morning, the day and the evening.
        val morning=greeting(5);val day=greeting(12);val evening=greeting(18)
        assertNotEquals(morning,day);assertNotEquals(day,evening);assertNotEquals(morning,evening)
        // The window is constant inside and changes at its boundary.
        assertEquals(morning,greeting(11));assertEquals(day,greeting(17))
        assertEquals(evening,greeting(23));assertEquals(evening,greeting(0))
        assertNotEquals(greeting(4),greeting(5))
        assertNotEquals(greeting(11),greeting(12))
        assertNotEquals(greeting(17),greeting(18))
    }
    @Test fun `the play button offers pause only while the sound runs`() {
        val playing=toggleLabel(true,false,false)
        val idle=toggleLabel(false,false,false)
        assertNotEquals(playing,idle)
        // A buffering track that is going to play behaves like a playing one.
        assertEquals(playing,toggleLabel(false,true,true))
        // A buffering track that stays paused does not.
        assertEquals(idle,toggleLabel(false,true,false))
    }
    @Test fun `theme roles keep readable text on app dialog and chip surfaces`() {
        fun contrast(a:Color,b:Color):Double {
            val l1=a.luminance().toDouble();val l2=b.luminance().toDouble()
            return (maxOf(l1,l2)+.05)/(minOf(l1,l2)+.05)
        }
        for(dark in listOf(false,true))for(accent in listOf("cyan","blue","violet","mint")) {
            val primary=accentColor(accent,dark)
            val colors=neDotifyColorScheme(dark,primary,if(dark)Color(0xFF001C2D) else Color.White)
            assertEquals(primary,colors.surfaceTint)
            for((ink,surface) in listOf(colors.onSurface to colors.surfaceContainerHigh,
                colors.onPrimaryContainer to colors.primaryContainer,colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiaryContainer to colors.tertiaryContainer)) {
                assertTrue("$dark/$accent contrast ${contrast(ink,surface)}",contrast(ink,surface)>=4.5)
            }
        }
    }
    /**
     * The finding was that the chips, the dialogs and the sliders kept the
     * Material baseline (the baseline purple). A role that is deliberately
     * chosen must differ from that baseline — a contrast check alone passes
     * for the baseline as well.
     */
    @Test fun `the container roles are chosen and not the material baseline`() {
        for(dark in listOf(false,true)) {
            val baseline=if(dark)darkColorScheme() else lightColorScheme()
            val colors=neDotifyColorScheme(dark,accentColor("cyan",dark),if(dark)Color(0xFF001C2D) else Color.White)
            assertNotEquals("$dark secondaryContainer",baseline.secondaryContainer,colors.secondaryContainer)
            assertNotEquals("$dark tertiaryContainer",baseline.tertiaryContainer,colors.tertiaryContainer)
            assertNotEquals("$dark surfaceContainerHigh",baseline.surfaceContainerHigh,colors.surfaceContainerHigh)
            assertNotEquals("$dark surfaceContainerLow",baseline.surfaceContainerLow,colors.surfaceContainerLow)
            assertNotEquals("$dark surfaceContainerHighest",baseline.surfaceContainerHighest,colors.surfaceContainerHighest)
            assertNotEquals("$dark inverseSurface",baseline.inverseSurface,colors.inverseSurface)
            assertNotEquals("$dark inverseOnSurface",baseline.inverseOnSurface,colors.inverseOnSurface)
            assertNotEquals("$dark inversePrimary",baseline.inversePrimary,colors.inversePrimary)
            assertNotEquals("$dark surfaceVariant",baseline.surfaceVariant,colors.surfaceVariant)
            assertNotEquals("$dark outlineVariant",baseline.outlineVariant,colors.outlineVariant)
        }
    }
}
