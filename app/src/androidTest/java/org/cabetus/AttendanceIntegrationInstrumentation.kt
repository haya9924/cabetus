package org.cabetus

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.CapturedDate
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ConfirmationWindow
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.Observation
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.ObservedCourse
import org.cabetus.data.classweb.ClassMobileAttendanceDomExtractor.SlotLabel
import org.cabetus.data.classweb.MobileAttendanceRepository
import org.cabetus.data.local.ClassCourseEntity
import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.data.local.TusDatabase
import org.cabetus.data.network.NetworkMonitor
import org.cabetus.data.network.NetworkStatus
import org.cabetus.data.network.NetworkTransport
import org.cabetus.domain.AttendanceResolver
import org.cabetus.domain.AttendanceSource
import org.cabetus.domain.AttendanceStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 実機/エミュレータで、実際の Room（インメモリ）と実際の [MobileAttendanceRepository] を
 * 組み合わせた出席連携の検証。androidx.test は使わず、標準の [Instrumentation] だけで動かす。
 *
 * アカウント・アプリ本体の DB・UI・Broadcast・ネットワーク送信は一切使わない。
 * NetworkMonitor はサービスを取得できない ContextWrapper で生成し、既知の接続済み
 * [NetworkStatus] をパラメータとして渡す（端末状態は読まない）。
 */
class AttendanceIntegrationInstrumentation : Instrumentation() {
    private var suite: String? = null

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite")
        start()
    }

    override fun onStart() {
        val outcome = runCatching {
            when (suite) {
                null -> runBlocking { runChecks() }
                "schedule-notices" -> ClassScheduleNoticesDeviceChecks.run(this)
                else -> error("Unknown instrumentation suite")
            }
        }
        outcome.fold(
            onSuccess = { evidence -> finish(Activity.RESULT_OK, evidence) },
            onFailure = { error -> finish(Activity.RESULT_CANCELED, stacktraceBundle(error)) },
        )
    }

    private suspend fun runChecks(): Bundle {
        val database = Room.inMemoryDatabaseBuilder(targetContext, TusDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val timetableDao = database.timetableDao()
            val classCourseDao = database.classCourseDao()
            val confirmations = database.mobileAttendanceConfirmationDao()

            val clock = AdjustableClock(FIRST_CAPTURED_AT)
            val networkMonitor = NetworkMonitor(object : ContextWrapper(targetContext) {
                override fun getSystemService(name: String): Any? = null
            })
            val repository = MobileAttendanceRepository(
                timetableDao = timetableDao,
                classCourseDao = classCourseDao,
                clock = clock,
                networkMonitor = networkMonitor,
                confirmations = confirmations,
            )

            timetableDao.insertAll(listOf(cell(courseCode = "C1", courseName = "数学", period = 1), cell(courseCode = "C2", courseName = "英語", period = 2)))
            classCourseDao.upsertAll(
                listOf(
                    ClassCourseEntity("C1", "数学", "", null, null),
                    ClassCourseEntity("C2", "英語", "", null, null),
                ),
            )

            // 事前: 書き込み前に古い読み取り（stale callback）として拒否され、何も保存されないこと。
            val stale = runCatching {
                repository.capture(observation(confirmedCourse(period = 1)), CONNECTED_WIFI) { false }
            }
            check(stale.isFailure) { "stale callback must be rejected" }
            check(confirmations.getForCourse("C1").isEmpty()) { "stale capture must not insert" }

            // 確定+受付中の読み取り。
            val first = repository.capture(observation(confirmedCourse(period = 1), pendingCourse(period = 2)), CONNECTED_WIFI)
            check(first.saved) { "capture must be saved" }
            check(first.confirmedCount == 1) { "confirmed=1 expected, was ${first.confirmedCount}" }
            check(first.pendingCount == 1) { "pending=1 expected, was ${first.pendingCount}" }

            val c1Rows = confirmations.getForCourse("C1")
            check(c1Rows.size == 1) { "C1 must have exactly one confirmation row" }
            check(c1Rows.single().period == 1) { "C1 row period must be 1" }
            // 日付キーは JST のその日 0:00。
            check(c1Rows.single().date == DAY_EPOCH_MS_JST) {
                "date key must be JST day start, was ${c1Rows.single().date}"
            }
            check(confirmations.getForCourse("C2").isEmpty()) { "pending must not be persisted" }
            check(c1Rows.single().confirmedAt == FIRST_CAPTURED_AT) { "confirmedAt must be first capture time" }

            // 時計を進めて同じ観測を再取り込み → IGNORE で元の確認時刻を保持する。
            clock.millisValue = SECOND_CAPTURED_AT
            val second = repository.capture(observation(confirmedCourse(period = 1), pendingCourse(period = 2)), CONNECTED_WIFI)
            check(second.saved) { "second capture must be saved" }
            val afterRepeat = confirmations.getForCourse("C1")
            check(afterRepeat.size == 1) { "repeat capture must not duplicate rows" }
            check(afterRepeat.single().confirmedAt == FIRST_CAPTURED_AT) {
                "repeat capture must keep original confirmedAt, was ${afterRepeat.single().confirmedAt}"
            }
            check(afterRepeat.single().date == DAY_EPOCH_MS_JST) { "repeat capture must keep date key" }

            // 受付中だけの読み取り + クリアでも、確認済みはちょうど 1 件残る。
            val pendingOnly = repository.capture(observation(pendingCourse(period = 2)), CONNECTED_WIFI)
            check(pendingOnly.saved) { "pending-only capture must be saved" }
            check(pendingOnly.pendingCount == 1) { "pending-only must report 1 pending" }
            repository.clear()
            val retained = confirmations.getForCourse("C1")
            check(retained.size == 1) { "clear must not delete confirmed rows" }
            check(retained.single().date == DAY_EPOCH_MS_JST) { "retained date key mismatch" }
            check(retained.single().confirmedAt == FIRST_CAPTURED_AT) { "retained confirmedAt mismatch" }
            check(confirmations.getForCourse("C2").isEmpty()) { "pending-only must not persist C2" }

            // 同じ厳密なキーだけモバイル登録が手動欠席より優先される。
            val keyRows = confirmations.getForCourse("C1")
            val sameKeyConfirmed = keyRows.any { it.courseCode == "C1" && it.date == DAY_EPOCH_MS_JST && it.period == 1 }
            val sameKey = AttendanceResolver.resolve(
                mark = "×",
                hasLocalCheck = true,
                overrideStatus = AttendanceStatus.ABSENT,
                hasMobileConfirmation = sameKeyConfirmed,
            )
            check(sameKey.status == AttendanceStatus.PRESENT) { "mobile must resolve PRESENT" }
            check(sameKey.source == AttendanceSource.MOBILE_REGISTRATION) { "mobile source expected" }

            val otherKey = AttendanceResolver.resolve(
                mark = "×",
                hasLocalCheck = true,
                overrideStatus = AttendanceStatus.ABSENT,
                hasMobileConfirmation = keyRows.any {
                    it.courseCode == "C1" && it.date == DAY_EPOCH_MS_JST && it.period == 2
                },
            )
            check(otherKey.status == AttendanceStatus.ABSENT) { "manual ABSENT must win for other key" }
            check(otherKey.source == AttendanceSource.MANUAL) { "manual source expected for other key" }

            val lowerSources = AttendanceResolver.resolve(
                mark = "〇",
                hasLocalCheck = true,
                overrideStatus = null,
                hasMobileConfirmation = sameKeyConfirmed,
            )
            check(lowerSources.status == AttendanceStatus.PRESENT) { "lower sources must not override mobile" }
            check(lowerSources.source == AttendanceSource.MOBILE_REGISTRATION) { "mobile must beat local+PDF" }

            return evidenceBundle(
                "ok=true",
                "confirmedCount=${first.confirmedCount}",
                "pendingCount=${first.pendingCount}",
                "rowDate=$DAY_EPOCH_MS_JST",
                "confirmedAt=${retained.single().confirmedAt}",
                "rowsAfterRepeat=${afterRepeat.size}",
                "retainedRows=${retained.size}",
                "sameKey=${sameKey.status}/${sameKey.source}",
                "otherKey=${otherKey.status}/${otherKey.source}",
            )
        } finally {
            database.close()
        }
    }

    private fun cell(courseCode: String, courseName: String, period: Int) = TimetableCellEntity(
        dayOfWeek = 1,
        period = period,
        courseCode = courseCode,
        courseName = courseName,
        instructor = "",
        room = null,
        isOnline = false,
        isMultiSession = false,
    )

    private fun confirmedCourse(period: Int) = ObservedCourse(
        courseName = if (period == 1) "数学" else "英語",
        slot = SlotLabel(1, listOf(period), "月$period"),
        successLabelVisible = true,
        date = PAGE_DATE,
        confirmationWindow = null,
        confirmationOpen = false,
        ambiguous = false,
    )

    private fun pendingCourse(period: Int) = ObservedCourse(
        courseName = if (period == 1) "数学" else "英語",
        slot = SlotLabel(1, listOf(period), "月$period"),
        successLabelVisible = false,
        date = PAGE_DATE,
        confirmationWindow = CONFIRMATION_WINDOW,
        confirmationOpen = true,
        ambiguous = false,
    )

    private fun observation(vararg courses: ObservedCourse) = Observation(
        titleLabel = "モバイル出席登録",
        pageDate = PAGE_DATE,
        campusNetworkLabelVisible = false,
        courses = courses.toList(),
        warnings = emptyList(),
    )

    private fun evidenceBundle(vararg lines: String): Bundle = Bundle().apply {
        putString("stream", "\n" + lines.joinToString("\n") + "\n")
    }

    private fun stacktraceBundle(error: Throwable): Bundle = evidenceBundle(
        "ok=false",
        error::class.java.name,
        error.message ?: "",
        error.stackTraceToString(),
    )

    /** テストから進められる固定/可変クロック。 */
    private class AdjustableClock(var millisValue: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(millisValue)
    }

    // 2026-07-06 は月曜日。
    private companion object {
        const val FIRST_CAPTURED_AT: Long = 1_800_000_000_000L
        const val SECOND_CAPTURED_AT: Long = FIRST_CAPTURED_AT + 3_600_000L

        val PAGE_DATE = CapturedDate(year = 2026, month = 7, day = 6, weekdayLabel = "月")
        val CONFIRMATION_WINDOW = ConfirmationWindow(LocalTime.of(10, 20), LocalTime.of(12, 0))
        val DAY_EPOCH_MS_JST: Long =
            LocalDate.of(2026, 7, 6).atStartOfDay(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()

        val CONNECTED_WIFI = NetworkStatus(
            isKnown = true,
            isConnected = true,
            hasInternetCapability = true,
            isValidated = true,
            isMetered = false,
            transport = NetworkTransport.WIFI,
        )
    }
}
