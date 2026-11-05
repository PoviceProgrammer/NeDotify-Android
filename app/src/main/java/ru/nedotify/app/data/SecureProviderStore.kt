package ru.nedotify.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ProviderSecretStore {
    fun read(key:String):String?
    fun write(key:String,value:String)
    fun remove(key:String)
}

/**
 * The credential cannot be read **right now**.
 *
 * The keystore cannot be reached, or its alias is gone: the record itself may
 * be perfectly good. A credential the user had to obtain out of band is never
 * destroyed for this reason - the caller keeps the record and tells the user
 * that the key is unavailable.
 */
class ProviderCredentialUnavailableException(message:String):SourceFailure(message)

/**
 * The stored record is damaged.
 *
 * The bytes are not a record, or they do not decrypt under a usable key: the
 * record is worthless, and only this failure may remove it.
 */
class ProviderCredentialCorruptException(message:String):SourceFailure(message)

/** The state of the keystore alias. */
internal sealed interface ProviderKey {
    /** The alias holds a usable key. */
    class Usable(val key:SecretKey):ProviderKey
    /** The alias does not exist. */
    object Missing:ProviderKey
    /** The alias exists but cannot be read back: it must be replaced. */
    object Broken:ProviderKey
    /** The keystore itself cannot be reached: nothing may be changed. */
    object Unavailable:ProviderKey
}

/** What to do with the keystore alias. */
internal enum class ProviderKeyAction { USE, CREATE, RECREATE, REFUSE }

/**
 * The key policy.
 *
 * A key that cannot be read back is broken: it must be replaced, otherwise
 * every read and every write of the store fails forever. A missing key is
 * created where a write is expected. A keystore that cannot be reached is
 * never touched - the credential is left alone and the user is told.
 */
internal fun providerKeyAction(key:ProviderKey,create:Boolean):ProviderKeyAction=when(key) {
    is ProviderKey.Usable->ProviderKeyAction.USE
    ProviderKey.Missing->if(create)ProviderKeyAction.CREATE else ProviderKeyAction.REFUSE
    ProviderKey.Broken->if(create)ProviderKeyAction.RECREATE else ProviderKeyAction.REFUSE
    ProviderKey.Unavailable->ProviderKeyAction.REFUSE
}

/**
 * The Android Keystore seam.
 *
 * The store is the only part of the app that talks to the Keystore, and the
 * only part that cannot run in a unit test. The interface keeps the key policy
 * - reuse, replace, refuse - testable without a device.
 */
internal interface ProviderKeyStore {
    fun load(alias:String):ProviderKey
    fun create(alias:String):SecretKey
    fun delete(alias:String)
}

/** The real Keystore. */
internal class AndroidProviderKeyStore:ProviderKeyStore {
    override fun load(alias:String):ProviderKey=try {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        when(val key=store.getKey(alias,null)) {
            null->ProviderKey.Missing
            is SecretKey->ProviderKey.Usable(key)
            else->ProviderKey.Broken
        }
    } catch(_:UnrecoverableKeyException){ProviderKey.Broken}
    catch(_:Exception){ProviderKey.Unavailable}
    override fun create(alias:String):SecretKey=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
    }.generateKey()
    override fun delete(alias:String) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        store.deleteEntry(alias)
    }
}

/**
 * The encrypted record of one secret.
 *
 * The record is `version | iv | ciphertext` and the name of the secret is the
 * additional authenticated data: a record cannot be moved to another secret.
 * A raw cryptographic exception never leaves this class.
 *
 * The two failures are told apart, because the caller acts on them
 * differently: a key that cannot be used **now** leaves the record alone, and
 * only a record that cannot be decrypted is damaged.
 */
internal class ProviderSecretCipher(private val keys:ProviderKeyStore,val alias:String="nedotify.provider-secrets.v1") {
    fun seal(value:String,aad:String):ByteArray=try {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key(true));cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        byteArrayOf(VERSION)+cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    } catch(error:ProviderCredentialUnavailableException){throw error}
    catch(error:ProviderCredentialCorruptException){throw error}
    catch(error:SourceFailure){throw error}
    catch(_:Exception){throw SourceFailure("Не удалось сохранить защищённые данные входа")}
    fun open(payload:ByteArray,aad:String):String=try {
        if(payload.size<29 || payload[0]!=VERSION.toByte())throw ProviderCredentialCorruptException("Запись защищённого хранилища повреждена")
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,payload.copyOfRange(1,13)))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        cipher.doFinal(payload.copyOfRange(13,payload.size)).toString(Charsets.UTF_8)
    } catch(error:ProviderCredentialUnavailableException){throw error}
    catch(error:ProviderCredentialCorruptException){throw error}
    catch(error:SourceFailure){throw error}
    catch(_:Exception){throw ProviderCredentialCorruptException("Не удалось прочитать сохранённый вход. Отключите сервис и подключите заново")}
    /**
     * The key of the record.
     *
     * A key that cannot be used leaves the record alone: the caller decides
     * what to do with a credential it cannot read *now*.
     */
    private fun key(create:Boolean):SecretKey {
        val loaded=keys.load(alias)
        return when(providerKeyAction(loaded,create)) {
            ProviderKeyAction.USE->(loaded as ProviderKey.Usable).key
            ProviderKeyAction.REFUSE->throw ProviderCredentialUnavailableException("Ключ входа недоступен. Подключите сервис заново")
            ProviderKeyAction.CREATE->createKey()
            ProviderKeyAction.RECREATE->{deleteKey();createKey()}
        }
    }
    private fun createKey():SecretKey=try {keys.create(alias)}
    catch(error:SourceFailure){throw error}
    catch(_:Exception){throw SourceFailure("Не удалось создать ключ защищённого хранилища")}
    private fun deleteKey() {try {keys.delete(alias)} catch(_:Exception){}}
    private companion object {const val VERSION:Byte=1}
}

/**
 * The file that holds one encrypted record.
 *
 * The write is crash-safe: the new content goes into a temporary file that is
 * renamed over the old one, and the old content is kept as `.bak` until the
 * rename has succeeded. A record whose base file was lost is restored from
 * `.bak`: a crash between the two renames must not lose a credential.
 *
 * The layer is written on `java.io.File` and not on `android.util.AtomicFile`
 * for one reason: `AtomicFile` is a platform class that a JVM test cannot
 * run, and the recovery of a half-written record is exactly the behaviour that
 * has to be guarded. The names on the disk (`.bin`, `.bin.bak`) are the same.
 */
internal class SecretRecordFile(private val file:File) {
    private val backup=File(file.path+".bak")
    private val pending=File(file.path+".new")
    /** The bytes of the record, or null when there is none. */
    fun read(maxBytes:Int):ByteArray? {
        if(!file.exists()) {
            // The base file was lost: the backup *is* the record.
            if(!backup.exists())return null
            if(!backup.renameTo(file))throw SourceFailure("Не удалось прочитать сохранённый вход. Отключите сервис и подключите заново")
        }
        val output=ByteArrayOutputStream();val buffer=ByteArray(4096)
        try {
            file.inputStream().use {input->
                while(true) {
                    val count=input.read(buffer)
                    if(count<0)break
                    if(output.size()+count>maxBytes)throw ProviderCredentialCorruptException("Запись защищённого хранилища повреждена")
                    output.write(buffer,0,count)
                }
            }
        } catch(error:ProviderCredentialCorruptException){throw error}
        catch(error:SourceFailure){throw error}
        catch(_:Exception){throw SourceFailure("Не удалось прочитать сохранённый вход. Отключите сервис и подключите заново")}
        return output.toByteArray()
    }
    /** Writes the record. A failed write leaves the previous record in place. */
    fun write(bytes:ByteArray) {
        try {
            pending.outputStream().use {stream->stream.write(bytes);stream.flush();stream.fd.sync()}
        } catch(_:Exception){pending.delete();throw SourceFailure("Не удалось сохранить защищённые данные входа")}
        if(file.exists()&&!file.renameTo(backup)){pending.delete();throw SourceFailure("Не удалось сохранить защищённые данные входа")}
        if(!pending.renameTo(file)){backup.renameTo(file);pending.delete();throw SourceFailure("Не удалось сохранить защищённые данные входа")}
        backup.delete()
    }
    /** Removes the record and every remnant of it. */
    fun delete() {file.delete();backup.delete();pending.delete()}
}

/** Ciphertext lives in noBackupFilesDir, the encryption key never leaves Android Keystore. */
class SecureProviderStore internal constructor(
    private val directory:File,private val cipher:ProviderSecretCipher
):ProviderSecretStore {
    constructor(context:Context,alias:String="nedotify.provider-secrets.v1"):
        this(File(context.noBackupFilesDir,"provider-secrets"),ProviderSecretCipher(AndroidProviderKeyStore(),alias))
    private val allowed=setOf("spotify_config","spotify_token","spotify_pending","yandex_token","yandex_user")
    private fun file(key:String):SecretRecordFile {
        require(key in allowed)
        if(!directory.isDirectory && !directory.mkdirs())throw SourceFailure("Не удалось открыть защищённое хранилище")
        return SecretRecordFile(File(directory,"$key.bin"))
    }
    @Synchronized override fun read(key:String):String? {
        // A record that cannot be read is not a record that may be removed: the
        // caller is told which failure it was.
        val bytes=file(key).read(MAX_RECORD_BYTES) ?: return null
        return cipher.open(bytes,key)
    }
    @Synchronized override fun write(key:String,value:String) {
        if(value.toByteArray(Charsets.UTF_8).size>MAX_VALUE_BYTES)throw SourceFailure("Слишком большой ответ авторизации")
        // The key and the cipher are prepared inside the guarded block: a broken
        // alias is replaced and a raw cryptographic exception never reaches the
        // user.
        val bytes=try {cipher.seal(value,key)} catch(error:SourceFailure){throw error} catch(_:Exception){throw SourceFailure("Не удалось сохранить защищённые данные входа")}
        file(key).write(bytes)
    }
    @Synchronized override fun remove(key:String) {file(key).delete()}
    internal companion object {
        /** The largest record that may be read from the disk. */
        const val MAX_RECORD_BYTES=32_768
        /** The largest secret that may be written. */
        const val MAX_VALUE_BYTES=24_000
    }
}
