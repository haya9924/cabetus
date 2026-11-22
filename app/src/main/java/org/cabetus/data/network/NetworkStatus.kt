package org.cabetus.data.network

/** 端末が現在使っているネットワークの種類。 */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER,
    NONE,
    ;

    companion object {
        /**
         * NetworkCapabilities の transport 有無から代表種別を決める。
         * VPN は実際の経路を隠すため最優先。有線 > Wi-Fi > モバイルの順で代表させる。
         */
        fun classify(
            hasWifi: Boolean,
            hasCellular: Boolean,
            hasEthernet: Boolean,
            hasVpn: Boolean,
            hasBluetooth: Boolean,
        ): NetworkTransport = when {
            hasVpn -> VPN
            hasEthernet -> ETHERNET
            hasWifi -> WIFI
            hasCellular -> CELLULAR
            hasBluetooth -> BLUETOOTH
            else -> OTHER
        }
    }
}

/**
 * 端末のネットワーク状態。
 *
 * [isValidated] は Android の NET_CAPABILITY_VALIDATED（インターネット到達性を
 * システムが検証済み）であり、CLASS への到達・ログインを保証するものではない。
 */
data class NetworkStatus(
    /** 状態をまだ取得できていない（初回など）。 */
    val isKnown: Boolean = false,
    val isConnected: Boolean = false,
    val hasInternetCapability: Boolean = false,
    val isValidated: Boolean = false,
    val isMetered: Boolean = false,
    val transport: NetworkTransport = NetworkTransport.NONE,
) {
    companion object {
        val Unknown = NetworkStatus()
    }
}

/** ネットワーク状態を UI 向けの日本語ラベルへ変換する純粋ロジック。 */
object NetworkStatusFormatter {

    /**
     * 学内/学外の判定は未確認であることを示すラベル。
     * 端末の Wi-Fi / モバイル種別から学内・学外を推測してはならない。
     */
    const val CAMPUS_SCOPE_UNKNOWN_LABEL = "学内/学外: 未確認"

    /** ラベルの隣に出せる短い補足。 */
    const val CAMPUS_SCOPE_UNKNOWN_HINT = "出席サイト側の判定は未確認"

    /** 二次詳細に置く説明。 */
    const val CAMPUS_SCOPE_NOTE =
        "学内/学外は端末の通信種別（Wi-Fi/モバイル）からは判定しません。" +
            "モバイル出席サイト側の判定を確認する必要があります。"

    const val CLASS_CONNECTION_CAVEAT =
        "端末のネットワーク状態の表示です。CLASSへの接続・ログイン状態を保証するものではありません。"

    /**
     * 学内/学外は未確認。端末の通信種別から推測しないため、状態によらず固定値。
     */
    fun campusScopeLabel(): String = CAMPUS_SCOPE_UNKNOWN_LABEL

    fun campusScopeHint(): String = CAMPUS_SCOPE_UNKNOWN_HINT

    fun transportLabel(status: NetworkStatus): String = when (status.transport) {
        NetworkTransport.WIFI -> "Wi-Fi"
        NetworkTransport.CELLULAR -> "モバイルデータ"
        NetworkTransport.ETHERNET -> "有線LAN"
        NetworkTransport.VPN -> "VPN"
        NetworkTransport.BLUETOOTH -> "Bluetooth"
        NetworkTransport.OTHER -> "その他"
        NetworkTransport.NONE -> "なし"
    }

    /** validated と種類を別々に判断できるよう、状態だけを返す。 */
    fun stateLabel(status: NetworkStatus): String = when {
        !status.isKnown -> "確認中…"
        !status.isConnected -> "オフライン"
        !status.hasInternetCapability -> "接続済み（インターネット未対応）"
        status.isValidated -> "オンライン（検証済み）"
        else -> "接続中（未検証）"
    }

    /** カードに1行で出す短い接続状態。 */
    fun summaryLabel(status: NetworkStatus): String = when {
        !status.isKnown -> "接続: 確認中"
        !status.isConnected -> "接続: オフライン"
        !status.hasInternetCapability -> "接続: ${transportLabel(status)}（ネット未対応）"
        status.isValidated -> "接続: ${transportLabel(status)}"
        else -> "接続: ${transportLabel(status)}（未検証）"
    }

    /** 種類 + 従量制かどうか。オフライン時は種類なし。 */
    fun typeLabel(status: NetworkStatus): String {
        if (!status.isKnown) return "種類: 不明"
        if (!status.isConnected) return "種類: なし"
        val type = transportLabel(status)
        return if (status.isMetered) "種類: $type（従量制）" else "種類: $type"
    }}
