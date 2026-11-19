package org.cabetus

import org.cabetus.data.network.NetworkStatus
import org.cabetus.data.network.NetworkStatusFormatter
import org.cabetus.data.network.NetworkTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusFormatterTest {

    @Test
    fun unknown_status_is_reported_as_checking() {
        val status = NetworkStatus.Unknown
        assertEquals("確認中…", NetworkStatusFormatter.stateLabel(status))
        assertEquals("種類: 不明", NetworkStatusFormatter.typeLabel(status))
    }

    @Test
    fun disconnected_is_offline_without_type() {
        val status = NetworkStatus(isKnown = true, isConnected = false)
        assertEquals("オフライン", NetworkStatusFormatter.stateLabel(status))
        assertEquals("種類: なし", NetworkStatusFormatter.typeLabel(status))
    }

    @Test
    fun connected_but_not_validated_is_distinguished_from_validated() {
        val notValidated = NetworkStatus(
            isKnown = true,
            isConnected = true,
            hasInternetCapability = true,
            isValidated = false,
            transport = NetworkTransport.WIFI,
        )
        assertEquals("接続中（未検証）", NetworkStatusFormatter.stateLabel(notValidated))
        assertEquals("種類: Wi-Fi", NetworkStatusFormatter.typeLabel(notValidated))

        val validated = notValidated.copy(isValidated = true)
        assertEquals("オンライン（検証済み）", NetworkStatusFormatter.stateLabel(validated))
        assertEquals("種類: Wi-Fi", NetworkStatusFormatter.typeLabel(validated))
    }

    @Test
    fun validated_without_internet_capability_is_not_called_online() {
        val status = NetworkStatus(
            isKnown = true,
            isConnected = true,
            hasInternetCapability = false,
            isValidated = true,
            transport = NetworkTransport.ETHERNET,
        )
        assertEquals("接続済み（インターネット未対応）", NetworkStatusFormatter.stateLabel(status))
    }

    @Test
    fun transport_type_and_metered_are_separate_from_validated() {
        val cellular = NetworkStatus(
            isKnown = true,
            isConnected = true,
            hasInternetCapability = true,
            isValidated = true,
            isMetered = true,
            transport = NetworkTransport.CELLULAR,
        )
        assertEquals("種類: モバイルデータ（従量制）", NetworkStatusFormatter.typeLabel(cellular))
        assertEquals("オンライン（検証済み）", NetworkStatusFormatter.stateLabel(cellular))
    }

    @Test
    fun each_transport_has_its_own_label() {
        assertEquals("Wi-Fi", NetworkStatusFormatter.transportLabel(status(NetworkTransport.WIFI)))
        assertEquals("モバイルデータ", NetworkStatusFormatter.transportLabel(status(NetworkTransport.CELLULAR)))
        assertEquals("有線LAN", NetworkStatusFormatter.transportLabel(status(NetworkTransport.ETHERNET)))
        assertEquals("VPN", NetworkStatusFormatter.transportLabel(status(NetworkTransport.VPN)))
        assertEquals("Bluetooth", NetworkStatusFormatter.transportLabel(status(NetworkTransport.BLUETOOTH)))
        assertEquals("その他", NetworkStatusFormatter.transportLabel(status(NetworkTransport.OTHER)))
        assertEquals("なし", NetworkStatusFormatter.transportLabel(status(NetworkTransport.NONE)))
    }

    @Test
    fun classify_prefers_vpn_then_ethernet_then_wifi_then_cellular() {
        assertEquals(
            NetworkTransport.VPN,
            NetworkTransport.classify(
                hasWifi = true, hasCellular = true, hasEthernet = true,
                hasVpn = true, hasBluetooth = true,
            ),
        )
        assertEquals(
            NetworkTransport.ETHERNET,
            NetworkTransport.classify(
                hasWifi = true, hasCellular = true, hasEthernet = true,
                hasVpn = false, hasBluetooth = true,
            ),
        )
        assertEquals(
            NetworkTransport.WIFI,
            NetworkTransport.classify(
                hasWifi = true, hasCellular = true, hasEthernet = false,
                hasVpn = false, hasBluetooth = false,
            ),
        )
        assertEquals(
            NetworkTransport.CELLULAR,
            NetworkTransport.classify(
                hasWifi = false, hasCellular = true, hasEthernet = false,
                hasVpn = false, hasBluetooth = false,
            ),
        )
        assertEquals(
            NetworkTransport.BLUETOOTH,
            NetworkTransport.classify(
                hasWifi = false, hasCellular = false, hasEthernet = false,
                hasVpn = false, hasBluetooth = true,
            ),
        )
        assertEquals(
            NetworkTransport.OTHER,
            NetworkTransport.classify(
                hasWifi = false, hasCellular = false, hasEthernet = false,
                hasVpn = false, hasBluetooth = false,
            ),
        )
    }

    @Test
    fun caveat_does_not_promise_class_connectivity() {
        val caveat = NetworkStatusFormatter.CLASS_CONNECTION_CAVEAT
        assertTrue(caveat.contains("CLASS"))
        assertTrue(caveat.contains("保証"))
    }

    @Test
    fun campus_scope_is_always_unknown_and_never_inferred_from_transport() {
        // Wi-Fi/モバイル/有線/VPN のどれでも学内・学外を推測しない。
        NetworkTransport.entries.forEach { transport ->
            assertEquals("学内/学外: 未確認", NetworkStatusFormatter.campusScopeLabel())
            assertEquals("学内/学外: 未確認", NetworkStatusFormatter.CAMPUS_SCOPE_UNKNOWN_LABEL)
            assertTrue(NetworkStatusFormatter.campusScopeHint().isNotBlank())
            // 通信種別のラベルに学内/学外の推測が混ざらない。
            assertFalse(NetworkStatusFormatter.transportLabel(status(transport)).contains("学内"))
            assertFalse(NetworkStatusFormatter.summaryLabel(status(transport)).contains("学内"))
        }
    }

    @Test
    fun campus_scope_note_points_to_class_side_verification() {
        val note = NetworkStatusFormatter.CAMPUS_SCOPE_NOTE
        assertTrue(note.contains("学内/学外"))
        assertTrue(note.contains("出席サイト"))
        assertTrue(note.contains("Wi-Fi"))
    }

    @Test
    fun summary_label_is_short_and_includes_transport_when_connected() {
        val validatedWifi = NetworkStatus(
            isKnown = true,
            isConnected = true,
            hasInternetCapability = true,
            isValidated = true,
            transport = NetworkTransport.WIFI,
        )
        assertEquals("接続: Wi-Fi", NetworkStatusFormatter.summaryLabel(validatedWifi))
        assertEquals(
            "接続: モバイルデータ（未検証）",
            NetworkStatusFormatter.summaryLabel(
                validatedWifi.copy(isValidated = false, transport = NetworkTransport.CELLULAR),
            ),
        )
        assertEquals("接続: 確認中", NetworkStatusFormatter.summaryLabel(NetworkStatus.Unknown))
        assertEquals(
            "接続: オフライン",
            NetworkStatusFormatter.summaryLabel(NetworkStatus(isKnown = true, isConnected = false)),
        )
        assertEquals(
            "接続: Wi-Fi（ネット未対応）",
            NetworkStatusFormatter.summaryLabel(validatedWifi.copy(hasInternetCapability = false)),
        )
    }

    private fun status(transport: NetworkTransport) = NetworkStatus(
        isKnown = true,
        isConnected = true,
        hasInternetCapability = true,
        isValidated = true,
        transport = transport,
    )
}
