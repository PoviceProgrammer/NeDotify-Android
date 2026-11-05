package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureProviderStoreTest {
    /** A keystore that is not the Android one: the key policy must not depend on the device. */
    private class FakeKeys(var state:ProviderKey):ProviderKeyStore {
        var created=0;var deleted=0
        override fun load(alias:String):ProviderKey=state
        override fun create(alias:String):SecretKey {
            created++
            val key=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
            state=ProviderKey.Usable(key);return key
        }
        override fun delete(alias:String) {deleted++;state=ProviderKey.Missing}
    }
    @get:Rule val folder=TemporaryFolder()
    private fun aesKey():SecretKey=KeyGenerator.getInstance("AES").apply {init(256)}.generateKey()
    private fun failure(block:()->Unit):String=try {block();fail("Expected SourceFailure");""}catch(error:SourceFailure){error.message.orEmpty()}

    @Test fun `the key policy reuses a usable key and replaces a broken alias`() {
        val key=aesKey()
        assertEquals(ProviderKeyAction.USE,providerKeyAction(ProviderKey.Usable(key),true))
        assertEquals(ProviderKeyAction.USE,providerKeyAction(ProviderKey.Usable(key),false))
        assertEquals(ProviderKeyAction.CREATE,providerKeyAction(ProviderKey.Missing,true))
        assertEquals(ProviderKeyAction.REFUSE,providerKeyAction(ProviderKey.Missing,false))
        assertEquals(ProviderKeyAction.RECREATE,providerKeyAction(ProviderKey.Broken,true))
        assertEquals(ProviderKeyAction.REFUSE,providerKeyAction(ProviderKey.Broken,false))
        assertEquals(ProviderKeyAction.REFUSE,providerKeyAction(ProviderKey.Unavailable,true))
        assertEquals(ProviderKeyAction.REFUSE,providerKeyAction(ProviderKey.Unavailable,false))
    }
    @Test fun `a secret survives a round trip and is bound to its own name`() {
        val keys=FakeKeys(ProviderKey.Missing);val cipher=ProviderSecretCipher(keys)
        val sealed=cipher.seal("token-value","yandex_token")
        assertEquals("token-value",cipher.open(sealed,"yandex_token"))
        assertTrue("The record must be encrypted",!sealed.toString(Charsets.UTF_8).contains("token-value"))
        // A record cannot be moved to another secret.
        assertTrue(failure {cipher.open(sealed,"yandex_user")}.isNotEmpty())
    }
    @Test fun `a broken alias is replaced instead of failing forever`() {
        val keys=FakeKeys(ProviderKey.Broken);val cipher=ProviderSecretCipher(keys)
        val sealed=cipher.seal("token-value","yandex_token")
        assertEquals("The broken alias must be deleted",1,keys.deleted)
        assertEquals("A new key must be created",1,keys.created)
        assertEquals("token-value",cipher.open(sealed,"yandex_token"))
    }
    @Test fun `a missing alias is created without deleting anything`() {
        val keys=FakeKeys(ProviderKey.Missing);val cipher=ProviderSecretCipher(keys)
        cipher.seal("token-value","yandex_token")
        assertEquals(0,keys.deleted);assertEquals(1,keys.created)
    }
    @Test fun `an unreachable keystore never changes the alias`() {
        val keys=object:ProviderKeyStore {
            override fun load(alias:String):ProviderKey=ProviderKey.Unavailable
            override fun create(alias:String):SecretKey=throw AssertionError("The keystore must not be touched")
            override fun delete(alias:String) {throw AssertionError("The keystore must not be touched")}
        }
        val message=failure {ProviderSecretCipher(keys).seal("token-value","yandex_token")}
        assertTrue("The user must be told that the key is gone: $message",message.contains("Ключ входа недоступен"))
    }
    @Test fun `a raw cryptographic exception never reaches the caller`() {
        val keys=object:ProviderKeyStore {
            override fun load(alias:String):ProviderKey=throw IllegalStateException("keystore exploded")
            override fun create(alias:String):SecretKey=throw IllegalStateException("keystore exploded")
            override fun delete(alias:String) {throw IllegalStateException("keystore exploded")}
        }
        val cipher=ProviderSecretCipher(keys)
        val written=failure {cipher.seal("token-value","yandex_token")}
        assertFalse("The raw message must not reach the user: $written",written.contains("keystore exploded"))
        assertTrue(written.isNotEmpty())
        val read=failure {cipher.open(ByteArray(40){1},"yandex_token")}
        assertFalse("The raw message must not reach the user: $read",read.contains("keystore exploded"))
    }
    @Test fun `a damaged record is refused without a cryptographic error`() {
        val keys=FakeKeys(ProviderKey.Missing);val cipher=ProviderSecretCipher(keys)
        val damaged=cipher.seal("token-value","yandex_token").copyOf(12)
        assertTrue(failure {cipher.open(damaged,"yandex_token")}.contains("повреждена"))
    }
    @Test fun `an unavailable key and a damaged record are told apart`() {
        // The key cannot be used: the record is left alone.
        val unavailable=object:ProviderKeyStore {
            override fun load(alias:String):ProviderKey=ProviderKey.Unavailable
            override fun create(alias:String):SecretKey=throw AssertionError("The keystore must not be touched")
            override fun delete(alias:String) {throw AssertionError("The keystore must not be touched")}
        }
        val error=runCatching {ProviderSecretCipher(unavailable).open(ByteArray(40){1},"yandex_token")}.exceptionOrNull()
        assertTrue("The failure must have its own type: ${error?.javaClass}",error is ProviderCredentialUnavailableException)
        assertFalse("It must not be a corruption: ${error?.javaClass}",error is ProviderCredentialCorruptException)
        // A missing alias cannot decrypt the record either - but the record is not
        // damaged either: it is left alone, and the next write recreates the alias.
        val missing=runCatching {ProviderSecretCipher(FakeKeys(ProviderKey.Missing)).open(ByteArray(40){1},"yandex_token")}.exceptionOrNull()
        assertTrue("A missing alias is not a damaged record: ${missing?.javaClass}",missing is ProviderCredentialUnavailableException)
        assertFalse("It must not be a corruption: ${missing?.javaClass}",missing is ProviderCredentialCorruptException)
        // The record itself is damaged: it may be removed.
        val cipher=ProviderSecretCipher(FakeKeys(ProviderKey.Missing))
        val sealed=cipher.seal("token-value","yandex_token")
        val damaged=runCatching {cipher.open(sealed.copyOf(sealed.size-1),"yandex_token")}.exceptionOrNull()
        assertTrue("The failure must have its own type: ${damaged?.javaClass}",damaged is ProviderCredentialCorruptException)
        assertFalse("It must not be an unavailable key: ${damaged?.javaClass}",damaged is ProviderCredentialUnavailableException)
        val header=runCatching {cipher.open(ByteArray(12),"yandex_token")}.exceptionOrNull()
        assertTrue("A record that is not a record is damaged: ${header?.javaClass}",header is ProviderCredentialCorruptException)
    }

    // ---- the file layer ----

    private fun fileStore(directory:File)=SecureProviderStore(directory,ProviderSecretCipher(FakeKeys(ProviderKey.Missing)))
    @Test fun `a secret survives a round trip through the file layer`() {
        val directory=folder.newFolder("provider-secrets")
        val store=fileStore(directory)
        store.write("yandex_token","token-value")
        assertEquals("token-value",store.read("yandex_token"))
        val record=File(directory,"yandex_token.bin")
        assertTrue("The record must be on the disk",record.exists())
        assertFalse("The secret must not be readable on the disk",record.readBytes().toString(Charsets.UTF_8).contains("token-value"))
        assertNull("A record of another secret is a different record",store.read("spotify_token"))
    }
    @Test fun `a record whose base file was lost is restored from the backup`() {
        val directory=folder.newFolder("provider-secrets")
        val store=fileStore(directory)
        store.write("yandex_token","token-value")
        // The crash between the two renames: the base file is gone, the backup is there.
        val record=File(directory,"yandex_token.bin")
        assertTrue(record.renameTo(File(directory,"yandex_token.bin.bak")))
        assertEquals("The backup is the record", "token-value",store.read("yandex_token"))
        assertTrue("The record must be restored",record.exists())
    }
    @Test fun `an oversized record is refused without a cryptographic error`() {
        val directory=folder.newFolder("provider-secrets")
        val store=fileStore(directory)
        File(directory,"yandex_token.bin").writeBytes(ByteArray(SecureProviderStore.MAX_RECORD_BYTES+16){7})
        val message=failure {store.read("yandex_token")}
        assertTrue("The record must be refused: $message",message.contains("повреждена"))
    }
    @Test fun `a value that is too large is refused`() {
        val directory=folder.newFolder("provider-secrets")
        val store=fileStore(directory)
        val message=failure {store.write("spotify_token","x".repeat(SecureProviderStore.MAX_VALUE_BYTES+1))}
        assertTrue("The value must be refused: $message",message.contains("Слишком большой"))
        assertNull("Nothing must be written",store.read("spotify_token"))
    }
    @Test fun `a removed record leaves no remnant on the disk`() {
        val directory=folder.newFolder("provider-secrets")
        val store=fileStore(directory)
        store.write("yandex_token","token-value")
        store.remove("yandex_token")
        assertNull(store.read("yandex_token"))
        assertFalse(File(directory,"yandex_token.bin").exists())
        assertFalse(File(directory,"yandex_token.bin.bak").exists())
        assertFalse(File(directory,"yandex_token.bin.new").exists())
    }
    @Test fun `a failed write keeps the previous record`() {
        val directory=folder.newFolder("provider-secrets")
        val store=fileStore(directory)
        store.write("yandex_token","first-value")
        // The temporary file cannot be created: the previous record must survive.
        assertTrue(File(directory,"yandex_token.bin.new").mkdirs())
        assertTrue(failure {store.write("yandex_token","second-value")}.isNotEmpty())
        assertEquals("The previous record must survive","first-value",store.read("yandex_token"))
    }
}
