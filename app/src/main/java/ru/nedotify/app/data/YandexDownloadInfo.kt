package ru.nedotify.app.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.StringReader
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class YandexDownloadVariant(val url:String,val bitrate:Int)

/** Legacy lossy protocol also used by the desktop yandex-music client.
 * No preview playback and no authorization headers on externally returned URLs.
 * Protocol constants are public signing salts, not an OAuth client secret.
 */
object YandexDownloadInfo {
    private val idPattern=Regex("[0-9]{1,24}")
    fun parameters(id:String,now:Long):Map<String,String> {
        require(id.matches(idPattern))
        val timestamp=(now/1000).toString()
        val mac=Mac.getInstance("HmacSHA256").apply {init(SecretKeySpec("p93jhgh689SBReK6ghtw62".toByteArray(),"HmacSHA256"))}
        val sign=Base64.getEncoder().encodeToString(mac.doFinal((id+timestamp).toByteArray(Charsets.UTF_8)))
        return mapOf("can_use_streaming" to "true","ts" to timestamp,"sign" to sign)
    }
    fun trustedUrl(raw:String):HttpUrl {
        val url=raw.toHttpUrlOrNull()?:throw SourceFailure("Яндекс Музыка вернула неверный адрес аудио")
        val host=url.host
        val trusted=host=="storage.mds.yandex.net" || host.endsWith(".storage.yandex.net") || host=="music.yandex.net" || host.endsWith(".music.yandex.net")
        if(!url.isHttps || !trusted || url.port!=443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment!=null || raw.length>8192)
            throw SourceFailure("Яндекс Музыка вернула неподдерживаемый адрес аудио")
        return url
    }
    fun select(rows:List<JSONObject>):YandexDownloadVariant {
        return rows.asSequence().filter {it.opt("preview")==false && it.optString("codec")=="mp3"}
            .mapNotNull {item->
                val bitrate=item.optInt("bitrateInKbps")
                if(bitrate !in 32..512)return@mapNotNull null
                val url=try {trustedUrl(item.optString("downloadInfoUrl"))}catch(_:SourceFailure){return@mapNotNull null}
                YandexDownloadVariant(url.toString(),bitrate)
            }.maxByOrNull {it.bitrate}?:throw SourceFailure("Полная запись недоступна для аккаунта. Проверьте подписку или выберите другой источник")
    }
    fun directLink(xml:String):String {
        if(xml.length>65_536 || xml.contains("<!DOCTYPE",true) || xml.contains("<!ENTITY",true))throw SourceFailure("Яндекс Музыка вернула неверные данные аудио")
        try {
            val factory=DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware=false;isExpandEntityReferences=false
                // Android and host JAXP expose different optional features. The
                // unconditional declaration rejection above and empty resolver
                // below enforce this boundary on both implementations.
                runCatching {setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}
                runCatching {setFeature("http://xml.org/sax/features/external-general-entities",false)}
                runCatching {setFeature("http://xml.org/sax/features/external-parameter-entities",false)}
            }
            val doc=factory.newDocumentBuilder().apply {setEntityResolver {_,_->InputSource(StringReader(""))}}
                .parse(InputSource(StringReader(xml)))
            fun field(name:String):String {
                val list=doc.getElementsByTagName(name)
                if(list.length!=1)throw IllegalArgumentException()
                return list.item(0).textContent
            }
            val host=field("host");val path=field("path");val ts=field("ts");val salt=field("s")
            if(!host.matches(Regex("[a-zA-Z0-9.-]{1,253}")) || !ts.matches(Regex("[0-9]{1,24}")) || salt.length !in 1..1024 ||
                !path.startsWith('/') || path.length>4096 || path.any {it.isISOControl() || it in "?#\\"} || path.split('/').any {it==".."})throw IllegalArgumentException()
            val signature=MessageDigest.getInstance("MD5").digest(("XGRlBW9FXlekgbPrRHuSiA"+path.drop(1)+salt).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
            return trustedUrl("https://$host/get-mp3/$signature/$ts$path").toString()
        }catch(error:SourceFailure){throw error}
        catch(_:Exception){throw SourceFailure("Яндекс Музыка вернула неверные данные аудио")}
    }
}
