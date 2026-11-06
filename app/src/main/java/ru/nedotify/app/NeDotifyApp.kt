package ru.nedotify.app

import android.app.Application
import androidx.room.Room
import ru.nedotify.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import androidx.media3.datasource.cache.*
import androidx.media3.database.StandaloneDatabaseProvider
import java.io.File
import android.util.Log
import ru.nedotify.app.downloads.Downloads
import kotlinx.coroutines.flow.collect

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NeDotifyApp:Application(),coil3.SingletonImageLoader.Factory {
    val ioScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    val uiEvents by lazy {ru.nedotify.app.ui.state.UiEvents()}
    /** One controller connection survives Activity and feature ViewModel recreation. */
    val playbackConnection by lazy {ru.nedotify.app.ui.state.PlaybackConnection(this)}
    /**
     * One database provider for both media caches.
     *
     * The streaming cache and the download cache are two `SimpleCache`
     * instances over one Media3 database file. Two providers would be two
     * connections to that file, and the second one could find it locked.
     */
    val mediaDatabase by lazy {StandaloneDatabaseProvider(this)}
    val downloads by lazy {Downloads(this,repository,ioScope,mediaDatabase)}
    override fun onCreate() {
        super.onCreate()
        // Coil's own network fetcher would use a stock client without any
        // timeout and without a bound on the size of the response.
        coil3.SingletonImageLoader.setSafe(this)
        // The download state is recovered in the background. Forcing it here
        // would open the Media3 database, the Room database and the reconcile
        // query before the first frame: the recovery is worth doing, but not
        // on the thread that draws the window.
        ioScope.launch {downloads}
    }
    override fun newImageLoader(context:android.content.Context):coil3.ImageLoader=ImageHttp.loader(context)
    val audioCache by lazy {
        val evictor=ru.nedotify.app.player.StreamingCacheEvictor(256L*1024*1024)
        SimpleCache(File(cacheDir,"audio"),evictor,mediaDatabase).also {cache->
            ioScope.launch {
                // The limit is applied only when the file has confirmed it: a
                // limit the user never chose must not pass for the user's one.
                try {repository.audioSettings.cacheLimit.collect {limit->
                    if(limit.confirmed)evictor.setLimit(cache,limit.megabytes.toLong()*1024*1024)
                }} catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception){Log.e("NeDotify","Streaming cache quota could not be applied",error)}
            }
        }
    }
    private val queueWrites=Channel<String>(Channel.CONFLATED)
    init {
        ioScope.launch {for(snapshot in queueWrites) {
            try {repository.preferences.saveQueue(snapshot)}
            catch(error:java.io.IOException){Log.e("NeDotify","Queue checkpoint could not be saved",error)}
        }}
    }
    fun saveQueue(snapshot:String) {queueWrites.trySend(snapshot)}
    val repository:LibraryRepository by lazy {
        val database=Room.databaseBuilder(this,LibraryDatabase::class.java,"nedotify-android.db")
            .addMigrations(LibraryDatabase.MIGRATION_1_2,LibraryDatabase.MIGRATION_2_3,LibraryDatabase.MIGRATION_3_4).build()
        LibraryRepository(this,database.library(),Preferences(this),MusicNetwork())
    }
}
