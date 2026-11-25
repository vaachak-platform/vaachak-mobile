package org.vaachak.reader.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.vaachak.reader.core.data.remote.CloudflareAiApi
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideCloudflareAiApi(client: OkHttpClient): CloudflareAiApi {
        return Retrofit.Builder()
            // Required Retrofit placeholder; @Url supplies the validated user-configured endpoint.
            .baseUrl("https://structure.placeholder/")
            // Never replay reading excerpts to redirect targets or downgrade TLS.
            .client(client.newBuilder().followRedirects(false).followSslRedirects(false).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudflareAiApi::class.java)
    }
}
