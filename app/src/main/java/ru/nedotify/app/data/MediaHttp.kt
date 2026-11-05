package ru.nedotify.app.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * The HTTP client of the media path.
 *
 * The metadata client and the media client are different jobs. A metadata
 * request is a small document that must arrive quickly, so it carries a
 * 30 s **total** call budget. A media request is the audio itself: Media3
 * reads it through a blocking `Call.execute()`, so a total budget would also
 * bound the *transfer* — a track that needs more than the budget is cut off
 * in the middle, and a download never completes.
 *
 * The media client therefore has **no total budget**. What bounds it instead:
 *  * a 20 s **read** timeout — a dead connection is detected, a slow one is not;
 *  * the download's own no-progress watchdog and retry policy
 *    (`downloads/TrackDownloadWorker.kt`), which bound a slow-but-alive
 *    transfer by cancelling and resuming it.
 *
 * The trade-off is deliberate and recorded: a *stalled* CDN is still detected
 * by the read timeout, but a connection that keeps trickling bytes is not
 * bounded by the client. An unbounded total budget was chosen over a
 * generous one because a generous total budget would cut off a legitimately
 * slow long track, which is the very defect this client exists to remove.
 */
object MediaHttp {
    const val CONNECT_TIMEOUT_SECONDS = 12L
    const val READ_TIMEOUT_SECONDS = 20L
    const val MAX_REDIRECTS = 5

    /**
     * The media client.
     *
     * @param base the client whose transport (connection pool, dispatcher,
     *   interceptors) is reused; its total call budget is replaced.
     * @param policy the host allow-list the request and every redirect hop
     *   are checked against.
     */
    fun client(base: OkHttpClient = OkHttpClient(), policy: MediaHostPolicy = MediaUrlPolicy,
        connectTimeoutSeconds: Long = CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS): OkHttpClient =
        base.newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            // No total budget: the transfer of a media file is not a metadata call.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // The redirect policy below follows the hops itself, one validated hop at a time.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(MediaRedirectInterceptor(policy, MAX_REDIRECTS))
            .addInterceptor(MediaPolicyInterceptor(policy))
            .build()
}

/**
 * The source that produced the media request.
 *
 * The source travels with the request as an OkHttp **tag**: a tag is never
 * written into the request line or the headers, so it cannot leak into the
 * network. The tag is what keeps the whole redirect chain inside the
 * allow-list of *one* source: the hop is checked against the source of the
 * first URL, not against the union of the allow-lists.
 */
data class MediaSourceTag(val source: String)

/**
 * Rejects a URL the source may not use, sends the headers the CDNs expect and
 * classifies the two status codes that mean "the signature is not usable any
 * more".
 *
 * The interceptor sits **after** the redirect interceptor, so every hop of a
 * redirect passes through it and is validated on its own.
 */
class MediaPolicyInterceptor(private val policy: MediaHostPolicy) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source = request.tag(MediaSourceTag::class.java)?.source
            ?: policy.sourceFor(request.url)
        if (source == null || !policy.accepts(source, request.url)) {
            policy.onRefusedHost(request.url.host)
            throw SourceFailure("Источник вернул неподдерживаемый адрес аудио")
        }
        val builder = request.newBuilder().header("User-Agent", MediaUrlPolicy.USER_AGENT)
        val referer = policy.refererFor(source)
        if (referer == null) builder.removeHeader("Referer") else builder.header("Referer", referer)
        val response = chain.proceed(builder.build())
        val status = response.code
        if (status == 401 || status == 403) {
            response.close()
            // The classification must survive the trip through Media3: the
            // streaming path reports it, the download path retries on it.
            throw SourceFailure(mediaStatusMessage(status) ?: "Источник ограничил доступ к аудио", status)
        }
        return response
    }
}

/**
 * Follows the redirects of a media request itself, so that every hop can be
 * checked against the host allow-list. OkHttp's own redirect handling cannot
 * be used for that: it runs behind the application interceptors and would
 * connect to the new host before anything could look at it.
 *
 * The chain stays inside **one** source. The first URL decides which source
 * the request belongs to — the resolution boundary has already checked that
 * the URL the provider handed out belongs to the provider's own list — and
 * every hop must belong to that same source. A hop to another provider's
 * list is refused: the `Referer` of the first source would be sent to the
 * other provider's CDN, and the credential of one provider would travel to
 * another provider's host.
 */
class MediaRedirectInterceptor(private val policy: MediaHostPolicy,
    private val maxRedirects: Int = MediaHttp.MAX_REDIRECTS) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val origin = request.tag(MediaSourceTag::class.java)?.source
            ?: policy.sourceFor(request.url)
        if (origin == null) {
            policy.onRefusedHost(request.url.host)
            throw SourceFailure("Источник вернул неподдерживаемый адрес аудио")
        }
        var response = chain.proceed(request.newBuilder().tag(MediaSourceTag::class.java, MediaSourceTag(origin)).build())
        var hops = 0
        while (isRedirect(response.code)) {
            val location = response.header("Location")
            if (location == null) return response
            hops++
            val target = request.url.resolve(location)
            response.close()
            if (hops > maxRedirects) throw SourceFailure("Источник перенаправляет запрос слишком часто")
            if (target == null || !policy.accepts(origin, target)) {
                policy.onRefusedHost(target?.host.orEmpty())
                throw SourceFailure("Источник перенаправил запрос на неподдерживаемый адрес")
            }
            request = redirectRequest(request, target, response.code)
            response = chain.proceed(request.newBuilder().tag(MediaSourceTag::class.java, MediaSourceTag(origin)).build())
        }
        return response
    }
    private fun redirectRequest(request: Request, target: okhttp3.HttpUrl, status: Int): Request {
        val builder = request.newBuilder().url(target)
        // 303 and the historic 301/302 turn a POST into a GET.
        if ((status == 303 || status == 301 || status == 302) && request.method != "GET")
            builder.method("GET", null)
        // OkHttp's own redirect handling drops the Authorization header when
        // the hop cannot reuse the connection — a different host, a different
        // port or a different scheme. The hop is followed here, so the header
        // has to be dropped by hand: a credential must never reach a host the
        // first URL did not name.
        if (!sameConnection(request.url, target)) builder.removeHeader("Authorization")
        return builder.build()
    }
    /** Whether a hop may reuse the connection of the request it answers. */
    private fun sameConnection(from: okhttp3.HttpUrl, to: okhttp3.HttpUrl): Boolean =
        from.host.equals(to.host, ignoreCase = true) && from.port == to.port && from.scheme == to.scheme
    private fun isRedirect(code: Int) = code == 301 || code == 302 || code == 303 || code == 307 || code == 308
}
