package ru.nedotify.app.data

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The HTTP client of the cover art.
 *
 * Coil's own network fetcher falls back to a **stock** `OkHttpClient` when
 * the app installs no image loader of its own: no connect timeout, no read
 * timeout and no bound on the size of the response. A cover is a small
 * image; a server that answers with an endless stream must not be able to
 * fill the heap of the app.
 */
object ImageHttp {
    /** The largest cover the app accepts. */
    const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 15L
    const val CALL_TIMEOUT_SECONDS = 30L

    /** The client: explicit timeouts and a hard cap on the response size. */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(ResponseSizeLimitInterceptor(MAX_IMAGE_BYTES))
        .build()

    /** The image loader the app installs. */
    fun loader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(client())) }
        .build()
}

/**
 * Refuses a response that is larger than the cap.
 *
 * The declared length is checked first, so an oversized image is refused
 * before its body is read at all. A body that declares no length is counted
 * while it is read, so a chunked answer cannot slip through.
 */
class ResponseSizeLimitInterceptor(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        if (body.contentLength() > maxBytes) {
            response.close()
            throw IOException("The image is larger than $maxBytes bytes")
        }
        return response.newBuilder().body(LimitedResponseBody(body, maxBytes)).build()
    }
}

private class LimitedResponseBody(private val delegate: ResponseBody, private val maxBytes: Long) : ResponseBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource {
        val source = delegate.source()
        return object : ForwardingSource(source) {
            private var read = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count > 0L) {
                    read += count
                    if (read > maxBytes) throw IOException("The image is larger than $maxBytes bytes")
                }
                return count
            }
        }.buffer()
    }
}
