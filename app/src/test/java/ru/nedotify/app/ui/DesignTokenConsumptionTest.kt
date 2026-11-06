package ru.nedotify.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.nedotify.app.ui.tokens.DesignTokens

/** Checks the theme consumes the shared generated tokens, not a copied palette. */
class DesignTokenConsumptionTest {
    @Test fun darkThemeConsumesPrototypeNeutralAccentAndErrorTokens() {
        val theme=neDotifyColorScheme(true,accentColor("cyan",true),Color(DesignTokens.Dark.onPrimary))
        assertEquals(Color(DesignTokens.Dark.background),theme.background)
        assertEquals(Color(DesignTokens.Dark.surface),theme.surface)
        assertEquals(Color(DesignTokens.Dark.surfaceRaised),theme.surfaceContainerHigh)
        assertEquals(Color(DesignTokens.Dark.onSurface),theme.onSurface)
        assertEquals(Color(DesignTokens.Dark.onSurfaceVariant),theme.onSurfaceVariant)
        assertEquals(Color(DesignTokens.Dark.primary),theme.primary)
        assertEquals(Color(DesignTokens.Dark.error),theme.error)
    }

    @Test fun lightThemeConsumesPrototypeNeutralAccentAndErrorTokens() {
        val theme=neDotifyColorScheme(false,accentColor("cyan",false),Color(DesignTokens.Light.onPrimary))
        assertEquals(Color(DesignTokens.Light.background),theme.background)
        assertEquals(Color(DesignTokens.Light.surface),theme.surface)
        assertEquals(Color(DesignTokens.Light.surfaceRaised),theme.surfaceContainerHigh)
        assertEquals(Color(DesignTokens.Light.onSurface),theme.onSurface)
        assertEquals(Color(DesignTokens.Light.onSurfaceVariant),theme.onSurfaceVariant)
        assertEquals(Color(DesignTokens.Light.primary),theme.primary)
        assertEquals(Color(DesignTokens.Light.error),theme.error)
    }
}
