package ru.nedotify.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ListeningReportsTest {
    private val utc = ZoneId.of("UTC")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private val now = time("2026-10-18T12:00:00Z")
    private fun snapshot(
        total: Long = 0,
        previous: Long? = null,
        days: List<ReportDaySum> = emptyList(),
        tracks: List<RankedTrack> = emptyList(),
        artists: List<RankedArtist> = emptyList(),
        history: List<HistoryItem> = emptyList(),
    ) = ReportSnapshot(ReportTotals(total, history.size, history.count { it.session.completed },
        history.map { it.session.trackId }.distinct().size), previous, days, tracks, artists, history)

    @Test fun weekIncludesTodayAndSixPreviousLocalDatesWithDisjointComparison() {
        val window = ReportCalendar.window(ReportPeriod.WEEK, now, utc)
        assertEquals(LocalDate.of(2026, 10, 12), window.days.first().date)
        assertEquals(LocalDate.of(2026, 10, 18), window.days.last().date)
        assertEquals(7, window.days.size)
        assertEquals(time("2026-10-12T00:00:00Z"), window.startInclusive)
        assertEquals(time("2026-10-05T11:59:59.999Z"), window.previousStartInclusive)
        assertEquals(window.startInclusive - 1, window.previousEndInclusive)
        assertEquals(now, window.endInclusive)
    }

    @Test fun monthMeansThirtyCalendarDaysIncludingLeapDay() {
        val window = ReportCalendar.window(ReportPeriod.MONTH, time("2024-03-01T10:00:00Z"), utc)
        assertEquals(30, window.days.size)
        assertEquals(LocalDate.of(2024, 2, 1), window.days.first().date)
        assertTrue(window.days.any { it.date == LocalDate.of(2024, 2, 29) })
        assertEquals(time("2024-01-02T13:59:59.999Z"), window.previousStartInclusive)
    }

    @Test fun springDayHasTwentyThreeHoursAndAutumnDayHasTwentyFive() {
        val zone = ZoneId.of("Europe/Berlin")
        fun dayLength(instant: String, date: LocalDate): Long {
            val days = ReportCalendar.window(ReportPeriod.WEEK, time(instant), zone).days
            val index = days.indexOfFirst { it.date == date }
            return days[index + 1].startInclusive - days[index].startInclusive
        }
        assertEquals(23 * 3_600_000L, dayLength("2026-03-30T12:00:00Z", LocalDate.of(2026, 3, 29)))
        assertEquals(25 * 3_600_000L, dayLength("2026-10-26T12:00:00Z", LocalDate.of(2026, 10, 25)))
    }

    @Test fun zoneRatherThanUtcDeterminesToday() {
        val instant = time("2026-10-18T01:00:00Z")
        val la = ReportCalendar.window(ReportPeriod.WEEK, instant, ZoneId.of("America/Los_Angeles"))
        val tokyo = ReportCalendar.window(ReportPeriod.WEEK, instant, ZoneId.of("Asia/Tokyo"))
        assertEquals(LocalDate.of(2026, 10, 17), la.days.last().date)
        assertEquals(LocalDate.of(2026, 10, 18), tokyo.days.last().date)
        assertEquals(instant, la.endInclusive)
    }

    @Test fun allTimeTotalsAreNotLimitedToTheBoundedChart() {
        val window = ReportCalendar.window(ReportPeriod.ALL, now, utc)
        assertEquals(Long.MIN_VALUE, window.startInclusive)
        assertEquals(365, window.days.size)
        assertNull(window.previousStartInclusive)
        assertNull(window.previousEndInclusive)
        val report = ReportCalendar.report(window, snapshot(total = 123_456))
        assertEquals(123_456L, report.totalMs)
        assertEquals(365, report.daily.size)
        assertEquals(0L, report.daily.sumOf { it.listenedMs })
        assertNull(report.previousMs)
        assertNull(report.changePercent)
    }

    @Test fun chartFillsMissingDatesAndRetainsMeasuredTotals() {
        val window = ReportCalendar.window(ReportPeriod.WEEK, now, utc)
        val day = window.days[2].date
        val report = ReportCalendar.report(window, snapshot(total = 2_500,
            days = listOf(ReportDaySum(day.toEpochDay(), 2_500))))
        assertEquals(7, report.daily.size)
        assertEquals(6, report.daily.count { it.listenedMs == 0L })
        assertEquals(DailyListening(day, 2_500), report.daily[2])
        assertEquals(2_500L, report.daily.sumOf { it.listenedMs })
    }

    @Test fun comparisonNeverInventsGrowthAgainstAnEmptyBaseline() {
        assertNull(ReportCalendar.percentage(100, null))
        assertNull(ReportCalendar.percentage(100, 0))
        assertNull(ReportCalendar.percentage(0, 0))
        assertEquals(-100.0, ReportCalendar.percentage(0, 100)!!, 0.0)
        assertEquals(50.0, ReportCalendar.percentage(150, 100)!!, 0.0)
        assertEquals(0.0, ReportCalendar.percentage(Long.MAX_VALUE, Long.MAX_VALUE)!!, 0.0)
    }

    @Test fun futureZeroNegativeAndBeforePeriodSessionsDoNotBecomeHistory() {
        val window = ReportCalendar.window(ReportPeriod.WEEK, now, utc)
        fun item(id: String, start: Long, measured: Long) =
            HistoryItem(ListeningSession(id, "missing", start, measured, false), null)
        val valid = item("valid", now, 2_000)
        val boundary = item("boundary", window.startInclusive, 1)
        val history = listOf(valid, boundary, item("future", now + 1, 4_000),
            item("zero", now, 0), item("negative", now, -100), item("old", window.startInclusive - 1, 1_000))
        val report = ReportCalendar.report(window, snapshot(history = history))
        assertEquals(listOf("valid", "boundary"), report.history.map { it.session.id })
        assertTrue(report.history.all { it.track == null })
    }

    @Test fun stableTrackIdentityAndMeasuredValuesSurviveIdenticalTitlesAndMissingMetadata() {
        val first = Track("a", "Совпадающее имя", durationMs = 900_000, source = "local")
        val second = first.copy(id = "b", artist = "Другой исполнитель")
        val ranking = listOf(RankedTrack(first, 2_000, 1), RankedTrack(second, 1_000, 1))
        val history = listOf(HistoryItem(ListeningSession("lost", "deleted", now, 1_000, false), null))
        val report = ReportCalendar.report(ReportCalendar.window(ReportPeriod.WEEK, now, utc),
            snapshot(total = 4_000, tracks = ranking, artists = listOf(RankedArtist("Исполнитель", 4_000, 3)), history = history))
        assertEquals(4_000L, report.totalMs)
        assertEquals(listOf("a", "b"), report.topTracks.map { it.track.id })
        assertEquals(listOf(2_000L, 1_000L), report.topTracks.map { it.listenedMs })
        assertEquals("deleted", report.history.single().session.trackId)
        assertNull(report.history.single().track)
    }

    @Test fun aSkippedCalendarDayIsRepresentedWithoutInventingListening() {
        val window = ReportCalendar.window(ReportPeriod.WEEK, time("2011-12-31T12:00:00Z"), ZoneId.of("Pacific/Apia"))
        val skipped = window.days.single { it.date == LocalDate.of(2011, 12, 30) }
        val next = window.days.single { it.date == LocalDate.of(2011, 12, 31) }
        assertEquals(skipped.startInclusive, next.startInclusive)
        val report = ReportCalendar.report(window, snapshot())
        assertEquals(0L, report.daily.single { it.date == skipped.date }.listenedMs)
    }

    @Test fun comparisonExposureIsEqualAtMidnightNoonAndAcrossDst() {
        for(zone in listOf(utc,ZoneId.of("Europe/Berlin")))for(instant in listOf(
            "2026-10-18T00:00:00Z","2026-10-18T12:00:00Z","2026-10-25T23:59:59Z","2026-03-29T12:00:00Z")) {
            val window=ReportCalendar.window(ReportPeriod.WEEK,time(instant),zone)
            assertEquals(window.endInclusive-window.startInclusive,window.previousEndInclusive!!-window.previousStartInclusive!!)
            assertTrue(window.previousEndInclusive<window.startInclusive)
        }
    }

    @Test fun artistRankingGroupsUnicodeCaseAndExcludesUnknownNames() {
        val ranked=normalizeReportArtists(listOf(RankedArtist("The Weeknd",100,1),RankedArtist(" the weeknd ",200,2),
            RankedArtist("Сплин",100,1),RankedArtist("СПЛИН",300,3),RankedArtist("Unknown Artist",999,9),
            RankedArtist("Неизвестный исполнитель",999,9)))
        assertEquals(listOf(400L,300L),ranked.map {it.listenedMs})
        assertEquals(listOf(4,3),ranked.map {it.sessions})
    }
}
