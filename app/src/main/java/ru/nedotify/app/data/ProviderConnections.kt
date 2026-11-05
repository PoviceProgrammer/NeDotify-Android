package ru.nedotify.app.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONObject

data class ProviderConnectionStatus(val spotifyConfigured:Boolean=false,val spotifyConnected:Boolean=false,
    val yandexConnected:Boolean=false,val yandexName:String="",val busy:Boolean=false,val error:String?=null,
    val spotifyClientId:String="",val spotifyPending:Boolean=false,val yandexRevision:Long=0)

/**
 * The credentials of the two music services.
 *
 * Three rules hold the model together:
 *
 * * **The state is guarded, the network is not.** The mutex protects the
 *   in-memory credentials and is never held across an HTTP call: a slow token
 *   refresh cannot block a catalogue read, and a catalogue read cannot delay a
 *   refresh.
 * * **One flight at a time.** A refresh token is spent by exactly one request;
 *   the other requests wait for its result. The re-validation of a credential
 *   after a 401 is done once for all the requests that failed together. One
 *   callback is exchanged once, however often it is delivered.
 * * **A credential is only destroyed on proof.** A 401 from one endpoint is
 *   re-validated against the service before the credential is dropped, and a
 *   pending login is only consumed after its token has been stored. A record
 *   that cannot be read *now* is never removed: only a record that cannot be
 *   decrypted is damaged.
 */
class ProviderConnections(
    private val store:ProviderSecretStore,http:OkHttpClient,
    internal val endpoints:ProviderEndpoints=ProviderEndpoints(),private val now:()->Long=System::currentTimeMillis
) {
    constructor(context:Context,http:OkHttpClient):this(SecureProviderStore(context.applicationContext),http)
    private val lock=Mutex()
    /**
     * The application scope. The token exchange and the token refresh outlive
     * the Activity: a login that is interrupted by a rotation is not lost.
     */
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var initialized=false
    private var clientId=""
    private var token:ProviderToken?=null
    private var yandexToken:String?=null
    private var yandexUid=""
    private var pending:PendingLogins=PendingLogins.EMPTY
    private var spotifyGeneration=0L
    private var yandexGeneration=0L
    /** How many actions run at the same time: the panel is busy while any of them is. */
    private var busyCount=0
    private class Access(val value:String,val generation:Long) {override fun toString()="Access(<redacted>)"}
    private data class YandexAccount(val uid:String,val name:String)
    internal val network=ProviderHttp(http)
    /** One refresh at a time: a refresh token is never spent twice. */
    private val spotifyRefresh=SingleFlight<Access>()
    /** One re-validation at a time per credential. */
    private val yandexRevalidation=SingleFlight<YandexVerdict>()
    /** One exchange at a time per callback: a repeated delivery spends the code once. */
    private val spotifyExchange=SingleFlight<Unit>()
    private val _status=MutableStateFlow(ProviderConnectionStatus())
    val status=_status.asStateFlow()
    val spotify=SpotifyCatalog(this)
    val yandex=YandexCatalog(this)
    private companion object {const val YANDEX_CLIENT="YandexMusicAndroid/24023621"}

    /**
     * What the store holds for one key.
     *
     * The distinction is the whole point of the recovery: a record that cannot
     * be read *now* (the keystore cannot be reached) must be left alone, and
     * only a record that is provably damaged may be removed. A credential the
     * user had to obtain out of band is never destroyed because the phone had
     * a bad day.
     */
    private enum class RecordState {
        /** The record is there and was read. */
        VALUE,
        /** There is no record. */
        ABSENT,
        /** The record cannot be read right now: it must not be touched. */
        UNREADABLE,
        /** The record is intact but cannot be used: it must not be removed either. */
        UNUSABLE,
        /** The record is damaged: it has been removed. */
        CORRUPT
    }
    private class Recovery<T>(val value:T?,val state:RecordState)

    private suspend fun load() {
        if(initialized)return
        lock.withLock {
            if(initialized)return
            fun report(message:String){_status.value=_status.value.copy(error=message)}
            fun removeCorrupt(key:String) {
                try {store.remove(key)} catch(_:Exception){}
                report("Сохранённый вход повреждён. Подключите соответствующий сервис заново")
            }
            fun <T> recover(key:String,decode:(String)->T?):Recovery<T> {
                val raw=try {store.read(key)}
                catch(error:ProviderCredentialUnavailableException) {
                    report("Ключ входа недоступен. Подключите сервис заново");return Recovery(null,RecordState.UNREADABLE)
                }
                catch(error:ProviderCredentialCorruptException){removeCorrupt(key);return Recovery(null,RecordState.CORRUPT)}
                catch(_:Exception) {
                    report("Не удалось прочитать сохранённый вход. Отключите сервис и подключите заново")
                    return Recovery(null,RecordState.UNREADABLE)
                }
                if(raw==null)return Recovery(null,RecordState.ABSENT)
                return try {
                    val value=decode(raw)
                    if(value==null)Recovery(null,RecordState.UNUSABLE) else Recovery(value,RecordState.VALUE)
                }
                catch(error:ProviderCredentialUnavailableException){report("Ключ входа недоступен. Подключите сервис заново");Recovery(null,RecordState.UNREADABLE)}
                catch(_:Exception){removeCorrupt(key);Recovery(null,RecordState.CORRUPT)}
            }
            val config=recover("spotify_config",AuthPkce::validateClientId)
            clientId=config.value.orEmpty()
            val storedToken=recover("spotify_token",ProviderToken::stored)
            token=storedToken.value
            val storedPending=recover("spotify_pending",PendingLogins::stored)
            var pendingList=storedPending.value?:PendingLogins.EMPTY
            if(storedPending.state==RecordState.VALUE) {
                // A login that is older than the login window is over, and a login of
                // another Spotify application cannot be finished.
                val kept=pendingList.forClient(clientId).fresh(now())
                if(kept.items.size!=pendingList.items.size) {
                    pendingList=kept
                    if(kept.isEmpty())store.remove("spotify_pending") else store.write("spotify_pending",kept.json())
                }
            }
            pending=pendingList
            if(clientId.isEmpty()&&config.state!=RecordState.UNREADABLE) {
                // Without an application there is no token and no login that could
                // belong to it.
                store.remove("spotify_token");store.remove("spotify_pending")
                token=null;pending=PendingLogins.EMPTY
            }
            val yandex=recover("yandex_token") {raw->
                if(raw.startsWith('{'))YandexCredential.stored(raw)
                else {
                    // The old format: the token alone, the account id in its own record.
                    val uid=try {YandexCredential.userId(store.read("yandex_user").orEmpty())}
                    catch(error:ProviderCredentialUnavailableException){throw error}
                    catch(_:Exception){null}
                    if(uid==null)null else YandexCredential(AuthPkce.secret(raw),uid,"")
                }
            }
            val credential=yandex.value
            yandexToken=credential?.access;yandexUid=credential?.uid.orEmpty()
            _status.value=_status.value.copy(yandexName=credential?.name.orEmpty())
            if(yandex.state==RecordState.ABSENT||yandex.state==RecordState.CORRUPT)store.remove("yandex_user")
            initialized=true
            publish()
        }
    }
    private fun publish() {
        _status.value=_status.value.copy(spotifyConfigured=clientId.isNotEmpty(),spotifyConnected=token!=null,
            spotifyClientId=clientId,spotifyPending=pending.isNotEmpty(),yandexConnected=yandexToken!=null,yandexRevision=yandexGeneration,
            busy=busyCount>0)
    }
    /**
     * One action at a time.
     *
     * The panel is busy while **any** action runs: two overlapping actions must
     * not clear each other's flag, and the message of a failed action must not
     * be cleared by an action that started later.
     */
    private suspend fun beginAction()=lock.withLock {
        busyCount++
        if(busyCount==1)_status.value=_status.value.copy(error=null)
        publish()
    }
    private suspend fun endAction()=lock.withLock {busyCount--;publish()}
    private suspend fun publishError(message:String)=withContext(NonCancellable){lock.withLock {_status.value=_status.value.copy(error=message)}}
    private suspend fun <T> action(block:suspend ()->T):T=withContext(Dispatchers.IO) {
        beginAction()
        try {load();block()}
        catch(error:CancellationException){throw error}
        catch(error:Exception){
            val safe=if(error is SourceFailure)error else SourceFailure("Не удалось завершить подключение. Повторите действие")
            publishError(safe.message.orEmpty())
            throw safe
        }
        finally {
            withContext(NonCancellable){endAction()}
        }
    }
    suspend fun initialize()=action {Unit}
    suspend fun configureSpotify(value:String)=action {
        val id=AuthPkce.validateClientId(value)
        lock.withLock {
            // The id has one spelling: the same application in another case is
            // the same application and must not destroy the token.
            if(id!=clientId) {
                store.remove("spotify_token");store.remove("spotify_pending")
                token=null;pending=PendingLogins.EMPTY;spotifyGeneration++
                store.write("spotify_config",id);clientId=id
            }
        }
    }
    /**
     * Starts a login.
     *
     * The pending logins are kept: a second tab must not destroy the first
     * attempt, so every start adds its own login and the callback is matched
     * against the login whose state it carries.
     */
    suspend fun beginSpotifyLogin():String=action {
        val id=lock.withLock {clientId}
        if(id.isBlank())throw SourceFailure("Сначала укажите Spotify Client ID")
        val next=AuthPkce.pending(id,now())
        lock.withLock {
            val updated=pending.forClient(id).fresh(now()).add(next)
            store.write("spotify_pending",updated.json());pending=updated
        }
        endpoints.spotifyAuthorize.newBuilder().addQueryParameter("client_id",id)
            .addQueryParameter("response_type","code").addQueryParameter("redirect_uri",AuthPkce.REDIRECT)
            .addQueryParameter("scope",AuthPkce.SCOPE).addQueryParameter("state",next.state)
            .addQueryParameter("code_challenge_method","S256").addQueryParameter("code_challenge",AuthPkce.challenge(next.verifier)).build().toString()
    }
    /**
     * Finishes the login.
     *
     * The exchange runs in the application scope: an Activity that goes away
     * while the token is fetched does not lose the login. The pending login is
     * removed only after the token has been stored, so a failed exchange leaves
     * the login pending and the user can start over.
     */
    suspend fun handleSpotifyRedirect(raw:String)=action {exchange(raw)}
    /**
     * The durable entry point: the exchange continues when the caller is gone.
     *
     * Its failure is published: a job whose exception nobody observes would
     * leave the user without a word about a login that did not finish.
     */
    fun exchangeSpotifyRedirect(raw:String):Job=scope.async(start=CoroutineStart.LAZY) {
        try {exchange(raw)}
        catch(error:CancellationException){throw error}
        catch(error:Exception){
            val safe=if(error is SourceFailure)error else SourceFailure("Не удалось завершить подключение. Повторите действие")
            publishError(safe.message.orEmpty())
            throw safe
        }
    }.also {it.start()}
    private suspend fun exchange(raw:String) {
        val callback=AuthPkce.parse(raw)
        // One exchange per callback: a repeated delivery of the same intent must
        // not spend the code a second time.
        spotifyExchange.run(SecretKeyRef(callback.state),scope) {exchangeRedirect(callback)}
    }
    private suspend fun exchangeRedirect(callback:AuthPkce.Callback) {
        // The pending logins live in the store: a durable entry point must load
        // them before it can match the callback.
        load()
        val awaiting=lock.withLock {
            pending.match(callback.state) ?: throw SourceFailure("Проверка безопасности входа не пройдена. Начните вход заново")
        }
        val outcome=try {AuthPkce.decide(callback,awaiting,now())}
        catch(error:SourceFailure) {
            // A login that is out of its window is over: it must not pile up.
            if(awaiting.expired(now()))lock.withLock {dropPending(awaiting.state)}
            throw error
        }
        when(outcome) {
            is AuthPkce.Outcome.Denied -> {
                // The login is over: the pending login is removed.
                lock.withLock {dropPending(awaiting.state)}
                throw SourceFailure(outcome.message)
            }
            is AuthPkce.Outcome.Granted -> exchangeToken(awaiting,outcome.code)
        }
    }
    private suspend fun exchangeToken(awaiting:PendingSpotify,code:String) {
        val client=lock.withLock {clientId}
        val body=FormBody.Builder().add("client_id",client).add("grant_type","authorization_code")
            .add("code",code).add("redirect_uri",AuthPkce.REDIRECT).add("code_verifier",awaiting.verifier).build()
        val received=try {ProviderToken.response(ProviderPayload.json(network,Request.Builder().url(endpoints.spotifyToken).post(body).build()),now())}
        catch(error:SourceFailure){throw providerFailure("spotify",error)}
        lock.withLock {
            // The pending login must still be there: a logout or a new login wins.
            if(pending.match(awaiting.state)==null)throw changed()
            store.write("spotify_token",received.json());token=received;spotifyGeneration++
            // The login is consumed only now: the token has been stored.
            dropPending(awaiting.state);publish()
        }
    }
    /** Cancels every pending login. A new one may be started at any time. */
    suspend fun cancelSpotifyLogin() {
        load()
        lock.withLock {store.remove("spotify_pending");pending=PendingLogins.EMPTY}
    }
    suspend fun saveYandexToken(value:String)=action {
        val candidate=AuthPkce.userSecret(value.trim())
        val account=try {yandexAccount(candidate)} catch(error:SourceFailure){throw providerFailure("yandex",error,YandexCredentialAction.DROP)}
        lock.withLock {
            store.write("yandex_token",YandexCredential(candidate,account.uid,account.name).json());store.remove("yandex_user")
            yandexToken=candidate;yandexUid=account.uid;yandexGeneration++
            _status.value=_status.value.copy(yandexName=account.name)
        }
    }
    suspend fun logout(provider:String)=action {
        when(provider) {
            "spotify"->lock.withLock {store.remove("spotify_token");store.remove("spotify_pending");token=null;pending=PendingLogins.EMPTY;spotifyGeneration++}
            "yandex"->lock.withLock {store.remove("yandex_token");store.remove("yandex_user");yandexToken=null;yandexUid="";yandexGeneration++;_status.value=_status.value.copy(yandexName="")}
            // The started login is abandoned. The account keeps its own state.
            "spotify-login"->cancelSpotifyLogin()
            else->throw SourceFailure("Неизвестное подключение")
        }
    }
    private suspend fun spotifyAccess(rejected:String?=null,expectedGeneration:Long?=null):Access {
        load()
        val (current,generation)=lock.withLock {
            if(expectedGeneration!=null && expectedGeneration!=spotifyGeneration)throw changed()
            val stored=token?:throw SourceFailure("Подключите Spotify в настройках")
            stored to spotifyGeneration
        }
        if(current.fresh(now()) && current.access!=rejected)return Access(current.access,generation)
        // One refresh at a time: the refresh token is spent exactly once.
        return spotifyRefresh.run(SecretKeyRef(current.refresh),scope) {refreshSpotify(current,generation)}
    }
    private suspend fun refreshSpotify(current:ProviderToken,generation:Long):Access {
        // Another caller may have refreshed while this one waited for the flight.
        val live=lock.withLock {token to spotifyGeneration}
        val already=live.first
        if(already!=null && already.access!=current.access && already.fresh(now()))return Access(already.access,live.second)
        if(current.refresh.isBlank()) {
            dropSpotify(current.access,generation)
            throw SourceFailure("Срок входа Spotify истёк. Подключите заново")
        }
        val client=lock.withLock {clientId}
        val body=FormBody.Builder().add("client_id",client).add("grant_type","refresh_token").add("refresh_token",current.refresh).build()
        val response=try {ProviderPayload.json(network,Request.Builder().url(endpoints.spotifyToken).post(body).build())}
        catch(error:SourceFailure){
            // A rejected refresh token means the login is over: `invalid_grant`
            // (400) is the normal answer of Spotify for an expired refresh token,
            // and the message must name the credential.
            val lost=error.httpCode==400||error.httpCode==401
            if(lost)dropSpotify(current.access,generation)
            throw providerFailure("spotify",error,lost=lost)
        }
        val refreshed=ProviderToken.response(response,now(),current.refresh)
        return lock.withLock {
            // A logout or a new login during the refresh wins: the old token must not come back.
            if(token?.access!=current.access)throw changed()
            store.write("spotify_token",refreshed.json());token=refreshed;publish()
            Access(refreshed.access,spotifyGeneration)
        }
    }
    private suspend fun dropSpotify(access:String,generation:Long) {
        lock.withLock {
            if(spotifyGeneration==generation && token?.access==access) {
                store.remove("spotify_token");token=null;spotifyGeneration++;publish()
            }
        }
    }
    private suspend fun yandexAccess(expectedGeneration:Long?=null):Access {
        load()
        return lock.withLock {
            if(expectedGeneration!=null && expectedGeneration!=yandexGeneration)throw changed()
            Access(yandexToken?:throw SourceFailure("Подключите Яндекс Музыку в настройках"),yandexGeneration)
        }
    }
    internal suspend fun userId():String=withContext(Dispatchers.IO){load();lock.withLock {
        if(yandexToken==null || yandexUid.isEmpty())throw SourceFailure("Подключите Яндекс Музыку в настройках")
        yandexUid
    }}
    internal suspend fun catalogScope(provider:String):Long=withContext(Dispatchers.IO) {load();lock.withLock {
        when(provider) {
            "spotify"->{if(token==null)throw SourceFailure("Подключите Spotify в настройках");spotifyGeneration}
            "yandex"->{if(yandexToken==null)throw SourceFailure("Подключите Яндекс Музыку в настройках");yandexGeneration}
            else->throw SourceFailure("Неизвестное подключение")
        }
    }}
    /** Nonsecret scope for account-bound playback caches. A refresh does not change identity. */
    internal suspend fun yandexScope():String=withContext(Dispatchers.IO){load();lock.withLock {
        if(yandexToken==null || yandexUid.isEmpty())throw SourceFailure("Подключите Яндекс Музыку в настройках")
        "yandex:$yandexUid:$yandexGeneration"
    }}
    private fun changed()=SourceFailure("Подключение к сервису изменилось. Повторите действие")
    private suspend fun checkAccess(provider:String,access:Access)=withContext(Dispatchers.IO) {lock.withLock {
        if((provider=="spotify" && (access.generation!=spotifyGeneration || token==null)) ||
            (provider=="yandex" && (access.generation!=yandexGeneration || yandexToken==null)))throw changed()
    }}
    private fun apiUrl(base:HttpUrl,path:String):HttpUrl {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && ".." !in path && '?' !in path && '#' !in path)
        val url=base.resolve(path)?:throw SourceFailure("Неверный запрос сервиса")
        require(url.scheme==base.scheme && url.host==base.host && url.port==base.port && url.encodedPath.startsWith(base.encodedPath))
        return url
    }
    internal suspend fun get(provider:String,path:String,params:Map<String,String> = emptyMap(),expectedGeneration:Long?=null):JSONObject {
        val base=when(provider) {
            "spotify"->endpoints.spotifyApi
            "yandex"->endpoints.yandexApi
            // An unknown name is not a Yandex request: it must not carry a token.
            else->throw SourceFailure("Неизвестное подключение")
        }
        val builder=try {apiUrl(base,path).newBuilder()} catch(error:IllegalArgumentException){throw SourceFailure("Недопустимый запрос к сервису")}
        val access=if(provider=="spotify")spotifyAccess(expectedGeneration=expectedGeneration) else yandexAccess(expectedGeneration)
        params.forEach {(name,value)->builder.addQueryParameter(name,value)}
        val request=Request.Builder().url(builder.build()).header("Authorization",if(provider=="spotify")"Bearer ${access.value}" else "OAuth ${access.value}")
        if(provider=="yandex")request.header("X-Yandex-Music-Client",YANDEX_CLIENT)
        val limit=providerPayloadLimit(provider,path)
        try {
            val result=ProviderPayload.json(network,request.build(),limit)
            checkAccess(provider,access);return result
        } catch(error:SourceFailure) {
            if(error.httpCode==401) {
                if(provider=="spotify")return retrySpotify(request,access,limit)
                throw providerFailure(provider,error,handleYandexUnauthorized(access))
            }
            throw providerFailure(provider,error)
        }
    }
    /** A rejected Spotify token is refreshed once and the request is repeated. */
    private suspend fun retrySpotify(request:Request.Builder,access:Access,limit:Int):JSONObject {
        val refreshed=spotifyAccess(access.value,access.generation)
        try {
            val result=ProviderPayload.json(network,request.header("Authorization","Bearer ${refreshed.value}").build(),limit)
            checkAccess("spotify",refreshed);return result
        } catch(retry:SourceFailure) {
            if(retry.httpCode==401)dropSpotify(refreshed.value,refreshed.generation)
            throw providerFailure("spotify",retry)
        }
    }
    /**
     * A rejected Yandex token is re-validated before the credential is dropped.
     *
     * The re-validation is the same call the login makes (`account/status`), and
     * it runs *before* the credential is removed: a validation that needs the
     * credential cannot run after it is gone. The credential is dropped only
     * when the re-validation is rejected as well.
     */
    private suspend fun handleYandexUnauthorized(access:Access):YandexCredentialAction {
        val verdict=revalidateYandex(access.value)
        return lock.withLock {
            val action=yandexCredentialAction(verdict,yandexGeneration==access.generation,yandexToken==access.value)
            if(action==YandexCredentialAction.DROP) {
                store.remove("yandex_token");store.remove("yandex_user")
                yandexToken=null;yandexUid="";yandexGeneration++
                _status.value=_status.value.copy(yandexName="")
            }
            publish()
            action
        }
    }
    /**
     * What the re-validation says about the credential.
     *
     * Only a 401 is a proof that the credential died. A 403 means that the
     * service recognised the credential and refused the request anyway (an
     * entitlement, a filter) - the credential works elsewhere and must survive.
     */
    private suspend fun revalidateYandex(token:String):YandexVerdict=yandexRevalidation.run(SecretKeyRef(token),scope) {
        try {yandexAccount(token);YandexVerdict.ALIVE}
        catch(error:SourceFailure){when(error.httpCode) {
            401->YandexVerdict.DEAD
            403->YandexVerdict.RESTRICTED
            else->YandexVerdict.UNKNOWN
        }}
        catch(error:Exception){YandexVerdict.UNKNOWN}
    }
    private suspend fun yandexAccount(token:String):YandexAccount {
        val url=endpoints.yandexApi.resolve("account/status")?:throw SourceFailure("Неверный адрес сервиса")
        val response=ProviderPayload.json(network,Request.Builder().url(url)
            .header("Authorization","OAuth $token").header("X-Yandex-Music-Client",YANDEX_CLIENT).build())
        val result=response.optJSONObject("result")?:throw SourceFailure("Яндекс Музыка не подтвердила вход")
        val account=result.optJSONObject("account")?:throw SourceFailure("Яндекс Музыка не вернула аккаунт")
        // `optText` and not `optString`: on AOSP a JSON null becomes the text
        // "null", which is not an account id.
        val uid=account.optText("uid").takeIf {it.matches(Regex("[0-9]{1,24}"))}?:throw SourceFailure("Яндекс Музыка не вернула идентификатор аккаунта")
        val name=account.optText("displayName").ifBlank {account.optText("login")}.take(100)
        return YandexAccount(uid,name)
    }
    internal suspend fun postYandex(path:String,params:Map<String,String>,expectedGeneration:Long?=null):JSONObject {
        val url=try {apiUrl(endpoints.yandexApi,path)} catch(error:IllegalArgumentException){throw SourceFailure("Недопустимый запрос к сервису")}
        val access=yandexAccess(expectedGeneration)
        val body=FormBody.Builder().apply {params.forEach {(name,value)->add(name,value)}}.build()
        try {
            val result=ProviderPayload.json(network,Request.Builder().url(url).header("Authorization","OAuth ${access.value}")
                .header("X-Yandex-Music-Client",YANDEX_CLIENT).post(body).build(),providerPayloadLimit("yandex",path))
            checkAccess("yandex",access);return result
        } catch(error:SourceFailure) {
            if(error.httpCode==401)throw providerFailure("yandex",error,handleYandexUnauthorized(access))
            throw providerFailure("yandex",error)
        }
    }
    /** The failure of a provider call, with the service it belongs to named. */
    private fun providerFailure(provider:String,error:SourceFailure,credential:YandexCredentialAction?=null,lost:Boolean=false):SourceFailure {
        val message=providerAuthMessage(provider,error.httpCode,credential,lost)?:return error
        return SourceFailure(message,error.httpCode)
    }
    private fun dropPending(state:String) {
        val updated=pending.without(state)
        if(updated.isEmpty())store.remove("spotify_pending") else store.write("spotify_pending",updated.json())
        pending=updated
    }
}
