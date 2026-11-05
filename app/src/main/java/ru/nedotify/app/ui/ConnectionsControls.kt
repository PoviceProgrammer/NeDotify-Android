package ru.nedotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.nedotify.app.data.AuthPkce
import ru.nedotify.app.data.ProviderConnectionStatus

/**
 * Whether the credential editor may close.
 *
 * The typed value is only dropped when the service has accepted it: a failed
 * save keeps the value, so the user can correct the token instead of typing it
 * again. The Yandex account is recognised by its revision, the Spotify
 * application by the client id it has stored.
 */
internal fun credentialSaveSettled(service:String,entered:String,status:ProviderConnectionStatus,revisionAtSave:Long):Boolean=when(service) {
    "yandex"->status.yandexConnected && status.yandexRevision!=revisionAtSave
    "spotify"->status.spotifyConfigured && status.spotifyClientId.equals(entered.trim(),ignoreCase=true)
    else->false
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionsControls(status:ProviderConnectionStatus,onConfigureSpotify:(String)->Unit,
    onBeginSpotify:()->Unit,onSaveYandex:(String)->Unit,onLogout:(String)->Unit,onOpenCatalog:(String)->Unit) {
    // The editor itself survives a configuration change: the panel must not
    // close under the user because the screen was turned.
    var editor by rememberSaveable {mutableStateOf("")}
    // Credentials intentionally stay in memory only, never in a saved-state Bundle.
    var value by remember {mutableStateOf("")}
    // The value is only dropped when the service has accepted it.
    var saving by rememberSaveable {mutableStateOf(false)}
    // The revision is a Long and stays unboxed: `mutableLongStateOf` needs an
    // explicit saver, because it is not a `MutableState<Long>`.
    var revision by rememberSaveable(saver=Saver<MutableLongState,Any>(
        save={it.longValue},restore={mutableLongStateOf(it as Long)})) {mutableLongStateOf(0L)}
    var revealed by rememberSaveable {mutableStateOf(false)}
    val settled=editor.isNotEmpty() && saving && credentialSaveSettled(editor,value,status,revision)
    LaunchedEffect(settled) {
        if(settled){editor="";value="";saving=false;revealed=false}
    }
    Column(Modifier.fillMaxWidth().glass().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Музыкальные сервисы",style=MaterialTheme.typography.titleMedium)
        Text("Spotify — поиск и перенос коллекции. Для воспроизведения подбирается версия в доступном источнике. Яндекс Музыка использует ваш аккаунт и доступные ему полные записи.",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Spotify · "+when {status.spotifyConnected->"подключён";status.spotifyPending->"ожидает входа";status.spotifyConfigured->"готов к входу";else->"не настроен"},
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton({value=status.spotifyClientId;editor="spotify";saving=false;revealed=false},enabled=!status.busy){Text("Client ID")}
            if(status.spotifyConnected) {
                Button({onOpenCatalog("spotify")},enabled=!status.busy){Text("Коллекция")}
                TextButton({onLogout("spotify")},enabled=!status.busy){Text("Выйти")}
            } else Button(onBeginSpotify,enabled=status.spotifyConfigured && !status.busy){Text(if(status.spotifyPending)"Начать заново" else "Войти")}
            // A started login can be abandoned: the pending login is dropped and
            // the account keeps its own state.
            if(status.spotifyPending)TextButton({onLogout("spotify-login")},enabled=!status.busy){Text("Отменить вход")}
        }
        HorizontalDivider()
        Text("Яндекс Музыка · "+if(status.yandexConnected)status.yandexName.ifBlank {"подключена"} else "не подключена",
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            if(status.yandexConnected) {
                Button({onOpenCatalog("yandex")},enabled=!status.busy){Text("Коллекция")}
                TextButton({onLogout("yandex")},enabled=!status.busy){Text("Выйти")}
            } else OutlinedButton({value="";editor="yandex";saving=false;revealed=false},enabled=!status.busy){Text("Подключить")}
        }
        if(status.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        status.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
    }
    if(editor.isNotEmpty())AlertDialog(onDismissRequest={editor="";value="";saving=false;revealed=false},
        title={Text(if(editor=="spotify")"Настройка Spotify" else "Вход в Яндекс Музыку")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(editor=="spotify") {
                Text("Укажите Client ID своего приложения в Spotify Developer Dashboard. В его настройках зарегистрируйте этот адрес возврата:",style=MaterialTheme.typography.bodySmall)
                SelectionContainer {Text(AuthPkce.REDIRECT,style=MaterialTheme.typography.bodySmall)}
                Text("Вход откроется в браузере. Client secret не требуется. Доступ зависит от ограничений вашего приложения Spotify.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value,{value=it.take(64)},label={Text("Spotify Client ID")},singleLine=true,modifier=Modifier.fillMaxWidth())
            } else {
                Text("Введите собственный OAuth-токен Яндекс Музыки. Пароль от аккаунта вводить не нужно. Доступ к полным записям зависит от аккаунта и подписки.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(value,{value=it.take(8192)},label={Text("OAuth-токен")},singleLine=true,
                    visualTransformation=if(revealed)VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,autoCorrectEnabled=false),
                    trailingIcon={IconButton({revealed=!revealed}){Icon(if(revealed)Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if(revealed)"Скрыть токен" else "Показать токен")}},
                    // The field carries a secret: a screen reader must not read it aloud.
                    modifier=Modifier.fillMaxWidth().semantics {password()})
            }
            // A failed save is reported here: the dialog covers the panel.
            status.error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,
                modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
        }},confirmButton={TextButton({
            // The value is kept until the service has accepted it.
            val entered=value.trim();val service=editor
            revision=if(service=="yandex")status.yandexRevision else 0L
            saving=true
            if(service=="spotify")onConfigureSpotify(entered) else onSaveYandex(entered)
        },enabled=value.isNotBlank() && !status.busy){Text("Сохранить")}},
        dismissButton={TextButton({editor="";value="";saving=false;revealed=false}){Text("Отмена")}})
}
