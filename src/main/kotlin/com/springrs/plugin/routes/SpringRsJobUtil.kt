package com.springrs.plugin.routes

import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.RsMetaItem
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.psi.ext.stringValue

/**
 * Utilities for extracting spring-rs job scheduling info from Rust PSI.
 *
 * Supported attribute macros (from spring-job crate):
 * - #[cron("1/10 * * * * *")]  — cron-based scheduling
 * - #[fix_delay(10000)]        — fixed delay between executions (ms)
 * - #[fix_rate(5000)]          — fixed rate execution (ms)
 * - #[one_shot(3000)]          — one-time execution after delay (ms)
 */
object SpringRsJobUtil {

    /**
     * Job scheduling type.
     */
    enum class JobType(val attrName: String, val displayName: String) {
        CRON("cron", "CRON"),
        FIX_DELAY("fix_delay", "FIX_DELAY"),
        FIX_RATE("fix_rate", "FIX_RATE"),
        ONE_SHOT("one_shot", "ONE_SHOT");

        companion object {
            private val BY_ATTR_NAME = entries.associateBy { it.attrName }

            fun fromAttrName(name: String): JobType? = BY_ATTR_NAME[name]
        }
    }

    /**
     * Extracted job info from an attribute macro.
     */
    data class JobInfo(
        val type: JobType,
        val expression: String
    )

    /**
     * Attribute names that indicate a job scheduling macro.
     */
    private val JOB_ATTR_NAMES: Set<String> = JobType.entries.map { it.attrName }.toSet()

    /**
     * Checks if a function has any job scheduling attribute.
     */
    fun hasJobAttribute(fn: RsFunction): Boolean {
        return fn.outerAttrList.any { attr ->
            val name = attr.metaItem.name
            name != null && name in JOB_ATTR_NAMES
        }
    }

    /**
     * Extracts job info from a function's attributes.
     *
     * Returns the first matching job attribute, or null if none found.
     * (A function should normally have only one scheduling attribute.)
     */
    fun extractJobInfo(fn: RsFunction): JobInfo? {
        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            val name = meta.name ?: continue
            val jobType = JobType.fromAttrName(name) ?: continue

            val expression = extractExpression(meta, jobType)
            if (expression != null) {
                return JobInfo(type = jobType, expression = expression)
            }
        }
        return null
    }

    /**
     * Extracts all job infos from a function (in case of multiple attributes).
     */
    fun extractAllJobInfos(fn: RsFunction): List<JobInfo> {
        val result = mutableListOf<JobInfo>()
        for (attr in fn.outerAttrList) {
            val meta = attr.metaItem
            val name = meta.name ?: continue
            val jobType = JobType.fromAttrName(name) ?: continue

            val expression = extractExpression(meta, jobType)
            if (expression != null) {
                result.add(JobInfo(type = jobType, expression = expression))
            }
        }
        return result
    }

    /**
     * Extracts the scheduling expression from a meta item.
     *
     * For #[cron("expr")]: extracts the string literal.
     * For #[fix_delay(ms)], #[fix_rate(ms)], #[one_shot(ms)]: extracts the numeric literal as string.
     */
    private fun extractExpression(meta: RsMetaItem, jobType: JobType): String? {
        val args = meta.metaItemArgs ?: return null

        // Try string literal first: #[cron("1/10 * * * * *")]
        args.litExprList.firstOrNull()?.let { litExpr ->
            litExpr.stringValue?.let { return it }
            // For numeric literals (fix_delay, fix_rate, one_shot): the text itself is the value.
            val text = litExpr.text?.trim()
            if (text != null && text.isNotEmpty()) {
                // Strip quotes if present.
                return text.removeSurrounding("\"")
            }
        }

        // Try meta item args: #[cron(expr = "...")]
        val metaItems = args.metaItemList
        if (metaItems.isNotEmpty()) {
            // Unnamed first argument.
            val unnamed = metaItems.firstOrNull { it.name.isNullOrBlank() } ?: metaItems.firstOrNull()
            unnamed?.litExpr?.let { litExpr ->
                litExpr.stringValue?.let { return it }
                val text = litExpr.text?.trim()
                if (text != null && text.isNotEmpty()) {
                    return text.removeSurrounding("\"")
                }
            }

            // Named: expr = "..."
            metaItems.firstOrNull { it.name == "expr" }?.litExpr?.stringValue?.let { return it }
        }

        return null
    }

    /**
     * Formats a job expression for display.
     *
     * @param jobInfo job info
     * @return formatted string, e.g. "CRON: 1/10 * * * * *" or "FIX_DELAY: 10000ms"
     */
    fun formatJobExpression(jobInfo: JobInfo): String {
        return when (jobInfo.type) {
            JobType.CRON -> "${jobInfo.type.displayName}: ${jobInfo.expression}"
            else -> "${jobInfo.type.displayName}: ${jobInfo.expression}ms"
        }
    }
}
