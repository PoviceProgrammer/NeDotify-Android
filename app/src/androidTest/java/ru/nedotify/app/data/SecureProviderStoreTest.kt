package ru.nedotify.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Device-only coverage: JVM tests cannot establish Android Keystore behaviour. */
class SecureProviderStoreTest {
    private lateinit var directory:File
    private lateinit var context:Context
    private lateinit var alias:String
    @Before fun setup() {
        val original=InstrumentationRegistry.getInstrumentation().targetContext
        alias="nedotify.test.provider."+UUID.randomUUID()
        directory=File(original.cacheDir,alias).apply {check(mkdirs())}
        context=object:ContextWrapper(original) {override fun getNoBackupFilesDir():File=directory}
    }
    @After fun cleanup() {
        if(::directory.isInitialized)directory.deleteRecursively()
        if(::alias.isInitialized)KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
    }
    @Test fun encryptedEntrySurvivesStoreRecreation() {
        val token="private-oauth-token-that-must-not-be-plaintext"
        SecureProviderStore(context,alias).write("spotify_token",token)
        val bytes=File(directory,"provider-secrets/spotify_token.bin").readBytes()
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(token))
        assertEquals(token,SecureProviderStore(context,alias).read("spotify_token"))
    }
    @Test fun tamperedCiphertextFailsClosed() {
        val store=SecureProviderStore(context,alias)
        store.write("yandex_token","private-yandex-token")
        val file=File(directory,"provider-secrets/yandex_token.bin")
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        assertThrows(SourceFailure::class.java){store.read("yandex_token")}
        store.remove("yandex_token");assertNull(store.read("yandex_token"))
    }
    @Test fun ciphertextCannotBeReusedUnderDifferentRecordName() {
        val store=SecureProviderStore(context,alias)
        store.write("spotify_token","private-spotify-token")
        File(directory,"provider-secrets/yandex_token.bin").writeBytes(File(directory,"provider-secrets/spotify_token.bin").readBytes())
        assertThrows(SourceFailure::class.java){store.read("yandex_token")}
        assertEquals("private-spotify-token",store.read("spotify_token"))
        assertThrows(IllegalArgumentException::class.java){store.write("../../escape","secret")}
    }
    @Test fun interruptedLegacyAtomicWriteRestoresTheCommittedCredential() {
        val store=SecureProviderStore(context,alias)
        val token="committed-private-token"
        store.write("spotify_token",token)
        val base=File(directory,"provider-secrets/spotify_token.bin")
        val backup=File(base.path+".bak")
        assertTrue(base.renameTo(backup));assertFalse(base.exists())
        assertEquals(token,SecureProviderStore(context,alias).read("spotify_token"))
        assertTrue(base.exists());assertFalse(backup.exists())
    }
}
