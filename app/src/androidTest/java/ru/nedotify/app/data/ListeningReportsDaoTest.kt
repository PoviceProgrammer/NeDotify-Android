package ru.nedotify.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningReportsDaoTest {
    private lateinit var database: LibraryDatabase
    private val now = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    @Before fun create() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, LibraryDatabase::class.java).build()
    }
    @After fun close() { database.close() }

    @Test fun measuredAggregationExcludesFutureAndZeroKeepsOrphansAndPeriodBoundaries() = runBlocking {
        val dao = database.library()
        val a = Track("a", "Одинаковое название", artist = "Артист", durationMs = 900_000, source = "local")
        val b = a.copy(id = "b")
        dao.putAll(listOf(a, b))
        val window = ReportCalendar.window(ReportPeriod.WEEK, now, zone)
        listOf(
            ListeningSession("first", a.id, window.startInclusive, 2_000, true),
            ListeningSession("second", b.id, now - 1, 1_000, false),
            ListeningSession("orphan", "gone", now, 500, false),
            ListeningSession("previous", a.id, window.startInclusive - 1, 1_000, true),
            ListeningSession("future", a.id, now + 1, 99_000, true),
            ListeningSession("zero", b.id, now - 2, 0, true),
            ListeningSession("negative", b.id, now - 3, -100, false),
        ).forEach { dao.insertSession(it) }
        val report = ListeningReports(dao).observe(ReportPeriod.WEEK, now, zone).first()
        assertEquals(3_500L, report.totalMs)
        assertEquals(3, report.sessions)
        assertEquals(1, report.completedSessions)
        assertEquals(3, report.uniqueTracks)
        assertEquals(1_000L, report.previousMs)
        assertEquals(250.0, report.changePercent!!, 0.0)
        assertEquals(2_000L, report.daily.first().listenedMs)
        assertEquals(1_500L, report.daily.last().listenedMs)
        assertEquals(listOf("a", "b"), report.topTracks.map { it.track.id })
        assertEquals(RankedArtist("Артист", 3_000, 2), report.topArtists.first())
        assertEquals(listOf("orphan", "second", "first"), report.history.map { it.session.id })
        assertNull(report.history.first().track)
    }

    @Test fun checkpointsAndTrackMetadataUpdateAnAlreadyCollectedReport() = runBlocking {
        val dao = database.library()
        val a = Track("a", "До изменения", source = "local")
        dao.put(a)
        val channel = Channel<ListeningReport>(Channel.UNLIMITED)
        val job = launch { ListeningReports(dao).observe(ReportPeriod.WEEK, now, zone).collect { channel.send(it) } }
        suspend fun nextWhere(predicate: (ListeningReport) -> Boolean): ListeningReport = withTimeout(5_000) {
            var report = channel.receive()
            while (!predicate(report)) report = channel.receive()
            report
        }
        try {
            nextWhere { it.totalMs == 0L }
            dao.putSession(ListeningSession("s", "a", now, 1_000, false))
            nextWhere { it.totalMs == 1_000L }
            dao.putSession(ListeningSession("s", "a", now, 2_500, true))
            val updated = nextWhere { it.totalMs == 2_500L }
            assertEquals(1, updated.sessions)
            assertEquals(1, updated.completedSessions)
            dao.put(a.copy(title = "После изменения"))
            nextWhere { it.topTracks.firstOrNull()?.track?.title == "После изменения" }
        } finally { job.cancel(); channel.close() }
    }

    @Test fun allTimeDailyChartUsesZoneBoundariesWithoutDiscardingOlderTotalsAndBoundsHistory() = runBlocking {
        val dao = database.library()
        val zone = ZoneId.of("Europe/Berlin")
        val instant = Instant.parse("2026-03-30T12:00:00Z").toEpochMilli()
        val track = Track("a", "Трек", source = "local")
        dao.put(track)
        val window = ReportCalendar.window(ReportPeriod.ALL, instant, zone)
        dao.putSession(ListeningSession("older", track.id, window.days.first().startInclusive - 1, 7_000, false))
        val march29 = window.days.single { it.date.toString() == "2026-03-29" }.startInclusive
        // 22:30 UTC is March 30 locally: the spring transition day was only 23 hours.
        dao.putSession(ListeningSession("before", track.id, march29 + 22 * 3_600_000L, 3_000, true))
        dao.putSession(ListeningSession("after", track.id, Instant.parse("2026-03-29T22:30:00Z").toEpochMilli(), 2_000, false))
        repeat(110) { dao.putSession(ListeningSession("recent:$it", track.id, instant - it, 1, false)) }
        val report = ListeningReports(dao).observe(ReportPeriod.ALL, instant, zone).first()
        assertEquals(12_110L, report.totalMs)
        assertEquals(5_110L, report.daily.sumOf { it.listenedMs })
        assertEquals(3_000L, report.daily.single { it.date.toString() == "2026-03-29" }.listenedMs)
        assertEquals(2_110L, report.daily.single { it.date.toString() == "2026-03-30" }.listenedMs)
        assertEquals(100, report.history.size)
        assertNull(report.previousMs)
    }
}
