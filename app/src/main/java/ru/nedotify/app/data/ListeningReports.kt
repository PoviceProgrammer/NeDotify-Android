package ru.nedotify.app.data

import androidx.room.Embedded
import androidx.room.Relation
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

enum class ReportPeriod { WEEK, MONTH, ALL }

data class DailyListening(val date: LocalDate, val listenedMs: Long)
data class RankedTrack(@Embedded val track: Track, val listenedMs: Long, val sessions: Int)
data class RankedArtist(val name: String, val listenedMs: Long, val sessions: Int)
data class HistoryItem(
    @Embedded val session: ListeningSession,
    @Relation(parentColumn = "trackId", entityColumn = "id") val track: Track?,
)
data class ListeningReport(
    val totalMs: Long,
    val sessions: Int,
    val completedSessions: Int,
    val uniqueTracks: Int,
    val previousMs: Long?,
    val changePercent: Double?,
    val daily: List<DailyListening>,
    val topTracks: List<RankedTrack>,
    val topArtists: List<RankedArtist>,
    val history: List<HistoryItem>,
)

/** SQL projections contain aggregates, rather than every track or every history checkpoint. */
data class ReportTotals(val totalMs: Long, val sessions: Int, val completedSessions: Int, val uniqueTracks: Int)
data class ReportDaySum(val epochDay: Long, val listenedMs: Long)
data class ReportSnapshot(
    val totals: ReportTotals,
    val previousMs: Long?,
    val days: List<ReportDaySum>,
    val topTracks: List<RankedTrack>,
    val topArtists: List<RankedArtist>,
    val history: List<HistoryItem>,
)

data class ReportDay(val date: LocalDate, val startInclusive: Long)
data class ReportWindow(
    val startInclusive: Long,
    val endInclusive: Long,
    val previousStartInclusive: Long?,
    val previousEndInclusive: Long?,
    val days: List<ReportDay>,
) {
    fun includes(session: ListeningSession): Boolean =
        session.listenedMs > 0 && session.startedAt in startInclusive..endInclusive

    /**
     * SQLite on API 26 cannot apply an arbitrary IANA time zone. Bind explicit local-midnight
     * boundaries instead; even 365 days require fewer than SQLite's 999 bind parameters.
     * Whole sessions are attributed to their start day. Pauses and midnight crossings cannot
     * be reconstructed from a single measured session total, so no synthetic timeline is made.
     */
    fun dailyQuery(): SupportSQLiteQuery {
        val arguments = mutableListOf<Any>()
        val cases = days.asReversed().dropLast(1).joinToString(" ") { day ->
            arguments.add(day.startInclusive)
            arguments.add(day.date.toEpochDay())
            "WHEN startedAt >= ? THEN ?"
        }
        arguments.add(days.first().date.toEpochDay())
        arguments.add(days.first().startInclusive)
        arguments.add(endInclusive)
        return SimpleSQLiteQuery(
            "SELECT CASE $cases ELSE ? END AS epochDay, SUM(listenedMs) AS listenedMs " +
                "FROM history WHERE startedAt >= ? AND startedAt <= ? AND listenedMs > 0 " +
                "GROUP BY epochDay ORDER BY epochDay",
            arguments.toTypedArray(),
        )
    }
}

object ReportCalendar {
    fun window(period: ReportPeriod, now: Long, zone: ZoneId): ReportWindow {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dayCount = when (period) {
            ReportPeriod.WEEK -> 7
            ReportPeriod.MONTH -> 30
            // The all-time headline covers all history; its chart is intentionally bounded.
            ReportPeriod.ALL -> 365
        }
        val firstDate = today.minusDays((dayCount - 1).toLong())
        fun midnight(date: LocalDate): Long {
            val instant = date.atStartOfDay(zone).toInstant()
            return try { instant.toEpochMilli() } catch (_: ArithmeticException) {
                if (instant.isBefore(Instant.EPOCH)) Long.MIN_VALUE else Long.MAX_VALUE
            }
        }
        val days = List(dayCount) { index ->
            val date = firstDate.plusDays(index.toLong())
            ReportDay(date, midnight(date))
        }
        val start = if (period == ReportPeriod.ALL) Long.MIN_VALUE else days.first().startInclusive
        // Compare equal elapsed exposure. Today is partial, so N complete previous days
        // systematically understated an unchanged habit until the evening.
        val previousEnd=if(period==ReportPeriod.ALL || start==Long.MIN_VALUE)null else start-1
        val previousStart=previousEnd?.let { end->
            val elapsed=(now-start).coerceAtLeast(0)
            if(end<Long.MIN_VALUE+elapsed)Long.MIN_VALUE else end-elapsed
        }
        return ReportWindow(
            startInclusive = start,
            endInclusive = now,
            previousStartInclusive = previousStart,
            previousEndInclusive = previousEnd,
            days = days,
        )
    }

    fun percentage(currentMs: Long, previousMs: Long?): Double? =
        if (previousMs == null || previousMs <= 0) null
        else ((currentMs.toDouble() - previousMs.toDouble()) / previousMs.toDouble()) * 100.0

    fun report(window: ReportWindow, snapshot: ReportSnapshot): ListeningReport {
        val measuredDays = snapshot.days.associate { it.epochDay to it.listenedMs }
        return ListeningReport(
            totalMs = snapshot.totals.totalMs,
            sessions = snapshot.totals.sessions,
            completedSessions = snapshot.totals.completedSessions,
            uniqueTracks = snapshot.totals.uniqueTracks,
            previousMs = snapshot.previousMs,
            changePercent = percentage(snapshot.totals.totalMs, snapshot.previousMs),
            daily = window.days.map { DailyListening(it.date, measuredDays[it.date.toEpochDay()] ?: 0) },
            topTracks = snapshot.topTracks,
            topArtists = snapshot.topArtists,
            history = snapshot.history.filter { window.includes(it.session) },
        )
    }
}

class ListeningReports(private val dao: LibraryDao) {
    /**
     * [now] is a fixed upper bound for this subscription, including exclusion of future rows.
     * The caller should restart collection when its clock/day changes. Room invalidates the
     * signal for history checkpoints and metadata edits; aggregates are read in one transaction.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(period: ReportPeriod, now: Long, zone: ZoneId = ZoneId.systemDefault()): Flow<ListeningReport> {
        val window = ReportCalendar.window(period, now, zone)
        return dao.observeReportChanges(SimpleSQLiteQuery("SELECT 1"))
            .mapLatest { ReportCalendar.report(window, dao.reportSnapshot(window)) }
            .flowOn(Dispatchers.IO)
    }
}
