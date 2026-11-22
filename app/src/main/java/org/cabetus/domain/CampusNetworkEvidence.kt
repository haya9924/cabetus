package org.cabetus.domain

import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.network.NetworkStatus
import java.time.Instant
import java.time.ZoneId

/** Page-level CLASS evidence, independent of timetable matching. Never infer external. */
object CampusNetworkEvidence {
    fun label(snapshot: MobileAttendanceSnapshot?, network: NetworkStatus, nowMillis: Long, authRequired: Boolean): String? {
        snapshot ?: return null
        if (authRequired || !network.isKnown || !network.isConnected || !snapshot.networkKnown ||
            snapshot.transport != network.transport.name) return null
        val age = nowMillis - snapshot.capturedAtEpochMs
        if (age < 0 || age >= 15 * 60_000L) return null
        val zone = ZoneId.of("Asia/Tokyo")
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (snapshot.year != date.year || snapshot.month != date.monthValue || snapshot.day != date.dayOfMonth) return null
        val time = Instant.ofEpochMilli(snapshot.capturedAtEpochMs).atZone(zone)
        val scope = if (snapshot.campusNetworkLabelVisible) "学内ネットワーク" else "学内判定なし"
        return "%s · %02d:%02d 時点".format(scope, time.hour, time.minute)
    }
}
