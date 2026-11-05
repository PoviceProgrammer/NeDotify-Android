package ru.nedotify.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** These classes deliberately redact secrets from diagnostic string interpolation. */
class ProviderToken(val access:String,val refresh:String,val expiresAt:Long) {
    override fun toString()="ProviderToken(<redacted>)"
    fun json():String=JSONObject().put("access",access).put("refresh",refresh).put("expiresAt",expiresAt).toString()
    fun fresh(now:Long):Boolean=expiresAt>now+60_000L
    companion object {
        fun stored(raw:String):ProviderToken {
            val item=JSONObject(raw)
            val expires=item.getLong("expiresAt").also {require(it>=0)}
            // `optText` and not `optString`: on AOSP a JSON null becomes the text
            // "null" and would be read as a refresh token.
            return ProviderToken(AuthPkce.secret(item.optText("access")),item.optText("refresh").let {if(it.isBlank())"" else AuthPkce.secret(it)},expires)
        }
        fun response(item:JSONObject,now:Long,oldRefresh:String=""):ProviderToken {
            if(!item.optText("token_type").equals("Bearer",true))throw SourceFailure("Сервис вернул неподдерживаемый тип авторизации")
            val rawLifetime=item.opt("expires_in")
            val lifetime=when(rawLifetime){is Int->rawLifetime.toLong();is Long->rawLifetime;else->throw SourceFailure("Сервис не указал срок авторизации")}
            if(lifetime !in 1..31_536_000L)throw SourceFailure("Сервис вернул неверный срок авторизации")
            // A missing refresh token and a JSON null are the same thing.
            val refresh=item.optText("refresh_token").let {if(it.isBlank())oldRefresh else AuthPkce.secret(it)}
            return ProviderToken(AuthPkce.secret(item.optText("access_token")),refresh,now+lifetime*1000)
        }
    }
}

class PendingSpotify(val state:String,val verifier:String,val createdAt:Long,val clientId:String) {
    override fun toString()="PendingSpotify(<redacted>)"
    fun json():String=JSONObject().put("state",state).put("verifier",verifier).put("createdAt",createdAt).put("clientId",clientId).toString()
    /** Whether the login is out of its window: it can no longer be finished. */
    fun expired(now:Long):Boolean=now<createdAt||now-createdAt>PendingLogins.MAX_AGE_MS
    companion object {fun stored(raw:String):PendingSpotify=JSONObject(raw).let {
        val state=it.optText("state");val verifier=it.optText("verifier")
        require(state.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        AuthPkce.challenge(verifier)
        PendingSpotify(state,verifier,it.getLong("createdAt").also {stamp->require(stamp>=0)},AuthPkce.validateClientId(it.optText("clientId")))
    }}
}

/**
 * The pending logins.
 *
 * A second PKCE start must not destroy the first one: the user may have two
 * browser tabs open and each of them carries its own state. Every start
 * therefore adds its own login, and the callback is matched against the login
 * whose state it carries.
 *
 * The list is bounded and every login expires on its own, so a login that was
 * never finished cannot pile up.
 */
class PendingLogins(val items:List<PendingSpotify>) {
    override fun toString()="PendingLogins(${items.size})"
    fun isEmpty()=items.isEmpty()
    fun isNotEmpty()=items.isNotEmpty()
    fun json():String {val array=JSONArray();items.forEach {array.put(JSONObject(it.json()))};return array.toString()}
    /** The newest login first; the oldest one is dropped when the list is full. */
    fun add(pending:PendingSpotify):PendingLogins=PendingLogins((listOf(pending)+items.filter {it.state!=pending.state}).take(MAX))
    /** The login the callback belongs to, compared in constant time. */
    fun match(state:String):PendingSpotify?=items.firstOrNull {MessageDigest.isEqual(it.state.toByteArray(Charsets.UTF_8),state.toByteArray(Charsets.UTF_8))}
    fun without(state:String):PendingLogins=PendingLogins(items.filter {it.state!=state})
    /** The logins that are still inside the login window. */
    fun fresh(now:Long):PendingLogins=PendingLogins(items.filter {!it.expired(now)})
    /** The logins that belong to the current Spotify application. */
    fun forClient(clientId:String):PendingLogins=PendingLogins(items.filter {it.clientId==clientId})
    companion object {
        /** How long a started login may be finished. */
        const val MAX_AGE_MS=600_000L
        /** How many logins may be pending at the same time. */
        const val MAX=4
        val EMPTY=PendingLogins(emptyList())
        /** A single pending login (the format before the list) is still accepted. */
        fun stored(raw:String):PendingLogins {
            val trimmed=raw.trim()
            if(!trimmed.startsWith("["))return PendingLogins(listOf(PendingSpotify.stored(trimmed)))
            val array=JSONArray(trimmed);val items=mutableListOf<PendingSpotify>()
            for(index in 0 until array.length()) {
                val item=array.optJSONObject(index) ?: continue
                items+=PendingSpotify.stored(item.toString())
            }
            return PendingLogins(items)
        }
    }
}

/** Account identity and token are committed in one authenticated encrypted record. */
class YandexCredential(val access:String,val uid:String,val name:String) {
    override fun toString()="YandexCredential(<redacted>)"
    fun json():String=JSONObject().put("access",access).put("uid",uid).put("name",name).toString()
    companion object {
        fun stored(raw:String):YandexCredential=JSONObject(raw).let {
            YandexCredential(AuthPkce.secret(it.optText("access")),userId(it.optText("uid")),it.optText("name").take(100))
        }
        fun userId(raw:String):String=raw.also {require(it.matches(Regex("[0-9]{1,24}")))}
    }
}

object AuthPkce {
    const val REDIRECT="nedotify://oauth/spotify"
    const val SCOPE="playlist-read-private playlist-read-collaborative user-library-read"
    private val random=SecureRandom()
    private fun randomString():String=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also(random::nextBytes))
    fun pending(clientId:String,now:Long):PendingSpotify=PendingSpotify(randomString(),randomString(),now,validateClientId(clientId))
    /**
     * The Spotify application of the user.
     *
     * The id is hexadecimal, so it has one spelling: it is canonicalised once,
     * here. The model compares the id with the stored one, and the panel
     * compares it with the one the user typed - if the two compared the id
     * differently, re-entering the same application in another case would have
     * destroyed the token and every started login.
     */
    fun validateClientId(value:String):String=value.trim().lowercase().also {if(!it.matches(Regex("[a-f0-9]{32}")))throw SourceFailure("Spotify Client ID должен содержать 32 шестнадцатеричных символа")}
    fun challenge(verifier:String):String {
        require(verifier.matches(Regex("[A-Za-z0-9._~-]{43,128}")))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
    fun secret(value:String):String=checkSecret(value,"Сервис вернул некорректные данные авторизации")
    /**
     * A credential the user typed in.
     *
     * The service did not return it, so it must not say so: the user is told
     * that *their* token is malformed.
     */
    fun userSecret(value:String):String=checkSecret(value,"Введённый токен выглядит некорректно")
    private fun checkSecret(value:String,message:String):String=value.also {
        // HTTP bearer credentials are ASCII. Reject malformed Unicode before
        // OkHttp can include a rejected header's value in an exception message.
        if(it.length !in 6..8192 || it.any {c->c !in '!'..'~'})throw SourceFailure(message)
    }
    /** The callback as it arrived, before it is matched against a pending login. */
    class Callback(val state:String,val code:String,val error:String?) {
        override fun toString()="Callback(<redacted>)"
    }
    /** What the callback means for the pending login it belongs to. */
    sealed class Outcome {
        /** The login may continue: the code may be exchanged. */
        class Granted(val code:String):Outcome()
        /** The login is over: the pending login must be removed. */
        class Denied(val message:String):Outcome()
    }
    /**
     * Parses the callback.
     *
     * The state is not checked here: it belongs to a login, and the login is
     * only known once the callback is matched against the pending logins.
     */
    fun parse(raw:String):Callback {
        val uri=try {URI(raw)}catch(_:Exception){throw SourceFailure("Неверный адрес возврата Spotify")}
        if(uri.scheme!="nedotify" || uri.rawAuthority!="oauth" || uri.rawPath!="/spotify" || uri.rawFragment!=null || raw.length>16_384)
            throw SourceFailure("Адрес возврата Spotify не совпадает с настройками")
        val params=linkedMapOf<String,String>()
        try {
            uri.rawQuery.orEmpty().split('&').filter {it.isNotEmpty()}.forEach {part->
                val values=part.split('=',limit=2)
                val key=decodeQuery(values[0])
                if(params.containsKey(key))throw IllegalArgumentException()
                params[key]=decodeQuery(values.getOrElse(1){""})
            }
        }catch(_:Exception){throw SourceFailure("Некорректный ответ авторизации Spotify")}
        val state=params["state"]?:throw SourceFailure("Spotify не вернул проверочный код входа")
        return Callback(state,params["code"].orEmpty(),params["error"])
    }
    /**
     * Whether the given address is the redirect the app registered.
     *
     * A foreign address must never be treated as an authorization callback.
     */
    fun isRedirect(raw:String):Boolean=try {
        val uri=URI(raw)
        uri.scheme=="nedotify"&&uri.rawAuthority=="oauth"&&uri.rawPath=="/spotify"&&uri.rawFragment==null
    }catch(_:Exception){false}
    /**
     * The value of one query parameter.
     *
     * A URI query is percent-encoded and not form-encoded: a `+` is a literal
     * plus sign. `URLDecoder` would turn it into a space and would change the
     * authorization code.
     */
    private fun decodeQuery(raw:String):String {
        val output=ByteArrayOutputStream()
        var index=0
        while(index<raw.length) {
            val char=raw[index]
            if(char=='%') {
                if(index+2>=raw.length)throw IllegalArgumentException("Truncated escape")
                val code=raw.substring(index+1,index+3).toIntOrNull(16) ?: throw IllegalArgumentException("Broken escape")
                output.write(code);index+=3
            } else {
                val bytes=char.toString().toByteArray(Charsets.UTF_8)
                output.write(bytes,0,bytes.size);index++
            }
        }
        return String(output.toByteArray(),Charsets.UTF_8)
    }
    /** Exact callback, unique parameters, constant-time state, age and single-use enforced by caller. */
    fun decide(callback:Callback,pending:PendingSpotify,now:Long):Outcome {
        if(now<pending.createdAt || now-pending.createdAt>PendingLogins.MAX_AGE_MS)throw SourceFailure("Время входа истекло. Начните вход заново")
        if(!MessageDigest.isEqual(callback.state.toByteArray(Charsets.UTF_8),pending.state.toByteArray(Charsets.UTF_8)))
            throw SourceFailure("Проверка безопасности входа не пройдена. Начните вход заново")
        if(callback.error!=null)return if(callback.error=="access_denied")Outcome.Denied("Вход Spotify отменён")
            else Outcome.Denied("Spotify отклонил вход. Начните заново")
        val code=callback.code
        if(code.isBlank() || code.length>8192 || code.any {it.isISOControl()})throw SourceFailure("Spotify не вернул код авторизации")
        return Outcome.Granted(code)
    }
    /** The one-shot contract: the code of a callback that belongs to the given login. */
    fun callback(raw:String,pending:PendingSpotify,now:Long):String=when(val outcome=decide(parse(raw),pending,now)) {
        is Outcome.Granted->outcome.code
        is Outcome.Denied->throw SourceFailure(outcome.message)
    }
}
