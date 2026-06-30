package com.remotemonitor.watch.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Factory for the Retrofit-backed [MeasurementsApi] (REQ-WATCH-10, REQ-WATCH-11,
 * T-WATCH-22).
 *
 * - [baseUrl] is supplied by `BuildConfig.API_BASE_URL` (debug: emulator
 *   host loopback, release: production placeholder).
 * - [debug] controls whether [HttpLoggingInterceptor] is added to the
 *   OkHttpClient. `BASIC` level keeps the log volume low on a Wear OS
 *   device (no body logging).
 *
 * The Moshi converter is configured with [KotlinJsonAdapterFactory] so the
 * data classes (`MeasurementEntity`, `BatchResponse`, etc.) can be
 * (de)serialized via reflection. For production, moshi-kotlin-codegen
 * (KSP-based) would be faster on cold start, but it is not required by
 * REQ-WATCH-10/11 for the PoC.
 */
object ApiClient {

    fun create(baseUrl: String, debug: Boolean): MeasurementsApi {
        val okHttpBuilder = OkHttpClient.Builder()
        if (debug) {
            okHttpBuilder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        val moshi = Moshi.Builder()
            // Field-scoped epoch-ms ↔ ISO 8601 bridge for
            // MeasurementEntity.timestamp (T-FIX-02, REQ-WATCH-54).
            // The `add(Object)` form lets Moshi introspect the
            // @FromJson/@ToJson methods and route only the
            // @Iso8601Timestamp-qualified fields through this adapter.
            .add(Iso8601TimestampAdapter())
            .add(KotlinJsonAdapterFactory())
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpBuilder.build())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MeasurementsApi::class.java)
    }
}
