package ru.nedotify.app.downloads

import androidx.media3.datasource.HttpDataSource
import ru.nedotify.app.data.SourceFailure

/**
 * The HTTP status of a media failure.
 *
 * The status is classified where the response is seen — in the media client,
 * which turns a 401 or a 403 into a `SourceFailure` — but Media3 also
 * produces its own `InvalidResponseCodeException` for the codes the client
 * does not classify. Both are read here, so the retry policy and the user
 * text do not depend on which of the two happened.
 */
fun mediaResponseStatus(error: Throwable): Int? {
    val chain = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
    chain.filterIsInstance<SourceFailure>().firstOrNull { it.httpCode != null }?.let { return it.httpCode }
    return chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
}
