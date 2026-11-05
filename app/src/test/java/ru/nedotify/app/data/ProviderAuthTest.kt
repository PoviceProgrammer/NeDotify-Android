package ru.nedotify.app.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderAuthTest {
    @Test fun `a Yandex credential is dropped only when the re-validation is rejected as well`() {
        assertEquals(YandexCredentialAction.DROP,yandexCredentialAction(YandexVerdict.DEAD,true,true))
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.ALIVE,true,true))
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.UNKNOWN,true,true))
    }
    @Test fun `a restricted re-validation never drops the credential`() {
        // A 403 means that the service recognised the credential: it is not dead.
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.RESTRICTED,true,true))
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.RESTRICTED,false,true))
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.RESTRICTED,true,false))
    }
    @Test fun `a stale 401 never touches the credential that replaced it`() {
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.DEAD,false,true))
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.DEAD,true,false))
        assertEquals(YandexCredentialAction.KEEP,yandexCredentialAction(YandexVerdict.DEAD,false,false))
    }
    @Test fun `the failure names the service whose credential was rejected`() {
        val yandex=providerAuthMessage("yandex",401);val spotify=providerAuthMessage("spotify",401)
        assertNotNull(yandex);assertNotNull(spotify)
        assertNotEquals("The two services must not share one message",yandex,spotify)
        assertTrue("The Yandex message must name Yandex",yandex!!.contains("Яндекс"))
        assertTrue("The Spotify message must name Spotify",spotify!!.contains("Spotify"))
        assertNull(providerAuthMessage("yandex",403));assertNull(providerAuthMessage("yandex",null))
        assertNull(providerAuthMessage("unknown",401))
        // A dead credential and a refused request are told apart.
        assertTrue(providerAuthMessage("yandex",401,YandexCredentialAction.DROP)!!.contains("Токен Яндекс Музыки"))
        assertNotEquals(providerAuthMessage("yandex",401,YandexCredentialAction.DROP),providerAuthMessage("yandex",401,YandexCredentialAction.KEEP))
    }
    @Test fun `a lost credential is named even when the service answered with another code`() {
        // Spotify answers an expired refresh token with `400 invalid_grant`.
        val spotify=providerAuthMessage("spotify",400,null,true)
        assertNotNull("The lost credential must be named",spotify)
        assertTrue("The message must name the service: $spotify",spotify!!.contains("Spotify"))
        assertTrue("The message must say that the login is over: $spotify",spotify.contains("Подключите Spotify заново"))
        val yandex=providerAuthMessage("yandex",400,null,true)
        assertNotNull(yandex);assertTrue(yandex!!.contains("Яндекс"))
        // Without the loss a 400 keeps the generic text.
        assertNull(providerAuthMessage("spotify",400))
    }
    @Test fun `the account growing endpoints have their own size limit`() {
        assertEquals(ProviderPayload.ACCOUNT_MAX_BYTES,providerPayloadLimit("yandex","users/1/likes/tracks"))
        assertEquals(ProviderPayload.ACCOUNT_MAX_BYTES,providerPayloadLimit("yandex","users/1/playlists/2"))
        assertEquals(ProviderPayload.DEFAULT_MAX_BYTES,providerPayloadLimit("yandex","tracks/1/download-info"))
        assertEquals(ProviderPayload.DEFAULT_MAX_BYTES,providerPayloadLimit("yandex","search"))
        assertEquals(ProviderPayload.DEFAULT_MAX_BYTES,providerPayloadLimit("spotify","me/playlists"))
        assertTrue(ProviderPayload.ACCOUNT_MAX_BYTES>ProviderPayload.DEFAULT_MAX_BYTES)
    }
    @Test fun `the payload reader keeps the bounds of the provider path`() {
        listOf("{\"nested\":"+"[".repeat(40)+"0"+"]".repeat(40)+"}","{} private-secret","[]","{\"a\":1} trailing").forEach {body->
            try {ProviderPayload.parse(body);fail("The body was accepted: $body")}catch(error:SourceFailure){
                assertFalse(error.message!!.contains("private-secret"))
            }
        }
        assertTrue(ProviderPayload.parse("""{"text":"quote \" and braces {}[]","ok":true}""").getBoolean("ok"))
    }
    @Test fun `one flight at a time shares the result of the first call`()=runBlocking {
        val flight=SingleFlight<Int>();val calls=AtomicInteger();val gate=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val first=async{flight.run("key",scope){calls.incrementAndGet();gate.await();42}}
        while(calls.get()==0)delay(5)
        val second=async{flight.run("key",scope){calls.incrementAndGet();7}}
        delay(100)
        assertEquals("A second flight must not start for the same key",1,calls.get())
        gate.complete(Unit)
        assertEquals(42,first.await());assertEquals(42,second.await())
        // A different key is a different flight.
        assertEquals(7,flight.run("other",scope){calls.incrementAndGet();7})
        assertEquals(2,calls.get())
        scope.cancel()
    }
    @Test fun `a cancelled waiter does not cancel the flight`()=runBlocking {
        val flight=SingleFlight<Int>();val gate=CompletableDeferred<Unit>();val done=CompletableDeferred<Boolean>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val waiter=async{flight.run("key",scope){gate.await();done.complete(true);5}}
        delay(50)
        waiter.cancelAndJoin()
        gate.complete(Unit)
        assertTrue("The flight must survive the cancelled waiter",withTimeout(3000){done.await()})
        scope.cancel()
    }
    @Test fun `the key of a flight never carries the secret into a diagnostic string`() {
        val key=SecretKeyRef("refresh-secret-value")
        assertFalse("The secret must not be printed",key.toString().contains("refresh-secret-value"))
        assertEquals(SecretKeyRef("refresh-secret-value"),key)
        assertNotEquals(SecretKeyRef("another-secret-value"),key)
    }
}
