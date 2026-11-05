package ru.nedotify.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * A secret used as a key.
 *
 * The single flight is keyed by the credential it protects. A plain string
 * would carry the secret into every diagnostic string of the flight, so the
 * key redacts itself - like every other carrier of a secret in this area.
 */
internal class SecretKeyRef(val value:String) {
    override fun toString()="SecretKeyRef(<redacted>)"
    override fun equals(other:Any?)=other is SecretKeyRef&&other.value==value
    override fun hashCode()=value.hashCode()
}

/**
 * One flight at a time.
 *
 * A refresh token may be used by exactly one request at a time: two concurrent
 * refreshes with the same token would spend it twice, and the second one would
 * fail for a reason that has nothing to do with the user's account. The waiters
 * therefore share the result of the first flight instead of starting their own.
 *
 * The flight runs in the scope it is given, not in the caller's coroutine: a
 * cancelled waiter does not cancel the flight, so a refresh that is already
 * running still completes and still stores its token.
 */
internal class SingleFlight<T> {
    private class Flight<T>(val key:Any,val marker:Any,val job:Deferred<T>)
    private val monitor=Any()
    private var flight:Flight<T>?=null

    /**
     * Runs [block] for [key] and returns its result. A running flight with the
     * same key is joined instead of a second one being started.
     */
    suspend fun run(key:Any,scope:CoroutineScope,block:suspend ()->T):T {
        val deferred=synchronized(monitor) {
            val current=flight
            if(current!=null && current.key==key && current.job.isActive) current.job
            else {
                val marker=Any()
                val job=scope.async(start=CoroutineStart.LAZY) {
                    try { block() }
                    finally { synchronized(monitor) { if(flight?.marker===marker) flight=null } }
                }
                flight=Flight(key,marker,job)
                job.start()
                job
            }
        }
        return deferred.await()
    }
}

/** What a re-validation of a Yandex credential says about it. */
enum class YandexVerdict {
    /** The service still accepts the credential. */
    ALIVE,
    /** The service rejects the credential: it is dead. */
    DEAD,
    /**
     * The service recognised the credential and refused the request anyway.
     *
     * A 403 is an entitlement or a filter, not a proof that the credential died:
     * the credential is kept.
     */
    RESTRICTED,
    /** The re-validation itself failed: nothing is known about the credential. */
    UNKNOWN
}

/** What happens to the stored credential after a Yandex 401. */
enum class YandexCredentialAction { KEEP, DROP }

/**
 * The decision after a Yandex 401.
 *
 * A 401 from one endpoint is not a proof that the credential died: the request
 * may have been refused for another reason. The credential is therefore dropped
 * only when the re-validation against `account/status` is rejected as well.
 *
 * An inconclusive re-validation never destroys a secret the user has to obtain
 * out of band, and a stale 401 - one that belongs to a token the user has
 * already replaced - never touches the credential that replaced it.
 */
internal fun yandexCredentialAction(verdict:YandexVerdict,generationMatches:Boolean,tokenMatches:Boolean):YandexCredentialAction=
    if(verdict==YandexVerdict.DEAD && generationMatches && tokenMatches) YandexCredentialAction.DROP else YandexCredentialAction.KEEP

/**
 * The message for a rejected credential.
 *
 * The generic text ("the login has expired") does not say *which* login
 * expired, and the user is looking at two of them. `null` means that the
 * failure is not an authorization failure and the original text must be kept.
 *
 * The Yandex message depends on what the re-validation found: a credential
 * that is still alive only means that this request was refused, a credential
 * that is dead has to be replaced.
 *
 * [lost] says that the credential itself was given up: the message must name
 * it even when the service answered with a code that is not a 401 - Spotify
 * answers an expired refresh token with `400 invalid_grant`.
 */
internal fun providerAuthMessage(provider:String,httpCode:Int?,credential:YandexCredentialAction?=null,lost:Boolean=false):String?=when {
    lost->when(provider) {
        "yandex"->"Токен Яндекс Музыки отклонён. Подключите Яндекс Музыку заново"
        "spotify"->"Вход Spotify истёк. Подключите Spotify заново"
        else->null
    }
    httpCode!=401->null
    provider=="yandex"->if(credential==YandexCredentialAction.DROP)"Токен Яндекс Музыки отклонён. Подключите Яндекс Музыку заново"
        else "Яндекс Музыка отклонила запрос. Повторите попытку или подключите сервис заново"
    provider=="spotify"->"Вход Spotify истёк. Подключите Spotify заново"
    else->null
}

/**
 * The size limit of a provider document.
 *
 * The endpoints under `users/` carry the account's own data - the whole
 * library, one playlist - and grow with it. Every other document is small and
 * keeps the tight limit.
 */
internal fun providerPayloadLimit(provider:String,path:String):Int=
    if(provider=="yandex" && path.startsWith("users/")) ProviderPayload.ACCOUNT_MAX_BYTES else ProviderPayload.DEFAULT_MAX_BYTES
