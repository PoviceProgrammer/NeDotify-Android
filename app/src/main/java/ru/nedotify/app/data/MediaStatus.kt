package ru.nedotify.app.data

/**
 * The text of a media failure.
 *
 * The HTTP status of a media request is classified in one place, so the
 * streaming path and the download path cannot drift apart: a 401 means the
 * signed URL expired, a 403 means the source refused the request. Both are
 * worth another attempt with a fresh signature.
 */
fun mediaStatusMessage(status: Int): String? = when (status) {
    401 -> "Ссылка на аудио истекла. Повторите попытку"
    403 -> "Источник ограничил доступ к аудио. Повторите попытку"
    else -> null
}

/**
 * The text of a media failure that reaches the user.
 *
 * The media path classifies a refused or an expired signature as a
 * `SourceFailure` with a text of its own. The failure reaches the player
 * wrapped in Media3's own exception, and the text must survive the wrapping
 * — otherwise the user sees a generic error instead of "the link has
 * expired".
 *
 * The text is never a URL: a signed URL carries a signature.
 */
fun mediaSessionError(error: Throwable): String? =
    generateSequence<Throwable>(error) { it.cause }.take(12).filterIsInstance<SourceFailure>()
        .firstOrNull()?.message?.take(240)
