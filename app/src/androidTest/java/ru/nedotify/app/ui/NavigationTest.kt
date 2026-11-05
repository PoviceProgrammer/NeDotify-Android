package ru.nedotify.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import ru.nedotify.app.MainActivity

class NavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun navigateEmptyLibraryAndSettings() {
        compose.onAllNodesWithText("Библиотека").onLast().performClick()
        compose.onNodeWithText("Ваша музыка в одном месте").assertIsDisplayed()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Настройте приложение под себя").assertIsDisplayed()
        compose.onNodeWithText("Светлая").performClick()
        compose.onNodeWithText("Светлая").assertIsSelected()
    }
    @Test fun settingsBackRestoresSearchTextAndTabSwitchKeepsIt() {
        compose.onAllNodesWithText("Поиск").onLast().performClick()
        compose.onNode(hasSetTextAction() and hasText("Поиск музыки")).performTextReplacement("Мой сохранённый запрос")
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.activityRule.scenario.onActivity {it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithText("Мой сохранённый запрос").assertIsDisplayed()
        compose.onAllNodesWithText("Библиотека").onLast().performClick()
        compose.onAllNodesWithText("Поиск").onLast().performClick()
        compose.onNodeWithText("Мой сохранённый запрос").assertIsDisplayed()
    }
}
