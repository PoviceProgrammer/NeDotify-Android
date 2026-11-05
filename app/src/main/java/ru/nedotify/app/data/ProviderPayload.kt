package ru.nedotify.app.data

import kotlinx.coroutines.CancellationException
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The provider's JSON reader.
 *
 * This is the **one** reader of the area: the body is read through
 * `ProviderHttp.text` with an explicit bound and parsed here with the rules
 * the provider path has always used - a bounded nesting depth, a balanced
 * document and a single object at the root. The endpoints whose payload grows
 * with the account (the library and the playlists) need their own limit, and
 * every other document keeps the tight one.
 */
internal object ProviderPayload {
    /** A normal provider document. */
    const val DEFAULT_MAX_BYTES=2*1024*1024
    /** A document that grows with the account: the whole library, one playlist. */
    const val ACCOUNT_MAX_BYTES=8*1024*1024
    /** The nesting depth a provider document may have. */
    const val MAX_DEPTH=32

    suspend fun json(network:ProviderHttp,request:Request,maxBytes:Int=DEFAULT_MAX_BYTES):JSONObject=parse(network.text(request,maxBytes))

    fun parse(body:String):JSONObject {
        // Bound the recursion before the platform JSON parser. Byte bounds alone do
        // not prevent a tiny deeply nested response from exhausting the stack.
        var depth=0;var quoted=false;var escaped=false
        body.forEach {char->
            if(quoted) {
                if(escaped)escaped=false else if(char=='\\')escaped=true else if(char=='"')quoted=false
            } else when(char) {
                '"'->quoted=true
                '[','{'->{depth++;if(depth>MAX_DEPTH)throw SourceFailure("Ответ сервиса слишком сложный")}
                ']','}'->{depth--;if(depth<0)throw SourceFailure("Сервис вернул некорректный ответ")}
            }
        }
        if(quoted || depth!=0)throw SourceFailure("Сервис вернул некорректный ответ")
        return try {
            val parser=JSONTokener(body)
            val result=parser.nextValue() as? JSONObject ?: throw SourceFailure("Сервис вернул некорректный ответ")
            if(parser.nextClean()!=0.toChar())throw SourceFailure("Сервис вернул некорректный ответ")
            result
        } catch(cancelled:CancellationException){throw cancelled}
        catch(error:SourceFailure){throw error}
        catch(_:Exception){throw SourceFailure("Сервис вернул некорректный ответ")}
    }
}
