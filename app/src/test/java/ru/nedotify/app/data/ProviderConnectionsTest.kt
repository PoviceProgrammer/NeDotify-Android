package ru.nedotify.app.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class ProviderConnectionsTest {
    private class MemoryStore:ProviderSecretStore {
        val values=mutableMapOf<String,String>()
        /** Every record that was removed: a removal must be justified. */
        val removed=mutableListOf<String>()
        /** When it is set, the store behaves like an unreachable keystore. */
        var unavailable=false
        override fun read(key:String):String?=if(unavailable)throw ProviderCredentialUnavailableException("Ключ входа недоступен. Подключите сервис заново") else values[key]
        override fun write(key:String,value:String){values[key]=value}
        override fun remove(key:String){removed+=key;values.remove(key)}
    }
    private val clientId="a".repeat(32)
    private fun endpoints(server:MockWebServer)=ProviderEndpoints(server.url("/spotify/"),server.url("/token"),server.url("/authorize"),server.url("/yandex/"))
    private fun connection(server:MockWebServer,store:MemoryStore,now:Long=1000)=ProviderConnections(store,OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build(),endpoints(server)){now}
    private fun tokenJson(access:String="access-secret",refresh:String="refresh-secret")="""{"token_type":"Bearer","access_token":"$access","refresh_token":"$refresh","expires_in":3600}"""
    private fun loggedInStore(expires:Long=9_999_999)=MemoryStore().apply {
        write("spotify_config",clientId);write("spotify_token",ProviderToken("access-secret","refresh-secret",expires).json())
    }
    @Test fun `OAuth callback survives process recreation and is consumed only once`()=runBlocking {
        MockWebServer().use {server->
            server.start();val store=MemoryStore();val first=connection(server,store)
            first.configureSpotify(clientId);val url=first.beginSpotifyLogin().toHttpUrl()
            assertEquals("S256",url.queryParameter("code_challenge_method"));assertEquals(AuthPkce.REDIRECT,url.queryParameter("redirect_uri"))
            assertFalse(url.toString().contains("verifier"))
            val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
            assertEquals(AuthPkce.challenge(pending.verifier),url.queryParameter("code_challenge"))
            server.enqueue(MockResponse().setBody(tokenJson()))
            val recreated=connection(server,store)
            recreated.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code")
            assertTrue(recreated.status.value.spotifyConnected);assertNull(store.values["spotify_pending"])
            val exchange=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/token",exchange.path);assertNull(exchange.getHeader("Authorization"))
            val form=exchange.body.readUtf8();assertTrue(form.contains("code_verifier=${pending.verifier}"));assertFalse(form.contains("client_secret"))
            try {recreated.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code");fail()}catch(_:SourceFailure){}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `foreign state does not consume legitimate pending login`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore();val session=connection(server,store)
            session.configureSpotify(clientId);session.beginSpotifyLogin()
            try {session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=wrong-state&code=secret-code");fail()}catch(error:SourceFailure){assertFalse(error.message!!.contains("secret-code"))}
            assertNotNull(store.values["spotify_pending"]);assertEquals(0,server.requestCount)
            assertFalse(session.status.value.busy)
        }
    }
    @Test fun `concurrent expired requests share one refresh and keep refresh token`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore(0);val session=connection(server,store)
            server.enqueue(MockResponse().setBody("""{"access_token":"new-access","token_type":"Bearer","expires_in":3600}"""))
            repeat(6){server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))}
            (0 until 6).map {async(Dispatchers.IO){session.spotify.search("music")}}.awaitAll()
            assertEquals(7,server.requestCount)
            val request=server.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/token",request.path);assertTrue(request.body.readUtf8().contains("refresh_token=refresh-secret"))
            repeat(6){assertEquals("Bearer new-access",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))}
            assertEquals("refresh-secret",ProviderToken.stored(store.values.getValue("spotify_token")).refresh)
        }
    }
    @Test fun `a rejected access token is refreshed once before the request retries`()=runBlocking {
        MockWebServer().use {server->server.start();val session=connection(server,loggedInStore())
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-access-secret"))
            server.enqueue(MockResponse().setBody(tokenJson("new-access")))
            server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))
            assertTrue(session.spotify.search("music").isEmpty())
            assertEquals("Bearer access-secret",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertEquals("/token",server.takeRequest(2,TimeUnit.SECONDS)!!.path)
            assertEquals("Bearer new-access",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
        }
    }
    @Test fun `invalid refresh disconnects and server secrets never reach error state`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore(0);val session=connection(server,store)
            server.enqueue(MockResponse().setResponseCode(400).setBody("refresh-secret private-account"))
            try {session.spotify.search("music");fail()}catch(error:SourceFailure){assertFalse(error.message!!.contains("refresh-secret"))}
            assertNull(store.values["spotify_token"]);assertFalse(session.status.value.spotifyConnected)
        }
    }
    @Test fun `a rejected refresh token names the credential it lost`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore(0);val session=connection(server,store)
            // `invalid_grant` (400) is the normal answer of Spotify for an expired refresh token.
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            try {session.spotify.search("music");fail()}catch(error:SourceFailure){
                assertTrue("The message must name the credential: ${error.message}",error.message!!.contains("Spotify"))
                assertTrue("The message must say that the login is over: ${error.message}",error.message!!.contains("Подключите Spotify заново"))
            }
            assertNull("The dead credential must be dropped",store.values["spotify_token"])
            assertFalse(session.status.value.spotifyConnected)
        }
    }
    @Test fun `corrupt stored credentials allow reconfiguration instead of permanently blocking settings`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore().apply {write("spotify_config","broken");write("spotify_pending","bad JSON");write("yandex_user","evil/path")}
            val session=connection(server,store);session.initialize()
            assertFalse(session.status.value.spotifyConnected);assertFalse(session.status.value.yandexConnected)
            session.configureSpotify(clientId);assertTrue(session.status.value.spotifyConfigured)
        }
    }
    @Test fun `an unreachable keystore never destroys the stored credential`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())}
            val session=connection(server,store)
            // The keystore cannot be reached: the record may be perfectly good.
            store.unavailable=true
            session.initialize()
            assertTrue("The credential must survive an unreachable keystore",store.values.containsKey("yandex_token"))
            assertTrue("No record may be removed: ${store.removed}",store.removed.isEmpty())
            assertFalse(session.status.value.yandexConnected)
            assertTrue("The user must be told: ${session.status.value.error}",session.status.value.error!!.contains("Ключ входа недоступен"))
        }
    }
    @Test fun `a damaged record is removed from the store`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("spotify_config","broken");write("spotify_token","{not json")}
            val session=connection(server,store);session.initialize()
            assertTrue("The damaged record must be removed: ${store.removed}",store.removed.contains("spotify_config"))
            assertTrue(store.removed.contains("spotify_token"))
            assertNull(store.values["spotify_config"]);assertNull(store.values["spotify_token"])
        }
    }
    @Test fun `provider redirects never forward bearer and HTTP errors redact body`()=runBlocking {
        MockWebServer().use {server->MockWebServer().use {other->server.start();other.start()
            val session=connection(server,loggedInStore())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",other.url("/steal")))
            try {session.spotify.search("music");fail()}catch(error:SourceFailure){assertEquals(302,error.httpCode)}
            assertEquals(0,other.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).setBody("Bearer SECRET"))
            try {session.spotify.search("music");fail()}catch(error:SourceFailure){assertFalse(error.message!!.contains("SECRET"))}
        }}
    }
    @Test fun `Yandex validates account before saving and logout removes its own tokens`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore();val session=connection(server,store)
            server.enqueue(MockResponse().setBody("""{"result":{"account":{"uid":123,"displayName":"Слушатель"}}}"""))
            session.saveYandexToken("yandex-secret")
            assertTrue(session.status.value.yandexConnected);assertEquals("Слушатель",session.status.value.yandexName)
            assertEquals("OAuth yandex-secret",server.takeRequest(2,TimeUnit.SECONDS)!!.getHeader("Authorization"))
            session.logout("yandex");assertNull(store.values["yandex_token"]);assertNotNull(store.values["spotify_token"])
            session.logout("spotify");assertNull(store.values["spotify_token"]);assertEquals(clientId,store.values["spotify_config"])
        }
    }
    @Test fun `provider HTTP bounds streamed bodies even without content length`()=runBlocking {
        MockWebServer().use {server->server.start();server.enqueue(MockResponse().setChunkedBody("a".repeat(2048),100))
            try {ProviderHttp(OkHttpClient()).text(Request.Builder().url(server.url("/")).build(),1000);fail()}catch(error:SourceFailure){assertTrue(error.message!!.contains("большой"))}
        }
    }
    @Test fun `pagination requests use only original provider origin ignoring supplied next host`()=runBlocking {
        MockWebServer().use {server->server.start();val session=connection(server,loggedInStore())
            val playlist=JSONObject().put("id","1".repeat(22)).put("name","First")
            server.enqueue(MockResponse().setBody(JSONObject().put("items",org.json.JSONArray().put(playlist)).put("next","https://evil.test/steal").toString()))
            server.enqueue(MockResponse().setBody("""{"items":[],"next":null}"""))
            assertEquals("First",session.spotify.playlists().single().title)
            assertTrue(server.takeRequest(2,TimeUnit.SECONDS)!!.path!!.contains("offset=0"))
            assertTrue(server.takeRequest(2,TimeUnit.SECONDS)!!.path!!.contains("offset=1"))
        }
    }
    @Test fun `encoded traversal and backslash authority cannot receive provider authorization`()=runBlocking {
        MockWebServer().use {server->server.start();val session=connection(server,loggedInStore())
            listOf("\\\\evil.test/path","%2e%2e/steal","//evil.test/steal","search?redirect=evil","https://evil.test/steal").forEach {path->
                try {session.get("spotify",path);fail("Rejected path was sent")}catch(error:SourceFailure){
                    // The user gets a message instead of "Failed requirement.".
                    assertTrue("The rejection must be explained: ${error.message}",error.message!!.contains("Недопустимый запрос"))
                }
            }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun `an unknown provider is refused instead of being taken for Yandex`()=runBlocking {
        MockWebServer().use {server->server.start();val session=connection(server,loggedInStore())
            try {session.get("other-service","tracks/1");fail()}catch(error:SourceFailure){
                assertTrue("The refusal must be explained: ${error.message}",error.message!!.contains("Неизвестное подключение"))
            }
            assertEquals("No request may be sent",0,server.requestCount)
        }
    }
    @Test fun `the JSON reader rejects excessive nesting trailing payload and nonobject root`()=runBlocking {
        MockWebServer().use {server->server.start();val network=ProviderHttp(OkHttpClient())
            listOf("{\"nested\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}","{} private-secret","[]","{\"a\":1} trailing").forEach {body->
                server.enqueue(MockResponse().setBody(body))
                try {ProviderPayload.json(network,Request.Builder().url(server.url("/")).build());fail()}catch(error:SourceFailure){assertFalse(error.message!!.contains("private-secret"))}
            }
            server.enqueue(MockResponse().setBody("""{"text":"quote \" and braces {}[]","ok":true}"""))
            assertTrue(ProviderPayload.json(network,Request.Builder().url(server.url("/")).build()).getBoolean("ok"))
        }
    }
    @Test fun `expired credentials without refresh become disconnected rather than permanently connected`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore(0)
            store.write("spotify_token",ProviderToken("access-secret","",0).json())
            val session=connection(server,store)
            try {session.spotify.search("music");fail()}catch(_:SourceFailure){}
            assertFalse(session.status.value.spotifyConnected);assertNull(store.values["spotify_token"]);assertEquals(0,server.requestCount)
        }
    }
    @Test fun `second unauthorized response disconnects and does not loop refresh`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore();val session=connection(server,store)
            server.enqueue(MockResponse().setResponseCode(401));server.enqueue(MockResponse().setBody(tokenJson("new-access")));server.enqueue(MockResponse().setResponseCode(401))
            try {session.spotify.search("music");fail()}catch(_:SourceFailure){}
            assertEquals(3,server.requestCount);assertFalse(session.status.value.spotifyConnected);assertNull(store.values["spotify_token"])
        }
    }
    @Test fun `a cancelled token exchange releases the busy flag and preserves the previous account`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore();val session=connection(server,store)
            session.beginSpotifyLogin();val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val exchange=async(Dispatchers.IO){session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code")}
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));exchange.cancelAndJoin()
            assertFalse(session.status.value.busy)
            // The pending login survives a cancelled exchange: the user must be able to retry.
            assertTrue(session.status.value.spotifyPending)
            assertEquals("access-secret",ProviderToken.stored(store.values.getValue("spotify_token")).access)
            session.logout("spotify");assertFalse(session.status.value.spotifyConnected)
        }
    }
    @Test fun `oversized provider pagination page fails instead of returning an unbounded collection`()=runBlocking {
        MockWebServer().use {server->server.start();val session=connection(server,loggedInStore())
            val items=org.json.JSONArray();repeat(51){items.put(JSONObject().put("id",it.toString().padStart(22,'0')).put("name","playlist"))}
            server.enqueue(MockResponse().setBody(JSONObject().put("items",items).put("next",JSONObject.NULL).toString()))
            try {session.spotify.playlists();fail()}catch(error:SourceFailure){assertTrue(error.message!!.contains("большую"))}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `direct JSON request cancellation remains cancellation instead of parser failure`()=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val network=ProviderHttp(OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build())
            val pending=async(Dispatchers.IO){ProviderPayload.json(network,Request.Builder().url(server.url("/pending-json")).build())}
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
            pending.cancelAndJoin()
            val result=runCatching {pending.await()}.exceptionOrNull()
            assertTrue("Cancellation must reach the caller",result is CancellationException)
        }
    }
    @Test fun `response from a logged out Spotify account cannot populate the catalog`()=runBlocking {
        MockWebServer().use {server->
            val received=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    received.countDown();release.await(5,TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"tracks":{"items":[]}}""")
                }
            }
            server.start();val session=connection(server,loggedInStore())
            val old=async(Dispatchers.IO){runCatching {session.spotify.search("private library")}}
            try {
                assertTrue(received.await(2,TimeUnit.SECONDS));session.logout("spotify");release.countDown()
                assertTrue(old.await().exceptionOrNull() is SourceFailure)
                assertFalse(session.status.value.spotifyConnected);assertEquals(1,server.requestCount)
            } finally {release.countDown();old.cancelAndJoin()}
        }
    }
    @Test fun `old unauthorized request cannot retry with newly connected Spotify credentials`()=runBlocking {
        MockWebServer().use {server->
            val received=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    return when {
                        request.path=="/token"->MockResponse().setBody(tokenJson("new-account-access"))
                        else->{received.countDown();release.await(5,TimeUnit.SECONDS);MockResponse().setResponseCode(401)}
                    }
                }
            }
            server.start();val store=loggedInStore();val session=connection(server,store)
            val old=async(Dispatchers.IO){runCatching {session.spotify.search("old account query")}}
            try {
                assertTrue(received.await(2,TimeUnit.SECONDS));session.logout("spotify")
                session.beginSpotifyLogin();val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
                session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=new-account-code")
                release.countDown()
                assertTrue(old.await().exceptionOrNull() is SourceFailure)
                assertEquals(2,server.requestCount)
                assertEquals("new-account-access",ProviderToken.stored(store.values.getValue("spotify_token")).access)
                assertTrue(session.status.value.spotifyConnected)
            } finally {release.countDown();old.cancelAndJoin()}
        }
    }
    @Test fun `Yandex metadata POST cannot return a previous account after logout`()=runBlocking {
        MockWebServer().use {server->
            val received=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    received.countDown();release.await(5,TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"result":[]}""")
                }
            }
            server.start();val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Previous").json())}
            val session=connection(server,store);session.initialize()
            val old=async(Dispatchers.IO){runCatching {session.postYandex("tracks",mapOf("track-ids" to "1"))}}
            try {
                assertTrue(received.await(2,TimeUnit.SECONDS));session.logout("yandex");release.countDown()
                assertTrue(old.await().exceptionOrNull() is SourceFailure)
                assertFalse(session.status.value.yandexConnected);assertEquals("",session.status.value.yandexName)
            } finally {release.countDown();old.cancelAndJoin()}
        }
    }
    @Test fun `nonsecret playback scope rotates on account changes including same account reconnect`()=runBlocking {
        MockWebServer().use {server->
            server.start();val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Previous").json())}
            val session=connection(server,store);val oldScope=session.yandexScope();val revision=session.status.value.yandexRevision
            server.enqueue(MockResponse().setBody("""{"result":{"account":{"uid":123,"displayName":"Current"}}}"""))
            session.saveYandexToken("new-yandex-secret")
            val newScope=session.yandexScope()
            assertNotEquals(oldScope,newScope);assertTrue(session.status.value.yandexRevision>revision)
            assertFalse(newScope.contains("secret"))
            val catalogScope=session.catalogScope("yandex")
            session.logout("yandex")
            try {session.yandexScope();fail()}catch(_:SourceFailure){}
            try {session.get("yandex","tracks/1",expectedGeneration=catalogScope);fail()}catch(_:SourceFailure){}
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun `catalog pagination counts skipped nullable positions in its offset`()=runBlocking {
        MockWebServer().use {server->server.start();val session=connection(server,loggedInStore())
            val first=JSONObject().put("id","1".repeat(22)).put("name","First")
            val second=JSONObject().put("id","2".repeat(22)).put("name","Second")
            server.enqueue(MockResponse().setBody(JSONObject().put("items",org.json.JSONArray().put(JSONObject.NULL).put(first)).put("next","https://api.spotify.com/page2").toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("items",org.json.JSONArray().put(second)).put("next",JSONObject.NULL).toString()))
            assertEquals(listOf("First","Second"),session.spotify.playlists().map {it.title})
            assertTrue(server.takeRequest(2,TimeUnit.SECONDS)!!.path!!.contains("offset=0"))
            assertTrue(server.takeRequest(2,TimeUnit.SECONDS)!!.path!!.contains("offset=2"))
        }
    }
    private suspend fun awaitTrue(timeoutMs:Long=5000,check:()->Boolean) {
        val deadline=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<deadline){if(check())return;delay(20)}
        fail("The condition was not met")
    }
    @Test fun `a Yandex 401 whose revalidation succeeds keeps the credential`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())}
            val session=connection(server,store)
            val scope=session.yandexScope();val revision=session.status.value.yandexRevision
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"result":{"account":{"uid":123,"displayName":"Слушатель"}}}"""))
            try {session.get("yandex","tracks/1");fail()}catch(error:SourceFailure){assertEquals(401,error.httpCode)}
            assertNotNull("A single 401 must not destroy the credential",store.values["yandex_token"])
            assertTrue(session.status.value.yandexConnected)
            assertEquals("The scope must not rotate",scope,session.yandexScope())
            assertEquals(revision,session.status.value.yandexRevision)
            assertEquals("The credential must be re-validated",2,server.requestCount)
        }
    }
    @Test fun `a Yandex 401 whose revalidation fails drops the credential and rotates the scope`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())}
            val session=connection(server,store)
            val revision=session.status.value.yandexRevision
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            try {session.get("yandex","tracks/1");fail()}catch(error:SourceFailure){assertEquals(401,error.httpCode)}
            assertNull("The dead credential must be dropped",store.values["yandex_token"])
            assertNull(store.values["yandex_user"])
            assertFalse(session.status.value.yandexConnected)
            assertTrue("The scope must rotate",session.status.value.yandexRevision>revision)
            try {session.yandexScope();fail()}catch(_:SourceFailure){}
        }
    }
    @Test fun `an inconclusive revalidation keeps the credential`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())}
            val session=connection(server,store)
            val revision=session.status.value.yandexRevision
            server.enqueue(MockResponse().setResponseCode(401))
            // The service itself is broken: nothing is known about the credential.
            server.enqueue(MockResponse().setResponseCode(500))
            try {session.get("yandex","tracks/1");fail()}catch(error:SourceFailure){assertEquals(401,error.httpCode)}
            assertNotNull("An inconclusive re-validation must not destroy the credential",store.values["yandex_token"])
            assertTrue(session.status.value.yandexConnected)
            assertEquals("The scope must not rotate",revision,session.status.value.yandexRevision)
        }
    }
    @Test fun `a 403 from the revalidation does not destroy the credential`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())}
            val session=connection(server,store)
            val revision=session.status.value.yandexRevision
            server.enqueue(MockResponse().setResponseCode(401))
            // 403 means that the service recognised the credential and refused it.
            server.enqueue(MockResponse().setResponseCode(403))
            try {session.get("yandex","tracks/1");fail()}catch(error:SourceFailure){assertEquals(401,error.httpCode)}
            assertNotNull("A restricted request is not a dead credential",store.values["yandex_token"])
            assertTrue(session.status.value.yandexConnected)
            assertEquals("The scope must not rotate",revision,session.status.value.yandexRevision)
        }
    }
    @Test fun `a second login start keeps the first pending login`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore();val session=connection(server,store)
            session.configureSpotify(clientId)
            val first=session.beginSpotifyLogin().toHttpUrl().queryParameter("state")
            val second=session.beginSpotifyLogin().toHttpUrl().queryParameter("state")
            assertNotNull(first);assertNotEquals(first,second)
            assertEquals(2,PendingLogins.stored(store.values.getValue("spotify_pending")).items.size)
            // The tab of the first login can still finish it.
            server.enqueue(MockResponse().setBody(tokenJson()))
            session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=$first&code=authorization-code")
            assertTrue(session.status.value.spotifyConnected)
            // The second login is still pending.
            assertEquals(1,PendingLogins.stored(store.values.getValue("spotify_pending")).items.size)
        }
    }
    @Test fun `the same client id in another case keeps the token and the started login`()=runBlocking {
        MockWebServer().use {server->server.start();val store=loggedInStore();val session=connection(server,store)
            session.beginSpotifyLogin()
            assertNotNull(store.values["spotify_pending"])
            // The same application, typed in another case.
            session.configureSpotify(clientId.uppercase())
            assertNotNull("The token must survive",store.values["spotify_token"])
            assertNotNull("The started login must survive",store.values["spotify_pending"])
            assertTrue(session.status.value.spotifyConnected)
            assertTrue(session.status.value.spotifyPending)
            assertEquals(clientId,store.values["spotify_config"])
        }
    }
    @Test fun `a failed exchange keeps the pending login so the user can retry`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore();val session=connection(server,store)
            session.configureSpotify(clientId);session.beginSpotifyLogin()
            val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
            server.enqueue(MockResponse().setResponseCode(500))
            try {session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code");fail()}catch(_:SourceFailure){}
            assertNotNull("A failed exchange must not destroy the pending login",store.values["spotify_pending"])
            assertTrue(session.status.value.spotifyPending)
            assertNull(store.values["spotify_token"])
        }
    }
    @Test fun `the token exchange survives the caller that started it`()=runBlocking {
        MockWebServer().use {server->
            val received=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    if(request.path=="/token"){received.countDown();release.await(5,TimeUnit.SECONDS);return MockResponse().setBody(tokenJson("durable-access"))}
                    return MockResponse().setResponseCode(404)
                }
            }
            server.start();val store=MemoryStore();val session=connection(server,store)
            session.configureSpotify(clientId);session.beginSpotifyLogin()
            val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
            val exchange=async(Dispatchers.IO){runCatching {session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code")}}
            assertTrue(received.await(2,TimeUnit.SECONDS))
            // The Activity goes away while the token is fetched.
            exchange.cancelAndJoin()
            release.countDown()
            awaitTrue{store.values["spotify_token"]!=null}
            assertEquals("durable-access",ProviderToken.stored(store.values.getValue("spotify_token")).access)
            assertTrue(session.status.value.spotifyConnected)
        }
    }
    @Test fun `the durable entry point finds the pending login of a fresh instance`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore()
            val first=connection(server,store)
            first.configureSpotify(clientId);first.beginSpotifyLogin()
            val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
            // A new process: the instance knows nothing about the login.
            val recreated=connection(server,store)
            server.enqueue(MockResponse().setBody(tokenJson("durable-access")))
            recreated.exchangeSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code").join()
            assertEquals("durable-access",ProviderToken.stored(store.values.getValue("spotify_token")).access)
            assertTrue(recreated.status.value.spotifyConnected)
            assertNull(store.values["spotify_pending"])
        }
    }
    @Test fun `a failed durable exchange tells the user`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore()
            val first=connection(server,store)
            first.configureSpotify(clientId);first.beginSpotifyLogin()
            val pending=PendingLogins.stored(store.values.getValue("spotify_pending")).items.single()
            val recreated=connection(server,store)
            server.enqueue(MockResponse().setResponseCode(500))
            recreated.exchangeSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code").join()
            assertNotNull("The failure must be visible",recreated.status.value.error)
            assertTrue("The failure must be visible: ${recreated.status.value.error}",recreated.status.value.error!!.isNotBlank())
            assertNull("The token must not be stored",store.values["spotify_token"])
            assertNotNull("The login must stay pending",store.values["spotify_pending"])
        }
    }
    @Test fun `a durable exchange of a foreign callback tells the user`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore()
            val recreated=connection(server,store)
            recreated.exchangeSpotifyRedirect("${AuthPkce.REDIRECT}?state=state-of-nobody&code=authorization-code").join()
            assertTrue("The refusal must be visible: ${recreated.status.value.error}",recreated.status.value.error!!.contains("Проверка безопасности"))
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun `the user can cancel a started login`()=runBlocking {
        MockWebServer().use {server->server.start();val store=MemoryStore();val session=connection(server,store)
            session.configureSpotify(clientId);session.beginSpotifyLogin()
            assertTrue(session.status.value.spotifyPending)
            session.logout("spotify-login")
            assertFalse(session.status.value.spotifyPending)
            assertNull(store.values["spotify_pending"])
            assertEquals("The application settings must survive",clientId,store.values["spotify_config"])
        }
    }
    @Test fun `a JSON null in the account is not shown as the text null`()=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setBody("""{"result":{"account":{"uid":123,"displayName":null}}}"""))
            val session=connection(server,MemoryStore())
            session.saveYandexToken("yandex-secret")
            assertEquals("",session.status.value.yandexName)
            assertTrue(session.status.value.yandexConnected)
        }
    }
    @Test fun `a rejected credential names the service that rejected it`()=runBlocking {
        MockWebServer().use {server->server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            try {connection(server,MemoryStore()).saveYandexToken("wrong-token");fail()}catch(error:SourceFailure){
                assertTrue("The Yandex token must be named: ${error.message}",error.message!!.contains("Яндекс"))
                assertEquals(401,error.httpCode)
            }
            val session=connection(server,loggedInStore())
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(401))
            try {session.get("spotify","me/playlists");fail()}catch(error:SourceFailure){
                assertTrue("The Spotify login must be named: ${error.message}",error.message!!.contains("Spotify"))
                assertEquals(401,error.httpCode)
            }
        }
    }
    @Test fun `a slow Spotify refresh does not block a Yandex read`()=runBlocking {
        MockWebServer().use {server->
            val release=CountDownLatch(1);val refresh=CountDownLatch(1)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    if(request.path=="/token"){refresh.countDown();release.await(5,TimeUnit.SECONDS);return MockResponse().setBody(tokenJson("new-access"))}
                    return MockResponse().setBody("""{"result":{}}""")
                }
            }
            server.start()
            val store=loggedInStore(0)
            store.write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())
            val session=connection(server,store)
            val slow=async(Dispatchers.IO){runCatching {session.spotify.search("music")}}
            assertTrue("The refresh must have started",refresh.await(2,TimeUnit.SECONDS))
            // The Yandex read must not wait behind the refresh.
            val read=withTimeout(3000){session.get("yandex","tracks/1")}
            assertNotNull(read)
            release.countDown();assertTrue(slow.await().isSuccess)
        }
    }
    @Test fun `the account growing endpoint accepts a document the tight limit would reject`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("yandex_token",YandexCredential("yandex-secret","123","Слушатель").json())}
            val session=connection(server,store)
            val big=JSONObject().put("result",JSONObject().put("blob","x".repeat(3*1024*1024))).toString()
            server.enqueue(MockResponse().setBody(big))
            val result=session.get("yandex","users/1/likes/tracks")
            assertTrue(result.optJSONObject("result")!!.optString("blob").length>2_000_000)
            server.enqueue(MockResponse().setBody(big))
            try {session.get("yandex","tracks/1");fail()}catch(error:SourceFailure){assertTrue(error.message!!.contains("большой"))}
        }
    }
    @Test fun `a login that is older than the login window is dropped`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore();var clock=1000L
            val session=ProviderConnections(store,OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build(),endpoints(server)){clock}
            session.configureSpotify(clientId);session.beginSpotifyLogin()
            assertEquals(1,PendingLogins.stored(store.values.getValue("spotify_pending")).items.size)
            clock+=PendingLogins.MAX_AGE_MS+1
            session.beginSpotifyLogin()
            assertEquals("The expired login must be dropped",1,PendingLogins.stored(store.values.getValue("spotify_pending")).items.size)
        }
    }
    @Test fun `an expired pending login is dropped when the app starts`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {
                write("spotify_config",clientId)
                write("spotify_pending",AuthPkce.pending(clientId,1000).json())
            }
            val session=ProviderConnections(store,OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build(),endpoints(server)){1000+PendingLogins.MAX_AGE_MS+1}
            session.initialize()
            assertFalse("The expired login must not be pending",session.status.value.spotifyPending)
            assertNull("The expired login must be removed",store.values["spotify_pending"])
        }
    }
    @Test fun `the old single pending login format still finishes a login`()=runBlocking {
        MockWebServer().use {server->server.start()
            val store=MemoryStore().apply {write("spotify_config",clientId)}
            val session=connection(server,store)
            val pending=AuthPkce.pending(clientId,1000)
            // The format before the list: one object.
            store.write("spotify_pending",pending.json())
            server.enqueue(MockResponse().setBody(tokenJson()))
            session.handleSpotifyRedirect("${AuthPkce.REDIRECT}?state=${pending.state}&code=authorization-code")
            assertTrue(session.status.value.spotifyConnected)
            assertEquals("access-secret",ProviderToken.stored(store.values.getValue("spotify_token")).access)
            assertNull(store.values["spotify_pending"])
        }
    }
    @Test fun `overlapping actions keep the panel busy until the last one ends`()=runBlocking {
        MockWebServer().use {server->
            val received=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    received.countDown();release.await(5,TimeUnit.SECONDS)
                    return MockResponse().setBody("""{"result":{"account":{"uid":123,"displayName":"Слушатель"}}}""")
                }
            }
            server.start();val store=MemoryStore();val session=connection(server,store)
            val first=async(Dispatchers.IO){runCatching {session.saveYandexToken("yandex-secret")}}
            assertTrue(received.await(2,TimeUnit.SECONDS))
            assertTrue("The panel must be busy",session.status.value.busy)
            // A second action starts while the first one is still running.
            val second=async(Dispatchers.IO){runCatching {session.initialize()}}
            second.await()
            assertTrue("The second action must not clear the flag of the first one",session.status.value.busy)
            release.countDown();first.await()
            assertFalse("The panel must be free again",session.status.value.busy)
        }
    }
}
