package ru.nedotify.app.ui

import org.junit.Assert.*
import org.junit.Test
import ru.nedotify.app.data.ProviderConnectionStatus
import java.io.File

class ConnectionsControlsTest {
    @Test fun `the typed value is only dropped when the service accepted it`() {
        val account=ProviderConnectionStatus(yandexConnected=true,yandexName="Слушатель",yandexRevision=3)
        // A failed save: the revision did not move, the value must survive.
        assertFalse(credentialSaveSettled("yandex","token",account,3))
        // The accepted save: the revision moved.
        assertTrue(credentialSaveSettled("yandex","token",account.copy(yandexRevision=4),3))
        // The account was dropped in the meantime: this is not an accepted save.
        assertFalse(credentialSaveSettled("yandex","token",account.copy(yandexRevision=4,yandexConnected=false),3))
    }
    @Test fun `the stored Spotify client id is the signal of an accepted save`() {
        val configured=ProviderConnectionStatus(spotifyConfigured=true,spotifyClientId="a".repeat(32))
        assertTrue(credentialSaveSettled("spotify","a".repeat(32),configured,0))
        assertTrue("The id is compared without the surrounding spaces",credentialSaveSettled("spotify","  "+"A".repeat(32)+"  ",configured,0))
        assertFalse(credentialSaveSettled("spotify","b".repeat(32),configured,0))
        assertFalse(credentialSaveSettled("spotify","a".repeat(32),ProviderConnectionStatus(),0))
    }
    @Test fun `an unknown service never settles`() {
        assertFalse(credentialSaveSettled("","value",ProviderConnectionStatus(yandexConnected=true,yandexRevision=1),0))
        assertFalse(credentialSaveSettled("spotify-client","value",ProviderConnectionStatus(spotifyConfigured=true,spotifyClientId="a".repeat(32)),0))
    }
    /**
     * The delivery of the OAuth callback.
     *
     * The Activity is the only place that receives the callback of the browser,
     * and its lifecycle cannot be observed from a JVM test. The contract is
     * therefore pinned on the source of the delivery itself: the callback must
     * be read on **every** creation of the Activity (a process death delivers
     * the intent again, and the login must still be finishable) and the
     * delivery must be marked in a holder that a configuration change does not
     * clear (a rotation must not deliver the same callback twice).
     */
    @Test fun `the OAuth callback is delivered on every creation and only once per process`() {
        val activity=source("ru/nedotify/app/MainActivity.kt")
        assertFalse("The callback must not be read only on the first creation",
            activity.contains("savedInstanceState==null)readOAuth"))
        assertTrue("The callback must be read on every creation",activity.contains("readOAuth(intent)"))
        assertTrue("The delivery must be marked",activity.contains("delivery.take("))
        assertTrue("The marker must live in a ViewModel",activity.contains("class OAuthDeliveryViewModel:ViewModel()"))
        assertFalse("The callback must stay re-deliverable",activity.contains("intent.data=null"))
    }
    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=File("").absoluteFile
        while(root.parentFile!=null) {
            val file=File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }
}
