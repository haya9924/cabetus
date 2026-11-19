package org.cabetus

import org.cabetus.data.classweb.MobileAttendanceSnapshot
import org.cabetus.data.network.NetworkStatus
import org.cabetus.data.network.NetworkTransport
import org.cabetus.domain.CampusNetworkEvidence
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CampusNetworkEvidenceTest {
    private val now = Instant.parse("2026-11-16T08:00:00Z").toEpochMilli()
    private val network = NetworkStatus(isKnown = true, isConnected = true, transport = NetworkTransport.WIFI)
    private val snapshot = MobileAttendanceSnapshot(capturedAtEpochMs = now, year = 2026, month = 11, day = 16,
        transport = NetworkTransport.WIFI.name, networkKnown = true, campusNetworkLabelVisible = true)
    @Test fun campusIsExplicitAndIndependentOfCourse() {
        assertTrue(CampusNetworkEvidence.label(snapshot, network, now, false)!!.startsWith("学内ネットワーク"))
        assertTrue(CampusNetworkEvidence.label(snapshot.copy(campusNetworkLabelVisible = false), network, now, false)!!.startsWith("学内判定なし"))
    }
    @Test fun invalidEvidenceIsSuppressed() {
        assertNull(CampusNetworkEvidence.label(snapshot, network, now, true))
        assertNull(CampusNetworkEvidence.label(snapshot, network, now + 900_000, false))
        assertNull(CampusNetworkEvidence.label(snapshot, network, now - 1, false))
        assertNull(CampusNetworkEvidence.label(snapshot.copy(day = 15), network, now, false))
        assertNull(CampusNetworkEvidence.label(snapshot, network.copy(isConnected = false), now, false))
        assertNull(CampusNetworkEvidence.label(snapshot.copy(transport = "CELLULAR"), network, now, false))
    }
}
