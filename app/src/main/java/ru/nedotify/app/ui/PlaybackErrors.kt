package ru.nedotify.app.ui

import ru.nedotify.app.data.SourceFailure
import java.io.FileNotFoundException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun playbackFailureMessage(error:Throwable):String {
    val chain=generateSequence(error){it.cause}.take(12).toList()
    return chain.filterIsInstance<SourceFailure>().firstOrNull()?.message?.take(240)
        ?:when {
            chain.any {it is FileNotFoundException || it is SecurityException}->"Файл удалён или доступ к нему потерян. Выберите его заново"
            chain.any {it is UnknownHostException}->"Не удалось подключиться к источнику. Проверьте соединение"
            chain.any {it is SocketTimeoutException}->"Источник не ответил вовремя. Повторите попытку"
            else->"Не удалось воспроизвести трек. Повторите попытку или выберите другой источник"
        }
}

/** A session error without a message must never clear the message channel. */
fun sessionErrorMessage(message:String?):String =
    message?.takeIf {it.isNotBlank()}?:"Не удалось выполнить команду плеера"

/**
 * The error of the player state that still has to be shown.
 *
 * The same error is never shown twice, and a null error never clears the
 * message channel.
 */
internal fun playerErrorMessage(error:Throwable?,alreadyShown:String?):String? =
    error?.let {playbackFailureMessage(it)}?.takeIf {it!=alreadyShown}
