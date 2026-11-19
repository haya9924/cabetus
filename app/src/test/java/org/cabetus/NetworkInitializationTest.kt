package org.cabetus

import org.cabetus.di.NetworkModule
import org.cabetus.data.letus.WebViewCookieJar
import org.junit.Assert.*
import org.junit.Test

/** Android stubs throw if browser APIs are touched: DI must work without a main looper. */
class NetworkInitializationTest {
    @Test fun userAgentAndClientConstructionNeverInitializeWebView() {
        val agent = NetworkModule.provideUserAgent()
        assertTrue(agent.contains("Mozilla/5.0"))
        val client = NetworkModule.provideOkHttpClient(agent)
        assertNotNull(client)
    }
    @Test fun cookieJarConstructionDoesNotTouchCookieManager() {
        assertNotNull(WebViewCookieJar())
    }
}
