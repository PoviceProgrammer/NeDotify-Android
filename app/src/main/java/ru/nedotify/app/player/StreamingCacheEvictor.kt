package ru.nedotify.app.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor

/** Changes the cache quota after DataStore loads, without blocking service creation. */
@UnstableApi
class StreamingCacheEvictor(initialBytes:Long):CacheEvictor {
    private var delegate=LeastRecentlyUsedCacheEvictor(initialBytes)

    fun setLimit(cache:Cache,bytes:Long)=synchronized(cache) {
        // SimpleCache invokes eviction callbacks under this same monitor. Swap
        // first so recursive removal callbacks reach the newly populated LRU.
        val spans=cache.keys.flatMap {cache.getCachedSpans(it)}.sortedBy {it.lastTouchTimestamp}
        delegate=LeastRecentlyUsedCacheEvictor(bytes)
        spans.forEach {span->if(cache.getCachedSpans(span.key).contains(span))delegate.onSpanAdded(cache,span)}
    }
    override fun requiresCacheSpanTouches()=true
    override fun onCacheInitialized()=delegate.onCacheInitialized()
    override fun onStartFile(cache:Cache,key:String,position:Long,length:Long)=delegate.onStartFile(cache,key,position,length)
    override fun onSpanAdded(cache:Cache,span:CacheSpan)=delegate.onSpanAdded(cache,span)
    override fun onSpanRemoved(cache:Cache,span:CacheSpan)=delegate.onSpanRemoved(cache,span)
    override fun onSpanTouched(cache:Cache,oldSpan:CacheSpan,newSpan:CacheSpan)=delegate.onSpanTouched(cache,oldSpan,newSpan)
}
