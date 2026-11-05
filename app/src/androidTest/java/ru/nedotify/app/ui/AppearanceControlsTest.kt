package ru.nedotify.app.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.nedotify.app.data.Appearance

class AppearanceControlsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun batteryFallbackDoesNotAccessMissingBackdrop() {
        compose.setContent {
            NeDotifyTheme(true) {CompositionLocalProvider(LocalGlass provides true,LocalPowerSave provides true) {
                // No LocalBackdrop is provided: attempting the expensive glass branch would throw.
                Box(Modifier.glass().padding(20.dp)) {Text("Непрозрачная панель")}
            }}
        }
        compose.onNodeWithText("Непрозрачная панель").assertIsDisplayed()
    }
    @Test fun modeAndAccentAreIndependentActions() {
        var mode="";var accent=""
        compose.setContent {
            NeDotifyTheme(false) {CompositionLocalProvider(LocalGlass provides false) {
                AppearanceControls(Appearance(mode="light",accent="violet"),{mode=it},{accent=it},{},{})
            }}
        }
        compose.onNodeWithText("Светлая").assertIsSelected()
        compose.onNodeWithText("Фиолетовый").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Тёмная").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("dark",mode);assertEquals("",accent)}
        compose.onNodeWithText("Мятный").performScrollTo().performClick()
        compose.runOnIdle {assertEquals("mint",accent);assertEquals("dark",mode)}
    }
    @Test fun glassAndBackgroundSwitchesAreSeparateAndAccessible() {
        var glass:Boolean?=null;var background:Boolean?=null
        compose.setContent {
            NeDotifyTheme(true) {CompositionLocalProvider(LocalGlass provides false) {
                AppearanceControls(Appearance(glass=true,background=false),{},{},{glass=it},{background=it})
            }}
        }
        compose.onNodeWithText("Стеклянные панели").performClick()
        compose.runOnIdle {assertEquals(false,glass);assertEquals(null,background)}
        compose.onNodeWithText("Атмосферный фон").performClick()
        compose.runOnIdle {assertEquals(true,background)}
    }

    @Test fun systemAccentIsASeparateAccessibleChoice() {
        var accent="";var mode=""
        compose.setContent {
            NeDotifyTheme(true,accent="system") {CompositionLocalProvider(LocalGlass provides false) {
                AppearanceControls(Appearance(mode="dark",accent="system"),{mode=it},{accent=it},{},{})
            }}
        }
        compose.onNodeWithText("Из системы").performScrollTo().assertIsSelected().performClick()
        compose.runOnIdle {assertEquals("system",accent);assertEquals("",mode)}
    }

    @Test fun systemAccentUsesMatchingOnPrimaryAndKeepsNeutralBackgrounds() {
        val dark=mutableStateOf(false)
        var actualPrimary=Color.Unspecified;var actualOnPrimary=Color.Unspecified;var background=Color.Unspecified
        var expectedPrimary=Color.Unspecified;var expectedOnPrimary=Color.Unspecified
        compose.setContent {
            val context=LocalContext.current
            val expected=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S) {
                val system=if(dark.value)dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                system.primary to system.onPrimary
            } else accentColor("cyan",dark.value) to if(dark.value)Color(0xFF001C2D) else Color.White
            NeDotifyTheme(dark.value,accent="system") {
                val colors=MaterialTheme.colorScheme
                SideEffect {
                    expectedPrimary=expected.first;expectedOnPrimary=expected.second
                    actualPrimary=colors.primary;actualOnPrimary=colors.onPrimary;background=colors.background
                }
            }
        }
        compose.runOnIdle {
            assertEquals(expectedPrimary,actualPrimary);assertEquals(expectedOnPrimary,actualOnPrimary)
            assertEquals(Color(0xFFF1F6FB),background);dark.value=true
        }
        compose.runOnIdle {
            assertEquals(expectedPrimary,actualPrimary);assertEquals(expectedOnPrimary,actualOnPrimary)
            assertEquals(Night,background)
        }
    }
}
