package com.springrs.plugin.wizard

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.io.HttpRequests
import java.net.URLEncoder

/**
 * A crate found from crates.io search.
 */
data class CrateSearchResult(
    val name: String,
    val version: String,
    val description: String
) {
    /** Cargo.toml dependency line, e.g. `serde = "1.0.217"` */
    val dependencyLine: String get() = """$name = "$version""""

    override fun toString(): String = "$name $version"
}

/**
 * Paged search result from crates.io.
 */
data class CrateSearchPage(
    val crates: List<CrateSearchResult>,
    val total: Int,
    val hasMore: Boolean,
    /** Non-null when the request failed (network error, timeout, etc). */
    val error: String? = null
)

/**
 * Searches crates.io API for crates by keyword with pagination.
 *
 * API: `GET https://crates.io/api/v1/crates?q={query}&per_page={n}&page={p}`
 */
object CratesIoSearchService {

    private val LOG = logger<CratesIoSearchService>()
    private val gson = Gson()
    private const val API_BASE = "https://crates.io/api/v1"

    /**
     * Search crates.io with pagination. Runs synchronously — call from a background thread.
     *
     * @param page 1-based page number
     */
    fun search(query: String, page: Int = 1, perPage: Int = 20): CrateSearchPage {
        if (query.isBlank()) return CrateSearchPage(emptyList(), 0, false)

        return try {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "$API_BASE/crates?q=$encoded&per_page=$perPage&page=$page"

            val json = HttpRequests.request(url)
                .userAgent("spring-rs-intellij-plugin (https://github.com/spring-rs)")
                .readString()

            val response = gson.fromJson(json, CratesIoResponse::class.java)
            val crates = response.crates.map {
                CrateSearchResult(
                    name = it.name,
                    version = it.maxStableVersion ?: it.maxVersion ?: it.newestVersion ?: "0.0.0",
                    description = it.description?.trim() ?: ""
                )
            }
            val total = response.meta?.total ?: crates.size
            val hasMore = page * perPage < total

            CrateSearchPage(crates, total, hasMore)
        } catch (e: Exception) {
            LOG.warn("crates.io search failed for '$query' page=$page: ${e.message}")
            CrateSearchPage(emptyList(), 0, false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    // ── JSON mapping ──

    private data class CratesIoResponse(
        val crates: List<CrateInfo> = emptyList(),
        val meta: MetaInfo? = null
    )

    private data class CrateInfo(
        val name: String = "",
        @SerializedName("max_stable_version") val maxStableVersion: String? = null,
        @SerializedName("max_version") val maxVersion: String? = null,
        @SerializedName("newest_version") val newestVersion: String? = null,
        val description: String? = null
    )

    private data class MetaInfo(val total: Int = 0)
}
