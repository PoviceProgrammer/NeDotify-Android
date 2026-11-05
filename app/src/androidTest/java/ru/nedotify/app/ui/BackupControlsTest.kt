package ru.nedotify.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.nedotify.app.data.BackupSummary

/** Mounts components directly in Compose's host; the tests make no player or repository calls. */
class BackupControlsTest {
    @get:Rule val compose=createComposeRule()

    @Test fun previewShowsMatchedCountsAndLocalFileWarningBeforeConfirming() {
        var confirmations=0
        setContent {
            BackupImportDialog(BackupSummary(12,7,3,25,2),false,"",null,{confirmations++},{})
        }

        assertCount("Треков",12)
        assertCount("В избранном",7)
        assertCount("Плейлистов",3)
        assertCount("Записей истории",25)
        assertCount("Локальных треков",2)
        compose.onNodeWithText("Если локальный трек недоступен, откройте его меню → «Указать файл».")
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,confirmations)}
        compose.onNodeWithText("Объединить").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(1,confirmations)}
    }

    @Test fun busyRestoreDisablesBothDialogActionsAndAnnouncesProgress() {
        var confirmations=0
        var dismissals=0
        setContent {
            BackupImportDialog(BackupSummary(12,7,3,25,0),true,"Объединяем библиотеку…",null,
                {confirmations++},{dismissals++})
        }

        compose.onNodeWithText("Объединить").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").assertIsNotEnabled()
        compose.onNodeWithText("Объединяем библиотеку…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Локальных треков").assertDoesNotExist()
        compose.runOnIdle {assertEquals(0,confirmations);assertEquals(0,dismissals)}
    }

    @Test fun failedRestoreKeepsErrorVisibleAndAllowsRetryOrCancel() {
        var retries=0
        var dismissals=0
        setContent {
            BackupImportDialog(BackupSummary(12,7,3,25,0),false,"","Не удалось сохранить библиотеку. Повторите действие",
                {retries++},{dismissals++})
        }

        compose.onNodeWithText("Не удалось сохранить библиотеку. Повторите действие")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Объединить").assertIsEnabled().performClick()
        compose.onNodeWithText("Отмена").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(1,retries);assertEquals(1,dismissals)}
    }

    @Test fun busyBackupDisablesExportAndImport() {
        var exports=0
        var imports=0
        setContent {
            BackupControls(true,"Проверяем резервную копию…",null,false,{exports++},{imports++})
        }

        compose.onNodeWithText("Сохранить копию").assertIsNotEnabled()
        compose.onNodeWithText("Восстановить из копии").assertIsNotEnabled()
        compose.onNodeWithText("Проверяем резервную копию…").assertIsDisplayed()
        compose.runOnIdle {assertEquals(0,exports);assertEquals(0,imports)}
    }

    @Test fun openDocumentPickerPreventsLaunchingAnotherOperation() {
        setContent {BackupControls(false,"",null,true,{},{})}

        compose.onNodeWithText("Сохранить копию").assertIsNotEnabled()
        compose.onNodeWithText("Восстановить из копии").assertIsNotEnabled()
        compose.onNodeWithText("Выберите файл или место для сохранения").assertIsDisplayed()
    }

    @Test fun failedExportShowsErrorAndBothDocumentActionsCanBeRepeated() {
        var exports=0
        var imports=0
        setContent {BackupControls(false,"","Хранилище недоступно",false,{exports++},{imports++})}

        compose.onNodeWithText("Хранилище недоступно").assertIsDisplayed()
        compose.onNodeWithText("Сохранить копию").assertIsEnabled().performClick()
        compose.onNodeWithText("Восстановить из копии").assertIsEnabled().performClick()
        compose.onNodeWithText("Аудиофайлы и скачанная музыка в копию не входят. Локальные файлы на другом устройстве нужно выбрать заново.")
            .assertIsDisplayed()
        compose.runOnIdle {assertEquals(1,exports);assertEquals(1,imports)}
    }

    private fun assertCount(label:String,value:Int) {
        compose.onNode(hasText(label) and hasAnySibling(hasText(value.toString()))).assertExists()
    }

    private fun setContent(content:@Composable ()->Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalGlass provides false) {MaterialTheme(content=content)}
        }
    }
}
