package ru.nedotify.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ProviderEndpoints(
    val spotifyApi:HttpUrl="https://api.spotify.com/v1/".toHttpUrl(),
    val spotifyToken:HttpUrl="https://accounts.spotify.com/api/token".toHttpUrl(),
    val spotifyAuthorize:HttpUrl="https://accounts.spotify.com/authorize".toHttpUrl(),
    val yandexApi:HttpUrl="https://api.music.yandex.net/".toHttpUrl()
) {
    init {
        listOf(spotifyApi,spotifyToken,spotifyAuthorize,yandexApi).forEach {
            require(it.isHttps || it.host in setOf("127.0.0.1","::1","localhost")) {"Providers require HTTPS"}
            require(it.username.isEmpty() && it.password.isEmpty() && it.fragment==null && it.query==null) {"Provider origins must not contain credentials or query parameters"}
        }
    }
}

/**
 * The transport of the provider path.
 *
 * No global auth interceptor, redirects or response-body errors: credentials
 * stay on their origin. The class reads the **body** and nothing else - the
 * JSON of a provider document is read by the one reader of the area
 * (`ProviderPayload`), with the size limit of the endpoint.
 */
class ProviderHttp(http:OkHttpClient) {
    private val client=http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun text(request:Request,maxBytes:Int=2*1024*1024):String=suspendCancellableCoroutine {continuation->
        val call=client.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException) {
                // A classified failure keeps its classification: the media
                // client rejects a forbidden host and an expired signature
                // with a `SourceFailure`, and the message must survive.
                if(continuation.isActive)continuation.resumeWithException(
                    if(e is SourceFailure)e else SourceFailure("Сервис недоступен. Проверьте подключение и повторите"))
            }
            override fun onResponse(call:Call,response:Response) {
                val result=runCatching {response.use {value->
                    if(!value.isSuccessful)throw SourceFailure(when(value.code){401->"Срок входа истёк. Подключите сервис заново";403->"Сервис ограничил доступ для этого аккаунта или приложения";429->"Слишком много запросов. Повторите позже";else->"Сервис ответил HTTP ${value.code}"},value.code)
                    val body=value.body?:throw SourceFailure("Сервис вернул пустой ответ")
                    if(body.contentLength()>maxBytes)throw SourceFailure("Ответ сервиса слишком большой")
                    body.byteStream().use {input->
                        val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true){val n=input.read(buffer);if(n<0)break;if(output.size()+n>maxBytes)throw SourceFailure("Ответ сервиса слишком большой");output.write(buffer,0,n)}
                        output.toString("UTF-8")
                    }
                }}
                if(continuation.isActive)result.fold(continuation::resume){continuation.resumeWithException(if(it is SourceFailure)it else SourceFailure("Не удалось прочитать ответ сервиса"))}
            }
        })
    }
}
