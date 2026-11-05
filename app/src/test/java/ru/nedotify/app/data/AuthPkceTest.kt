package ru.nedotify.app.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class AuthPkceTest {
    private val client="a".repeat(32)
    private fun failure(block:()->Unit):String=try {block();fail("Expected SourceFailure");""}catch(error:SourceFailure){error.message.orEmpty()}
    @Test fun `PKCE challenge follows RFC7636 vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",AuthPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
    @Test fun `login attempts have independent entropy and persist verifier`() {
        val a=AuthPkce.pending(client,1000);val b=AuthPkce.pending(client,1000)
        assertNotEquals(a.state,b.state);assertNotEquals(a.verifier,b.verifier)
        assertNotEquals(a.state,a.verifier)
        val restored=PendingSpotify.stored(a.json())
        assertEquals(a.state,restored.state);assertEquals(a.verifier,restored.verifier)
        assertFalse(a.toString().contains(a.verifier))
    }
    @Test fun `callback validates exact redirect and single use state belongs to caller`() {
        val pending=AuthPkce.pending(client,1000)
        assertEquals("valid+code",AuthPkce.callback("${AuthPkce.REDIRECT}?state=${pending.state}&code=${URLEncoder.encode("valid+code","UTF-8")}",pending,2000))
        listOf("nedotify://oauth.evil/spotify","nedotify://user@oauth/spotify","nedotify://oauth/spotify/","https://oauth/spotify").forEach {prefix->
            failure {AuthPkce.callback("$prefix?state=${pending.state}&code=good",pending,2000)}
        }
        failure {AuthPkce.callback("${AuthPkce.REDIRECT}?state=attacker&code=good",pending,2000)}
        failure {AuthPkce.callback("${AuthPkce.REDIRECT}?state=${pending.state}&state=${pending.state}&code=good",pending,2000)}
        failure {AuthPkce.callback("${AuthPkce.REDIRECT}?state=${pending.state}&code=good#anything",pending,2000)}
    }
    @Test fun `expiry clock rollback cancellation and malformed encoding have safe errors`() {
        val pending=AuthPkce.pending(client,1000)
        val uri="${AuthPkce.REDIRECT}?state=${pending.state}&code=secretAuthorizationCode"
        assertFalse(failure {AuthPkce.callback(uri,pending,601001)}.contains("secretAuthorizationCode"))
        failure {AuthPkce.callback(uri,pending,999)}
        assertEquals("Вход Spotify отменён",failure {AuthPkce.callback("${AuthPkce.REDIRECT}?state=${pending.state}&error=access_denied",pending,1001)})
        failure {AuthPkce.callback("${AuthPkce.REDIRECT}?state=${pending.state}&code=%zz",pending,1001)}
    }
    @Test fun `a plus in the callback is a literal plus and not a space`() {
        val pending=AuthPkce.pending(client,1000)
        // A URI query is percent-encoded and not form-encoded.
        assertEquals("abc+def",AuthPkce.callback("${AuthPkce.REDIRECT}?state=${pending.state}&code=abc%2Bdef",pending,1001))
        assertEquals("abc+def",AuthPkce.parse("${AuthPkce.REDIRECT}?state=${pending.state}&code=abc+def").code)
        assertEquals("a b",AuthPkce.parse("${AuthPkce.REDIRECT}?state=${pending.state}&code=a%20b").code)
    }
    @Test fun `tokens rotate refresh only when provided and diagnostics redact`() {
        val token=ProviderToken.response(JSONObject("""{"token_type":"Bearer","access_token":"secret-access","expires_in":3600}"""),1000,"secret-refresh")
        assertEquals("secret-refresh",token.refresh);assertTrue(token.fresh(1000));assertFalse(token.fresh(3_550_000))
        assertFalse(token.toString().contains("secret-access"));assertEquals(token.access,ProviderToken.stored(token.json()).access)
        listOf("-1","0","1.5","\"3600\"","9999999999999").forEach {expires->
            failure {ProviderToken.response(JSONObject("""{"token_type":"Bearer","access_token":"secret-access","expires_in":$expires}"""),1000)}
        }
        failure {AuthPkce.secret("Bearer secret")}
        assertFalse(failure {AuthPkce.secret("секретный-токен")}.contains("секретный"))
    }
    @Test fun `a token the user typed in is the user's mistake and not the service's`() {
        val message=failure {AuthPkce.userSecret("секретный токен")}
        assertTrue("The message must speak about the input: $message",message.contains("Введённый токен"))
        assertFalse("The token must not be printed: $message",message.contains("секретный"))
        // The service's own secrets keep the service's wording.
        assertTrue(failure {AuthPkce.secret("секретный токен")}.contains("Сервис вернул"))
    }
    @Test fun `a JSON null is not a value`() {
        // On AOSP `optString` answers the text "null" for a JSON null; the reader
        // of the app must not turn a missing value into a credential.
        val stored=ProviderToken.stored("""{"access":"access-secret","refresh":null,"expiresAt":9999999}""")
        assertEquals("",stored.refresh)
        val response=ProviderToken.response(JSONObject("""{"token_type":"Bearer","access_token":"secret-access","refresh_token":null,"expires_in":3600}"""),1000,"old-refresh")
        assertEquals("A missing refresh token keeps the old one","old-refresh",response.refresh)
        try {YandexCredential.stored("""{"access":"yandex-secret","uid":null,"name":"Слушатель"}""");fail("A JSON null is not an account id")}catch(_:IllegalArgumentException){}
    }
    @Test fun `the Spotify application has one spelling`() {
        assertEquals(client,AuthPkce.validateClientId("  "+client.uppercase()+"  "))
        assertEquals(client,AuthPkce.validateClientId(client))
        // The stored and the typed id are the same application.
        assertEquals(AuthPkce.validateClientId(client.uppercase()),AuthPkce.validateClientId(client))
        failure {AuthPkce.validateClientId("not a client id")}
        failure {AuthPkce.validateClientId("z".repeat(32))}
    }
    @Test fun `a callback is matched against the login whose state it carries`() {
        val first=AuthPkce.pending(client,1000);val second=AuthPkce.pending(client,1000)
        val callback=AuthPkce.parse("${AuthPkce.REDIRECT}?state=${second.state}&code=good-code")
        assertEquals(second.state,callback.state)
        assertTrue(AuthPkce.decide(callback,second,1001) is AuthPkce.Outcome.Granted)
        // The callback does not belong to the first login.
        failure {AuthPkce.decide(callback,first,1001)}
    }
    @Test fun `a refused login is a typed outcome and not a message`() {
        val pending=AuthPkce.pending(client,1000)
        val denied=AuthPkce.decide(AuthPkce.parse("${AuthPkce.REDIRECT}?state=${pending.state}&error=access_denied"),pending,1001)
        assertTrue(denied is AuthPkce.Outcome.Denied)
        assertEquals("Вход Spotify отменён",(denied as AuthPkce.Outcome.Denied).message)
        val refused=AuthPkce.decide(AuthPkce.parse("${AuthPkce.REDIRECT}?state=${pending.state}&error=invalid_scope"),pending,1001)
        assertTrue("A refused login is over as well",refused is AuthPkce.Outcome.Denied)
    }
    @Test fun `every started login is kept until it is finished or expires`() {
        val first=AuthPkce.pending(client,1000);val second=AuthPkce.pending(client,1000)
        val logins=PendingLogins.EMPTY.add(first).add(second)
        assertEquals(2,logins.items.size)
        assertEquals(first.state,logins.match(first.state)?.state)
        assertEquals(second.state,logins.match(second.state)?.state)
        assertNull(logins.match("a state of nobody"))
        // The list survives the round trip through the store.
        val restored=PendingLogins.stored(logins.json())
        assertEquals(2,restored.items.size)
        // A login of another application is not ours.
        assertEquals(0,restored.forClient("b".repeat(32)).items.size)
        // A login outside the window is over.
        assertEquals(0,restored.fresh(1000+PendingLogins.MAX_AGE_MS+1).items.size)
        assertEquals(2,restored.fresh(1000+1000).items.size)
        // A single login of the old format is still accepted.
        assertEquals(1,PendingLogins.stored(first.json()).items.size)
        // A finished login is removed, the other one stays.
        assertEquals(1,restored.without(first.state).items.size)
    }
    @Test fun `the list of pending logins is bounded`() {
        var logins=PendingLogins.EMPTY
        val started=mutableListOf<PendingSpotify>()
        repeat(PendingLogins.MAX+5){val login=AuthPkce.pending(client,1000);started+=login;logins=logins.add(login)}
        assertEquals("The list must not grow without a bound",PendingLogins.MAX,logins.items.size)
        // The newest login is kept, the oldest one is dropped.
        assertEquals(started.last().state,logins.items.first().state)
        assertNull("The oldest login must be gone",logins.match(started.first().state))
        assertTrue(logins.match(started.last().state)!=null)
    }
    @Test fun `only the registered redirect is an authorization callback`() {
        assertTrue(AuthPkce.isRedirect("${AuthPkce.REDIRECT}?state=abc&code=def"))
        assertTrue(AuthPkce.isRedirect(AuthPkce.REDIRECT))
        listOf("nedotify://oauth.evil/spotify","nedotify://user@oauth/spotify","nedotify://oauth/spotify/","https://oauth/spotify",
            "nedotify://oauth/spotify#fragment","not a uri").forEach {foreign->
            assertFalse("A foreign address must not be a callback: $foreign",AuthPkce.isRedirect(foreign))
        }
    }
}
