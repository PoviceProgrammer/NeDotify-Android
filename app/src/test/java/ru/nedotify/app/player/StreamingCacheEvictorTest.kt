package ru.nedotify.app.player

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.lang.reflect.Proxy
import java.util.TreeSet
import org.junit.Assert.*
import org.junit.Test

class StreamingCacheEvictorTest {
    @Test fun loadedQuotaEvictsOldestAndFutureWritesHonorNewLimit() {
        val spans=TreeSet<CacheSpan>()
        val removed=mutableListOf<CacheSpan>()
        val evictor=StreamingCacheEvictor(200)
        val cache=Proxy.newProxyInstance(Cache::class.java.classLoader,arrayOf(Cache::class.java)) {proxy,method,args->
            when(method.name) {
                "getKeys"->spans.map {it.key}.toSet()
                "getCachedSpans"->TreeSet(spans.filter {it.key==args!![0]})
                "removeSpan"->{val span=args!![0] as CacheSpan;spans.remove(span);removed.add(span);evictor.onSpanRemoved(proxy as Cache,span);null}
                "toString"->"test cache"
                else->throw UnsupportedOperationException(method.name)
            }
        } as Cache
        fun add(key:String,touched:Long):CacheSpan {
            val span=CacheSpan(key,0,40,touched,File(key))
            spans.add(span);evictor.onSpanAdded(cache,span);return span
        }
        val old=add("old",1)
        val recent=add("recent",2)
        evictor.setLimit(cache,50)
        assertEquals(listOf(old),removed)
        assertEquals(setOf(recent),spans.toSet())
        val newest=add("newest",3)
        assertEquals(listOf(old,recent),removed)
        assertEquals(setOf(newest),spans.toSet())
    }
}
