package com.pittech.billing

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CommunityBenchmarksFailure(val status: Int = 0) : Exception(
    when (status) {
        401 -> "Sign in again to see community benchmarks. Your local cookbook is unchanged."
        403 -> "Community benchmarks need PitTech Pro on this account."
        404 -> "Community benchmarks aren't available on this server yet. Your local cookbook is unchanged."
        else -> "Community benchmarks are unavailable right now. Your local cookbook is unchanged."
    },
)

internal interface CommunityBenchmarksAPI {
    suspend fun aggregates(bucket: String?, metric: String?, runId: String?, access: String?): JSONObject
    suspend fun jobRuns(limit: Int?, cursor: String?, access: String?): JSONObject
}

/** Pro-authorized aggregate reads behind the same transport idiom as the
 * subscription client: Bearer [REDACTED] session, no-store, bounded responses,
 * no cookies or redirects. Filters validate client-side before any request. */
internal class CommunityBenchmarksClient(client: OkHttpClient = OkHttpClient()) : CommunityBenchmarksAPI {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).callTimeout(30, TimeUnit.SECONDS).build()

    /** Latest runs carry every recorded cell plus curve intervals, so the
     * bound is roomier than the subscription client's without being open. */
    private val maximumResponseBytes = 262_144

    override suspend fun aggregates(bucket: String?, metric: String?, runId: String?, access: String?): JSONObject {
        if (bucket != null) require(CommunityBenchmarks.bucketAllowed(bucket))
        if (metric != null) require(CommunityBenchmarks.metricAllowed(metric))
        if (runId != null) require(CommunityBenchmarks.runIdValid(runId))
        require(access != null && com.pittech.data.AccountProtocol.credential(access))
        val url = ("https://api.pittech.app" + CommunityBenchmarks.AGGREGATES_PATH).toHttpUrl().newBuilder()
        if (bucket != null) url.addQueryParameter("bucket", bucket)
        if (metric != null) url.addQueryParameter("metric", metric)
        if (runId != null) url.addQueryParameter("runId", runId)
        return get(url.build().toString(), access)
    }

    override suspend fun jobRuns(limit: Int?, cursor: String?, access: String?): JSONObject {
        if (limit != null) require(CommunityBenchmarks.jobRunsLimitValid(limit))
        if (cursor != null) require(CommunityBenchmarks.runIdValid(cursor))
        require(access != null && com.pittech.data.AccountProtocol.credential(access))
        val url = ("https://api.pittech.app" + CommunityBenchmarks.JOB_RUNS_PATH).toHttpUrl().newBuilder()
        if (limit != null) url.addQueryParameter("limit", limit.toString())
        if (cursor != null) url.addQueryParameter("cursor", cursor)
        return get(url.build().toString(), access)
    }

    private suspend fun get(url: String, access: String): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $access").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(CommunityBenchmarksFailure())
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            if (!it.isSuccessful) throw CommunityBenchmarksFailure(it.code)
                            val source = it.body?.source()
                            val buffer = okio.Buffer()
                            val cap = maximumResponseBytes + 1L
                            if (source != null) while (buffer.size < cap && source.read(buffer, minOf(8192L, cap - buffer.size)) != -1L) { }
                            require(buffer.size <= maximumResponseBytes)
                            if (buffer.size == 0L) JSONObject() else JSONObject(String(buffer.readByteArray(), Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(if (e is CommunityBenchmarksFailure) e else CommunityBenchmarksFailure())
                    }
                }
            })
        }
    }
}
