package ru.nedotify.app.ui

import ru.nedotify.app.data.*

data class QueueRow(val id:String,val title:String,val artist:String="",val artwork:String="",val durationMs:Long=0) {
    /** The rows used to be pairs; the player screen still reads .first and .second. */
    val first get()=id
    val second get()=title
}
data class NowPlaying(val id:String="",val title:String="",val artist:String="",val artwork:String="",
    val playing:Boolean=false,val buffering:Boolean=false,val duration:Long=0,
    val shuffle:Boolean=false,val repeat:Int=0,val activeIndex:Int=-1,val queue:List<QueueRow> = emptyList(),
    val playWhenReady:Boolean=false,val error:String?=null)
data class SearchState(val busy:Boolean=false,val tracks:List<Track> = emptyList(),val error:String?=null,val warning:String?=null)
data class ReportState(val busy:Boolean=true,val report:ListeningReport?=null,val error:String?=null)
data class ProfileSaveState(val busy:Boolean=false,val error:String?=null,val revision:Long=0)
data class LyricsState(val trackId:String="",val loading:Boolean=false,val plain:String="",
    val lines:List<LrcLine> = emptyList(),val error:String?=null,val instrumental:Boolean=false,val source:String="")
