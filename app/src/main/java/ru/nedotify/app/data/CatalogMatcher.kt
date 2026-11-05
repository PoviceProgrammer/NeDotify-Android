package ru.nedotify.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.coroutines.coroutineContext

data class CatalogMatch(val track:Track,val confidence:Double)

/** A catalog entry is not audio. Only a separately identified, sufficiently close provider track is playable. */
object CatalogMatcher {
    private val bracketedDecoration=Regex("(?i)\\s*[\\[(](?:official(?:\\s+(?:audio|video|music video))?|lyrics?|visuali[sz]er|hd|hq|4k)[\\])]\\s*$")
    private val technicalSuffix=Regex("(?i)(?:\\s*[-|–—]\\s*|\\s+)(?:official\\s+(?:audio|video|music video)|lyric\\s+video|visuali[sz]er)\\s*$")
    private val versions=listOf(listOf("remix","ремикс"),listOf("live","концерт","концертная","концертный"),
        listOf("acoustic","акустика","акустическая"),listOf("instrumental","инструментал"),listOf("karaoke","караоке"),
        listOf("cover","кавер"),listOf("sped up","speed up","ускоренная"),listOf("slowed","замедленная"),listOf("remaster","remastered"))
    private fun normalize(raw:String):String=Normalizer.normalize(raw,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('ё','е')
        .replace(Regex("[^\\p{L}\\p{N}]+")," ").trim().replace(Regex("\\s+")," ")
    private fun withoutDecoration(raw:String):String {
        var value=raw
        while(true) {
            val next=bracketedDecoration.replace(value,"")
            if(next==value)break
            value=next
        }
        return technicalSuffix.replace(value,"")
    }
    private fun tokens(value:String):Set<String> = value.split(' ').filter {it.length>1}.toSet()
    private fun similarity(a:String,b:String):Double {
        if(a.isBlank() || b.isBlank())return 0.0
        if(a==b)return 1.0
        val x=tokens(a);val y=tokens(b)
        if(x.isEmpty() || y.isEmpty())return 0.0
        return (2.0*x.intersect(y).size)/(x.size+y.size)
    }
    fun match(metadata:CatalogTrack,candidates:List<Track>):CatalogMatch? {
        // Catalog titles are authoritative. Words such as "Official", "Lyrics" or "HD"
        // can be the actual song name and must not disappear from its identity.
        val title=normalize(metadata.title)
        val artist=metadata.artists.map(::normalize).filter {it.isNotBlank()}
        return candidates.asSequence().filter {it.source in setOf("soundcloud","youtube") && it.id=="${it.source}:${it.sourceId}"}.mapNotNull {track->
            fun stripArtist(raw:String):String {
                var value=normalize(raw)
                artist.forEach {name->if(value.startsWith("$name "))value=value.removePrefix("$name ")}
                return value
            }
            val fullCandidateTitle=stripArtist(track.title)
            val candidateTitle=if(fullCandidateTitle==title)fullCandidateTitle else stripArtist(withoutDecoration(track.title))
            // YouTube commonly includes the artist in the title; never use the uploader alone as identity.
            val normalizedArtist=normalize(track.artist)
            val author=artist.maxOfOrNull {name->maxOf(similarity(name,normalizedArtist),if(normalize(track.title).startsWith("$name "))1.0 else 0.0)}?:0.0
            val name=similarity(title,candidateTitle)
            fun hasVersion(value:String,tags:List<String>):Boolean=tags.any {tag->" $tag " in " $value "}
            val differentVersion=versions.any {tags->hasVersion(title,tags)!=hasVersion(candidateTitle,tags)}
            if(author<.70 || name<.86 || differentVersion)return@mapNotNull null
            val knownDuration=metadata.durationMs>0 && track.durationMs>0
            val difference=abs(metadata.durationMs-track.durationMs)
            if(knownDuration && difference>maxOf(8_000L,(metadata.durationMs*.04).toLong()))return@mapNotNull null
            val duration=if(!knownDuration).5 else (1.0-difference.toDouble()/maxOf(8_000L,(metadata.durationMs*.04).toLong())).coerceIn(0.0,1.0)
            val confidence=name*.60+author*.30+duration*.10
            if(confidence<.87)null else CatalogMatch(track,confidence)
        }.maxByOrNull {it.confidence}
    }
    suspend fun resolve(metadata:CatalogTrack,searchers:List<suspend (String)->List<Track>>,
        prepare:suspend (Track)->Unit = {}):Track {
        val query="${metadata.artist} ${metadata.title}".take(200)
        var available=false
        var classified:SourceFailure?=null
        for(search in searchers) {
            coroutineContext.ensureActive()
            try {
                val candidates=search(query).toMutableList();available=true
                while(candidates.isNotEmpty()) {
                    val matched=match(metadata,candidates)?:break
                    candidates.removeAll {it.id==matched.track.id}
                    try {
                        prepare(matched.track)
                        return matched.track.copy(title=metadata.title,artist=metadata.artist,album=metadata.album,
                            durationMs=metadata.durationMs.takeIf {it>0}?:matched.track.durationMs,
                            coverUrl=metadata.coverUrl.ifBlank {matched.track.coverUrl})
                    } catch(cancelled:CancellationException){throw cancelled}
                    catch(error:SourceFailure) {
                        if(error.httpCode in setOf(401,403,429))throw error
                        classified=classified?:error
                    }
                    catch(_:Exception){ /* A different matching candidate can still be playable. */ }
                }
            }
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:SourceFailure){if(error.httpCode in setOf(401,403,429))throw error;classified=classified?:error}
            catch(_:Exception){ /* Try the next explicitly supplied playback provider, never a preview URL. */ }
        }
        // No provider answered at all: the request is blocked, not a missing recording.
        // A provider that answered but has no usable recording is a per-item failure.
        if(!available)throw classified?.let {CatalogImportBlocked(it.message?:"Источники воспроизведения недоступны. Повторите позже",it.httpCode)}
            ?:CatalogImportBlocked("Источники воспроизведения недоступны. Повторите позже")
        throw classified ?: SourceFailure("Не найдено достаточно точное совпадение. Найдите нужную версию вручную")
    }
}
