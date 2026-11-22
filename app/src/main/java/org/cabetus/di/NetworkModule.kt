package org.cabetus.di

import android.content.Context
import org.cabetus.data.letus.WebViewCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @Named("userAgent")
    // Never initialize Chromium while holding a Hilt singleton lock. A worker can
    // wait for main here while main waits for the same OkHttp singleton: startup ANR.
    fun provideUserAgent(): String =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @Named("userAgent") userAgent: String,
    ): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(req)
        }
        .build()
}
