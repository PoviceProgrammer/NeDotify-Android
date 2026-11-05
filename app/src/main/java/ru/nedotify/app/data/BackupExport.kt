package ru.nedotify.app.data

data class PreparedBackupExport(val backup:LibraryBackup,val droppedSessions:Int) {
    val summary get()=backup.summary.copy(droppedSessions=droppedSessions)
}

/** Bound the portable history, keeping the complete history in Room. Never delete listening data. */
object BackupExport {
    fun prepare(backup:LibraryBackup,totalSessions:Int=backup.history.size):PreparedBackupExport {
        val newest=backup.history.sortedWith(compareByDescending<ListeningSession> {it.startedAt}.thenByDescending {it.id})
            .take(BackupCodec.MAX_SESSIONS)
        val portable=try {BackupCodec.portable(backup.copy(history=newest))}
            catch(error:BackupFormatException) {throw SourceFailure("Не удалось сохранить копию: ${error.reason}")}
        // The document is measured once. The tracks and the playlists do not change while the
        // newest sessions are fitted into the byte budget, and the history is the only part
        // that grows: measuring the whole document again for every step of the search cost a
        // pass over every track and every playlist per step.
        val other=BackupCodec.encodedSize(portable.copy(history=emptyList()))-2
        val sizes=BackupCodec.historyPrefixSizes(portable.history)
        if(other+sizes[portable.history.size]<=BackupCodec.MAX_BYTES)
            return PreparedBackupExport(portable,(totalSessions-newest.size).coerceAtLeast(0))
        if(other>BackupCodec.MAX_BYTES)
            throw SourceFailure("Не удалось сохранить копию: метаданные треков и плейлистов превышают предел формата 16 МБ")
        var low=0;var high=newest.size
        while(low<high) {
            val middle=(low+high+1)/2
            if(other+sizes[middle]<=BackupCodec.MAX_BYTES)low=middle
            else high=middle-1
        }
        return PreparedBackupExport(portable.copy(history=portable.history.take(low)),(totalSessions-low).coerceAtLeast(0))
    }
}
