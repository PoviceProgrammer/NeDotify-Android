package ru.nedotify.app.data

import java.util.Locale

/** SQLite's LOWER is ASCII-only on Android; normalise Unicode names outside SQL. */
internal fun normalizeReportArtists(rows:List<RankedArtist>):List<RankedArtist> =
    rows.groupBy {it.name.trim().lowercase(Locale.ROOT)}
        .filterKeys {it !in setOf("","unknown artist","unknown","неизвестный исполнитель","<unknown>")}
        .map {(_,group)->RankedArtist(group.map {it.name.trim()}.minOrNull().orEmpty(),group.sumOf {it.listenedMs},group.sumOf {it.sessions})}
        .sortedWith(compareByDescending<RankedArtist>{it.listenedMs}.thenBy {it.name.lowercase(Locale.ROOT)}.thenBy {it.name}).take(10)
