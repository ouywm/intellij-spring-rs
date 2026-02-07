package com.springrs.plugin

import com.springrs.plugin.routes.SpringRsJobUtil
import com.springrs.plugin.routes.SpringRsRouteUtil
import com.springrs.plugin.utils.RustTypeUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for spring-rs plugin utility methods.
 *
 * These tests verify pure logic that doesn't require IntelliJ PSI or Rust plugin.
 */
class SpringRsJobUtilTest {

    // ==================== JobType Tests ====================

    @Test
    fun `JobType fromAttrName should return correct types`() {
        assertEquals(SpringRsJobUtil.JobType.CRON, SpringRsJobUtil.JobType.fromAttrName("cron"))
        assertEquals(SpringRsJobUtil.JobType.FIX_DELAY, SpringRsJobUtil.JobType.fromAttrName("fix_delay"))
        assertEquals(SpringRsJobUtil.JobType.FIX_RATE, SpringRsJobUtil.JobType.fromAttrName("fix_rate"))
        assertEquals(SpringRsJobUtil.JobType.ONE_SHOT, SpringRsJobUtil.JobType.fromAttrName("one_shot"))
        assertNull(SpringRsJobUtil.JobType.fromAttrName("unknown"))
        assertNull(SpringRsJobUtil.JobType.fromAttrName("get"))
    }

    @Test
    fun `JobType displayName should be uppercase`() {
        assertEquals("CRON", SpringRsJobUtil.JobType.CRON.displayName)
        assertEquals("FIX_DELAY", SpringRsJobUtil.JobType.FIX_DELAY.displayName)
        assertEquals("FIX_RATE", SpringRsJobUtil.JobType.FIX_RATE.displayName)
        assertEquals("ONE_SHOT", SpringRsJobUtil.JobType.ONE_SHOT.displayName)
    }

    @Test
    fun `formatJobExpression should format correctly`() {
        val cronInfo = SpringRsJobUtil.JobInfo(SpringRsJobUtil.JobType.CRON, "1/10 * * * * *")
        assertEquals("CRON: 1/10 * * * * *", SpringRsJobUtil.formatJobExpression(cronInfo))

        val delayInfo = SpringRsJobUtil.JobInfo(SpringRsJobUtil.JobType.FIX_DELAY, "10000")
        assertEquals("FIX_DELAY: 10000ms", SpringRsJobUtil.formatJobExpression(delayInfo))

        val rateInfo = SpringRsJobUtil.JobInfo(SpringRsJobUtil.JobType.FIX_RATE, "5000")
        assertEquals("FIX_RATE: 5000ms", SpringRsJobUtil.formatJobExpression(rateInfo))

        val oneShotInfo = SpringRsJobUtil.JobInfo(SpringRsJobUtil.JobType.ONE_SHOT, "3000")
        assertEquals("ONE_SHOT: 3000ms", SpringRsJobUtil.formatJobExpression(oneShotInfo))
    }

    // ==================== RouteUtil Path Tests ====================

    @Test
    fun `joinPaths should normalize paths correctly`() {
        assertEquals("/", SpringRsRouteUtil.joinPaths("", ""))
        assertEquals("/api", SpringRsRouteUtil.joinPaths("", "/api"))
        assertEquals("/api", SpringRsRouteUtil.joinPaths("/api", ""))
        assertEquals("/api/users", SpringRsRouteUtil.joinPaths("/api", "/users"))
        assertEquals("/api/users", SpringRsRouteUtil.joinPaths("/api/", "/users"))
        assertEquals("/api/users", SpringRsRouteUtil.joinPaths("/api", "users"))
        assertEquals("/api/users", SpringRsRouteUtil.joinPaths("/api/", "users"))
    }

    @Test
    fun `joinPaths should handle null inputs`() {
        assertEquals("/", SpringRsRouteUtil.joinPaths(null, null))
        assertEquals("/api", SpringRsRouteUtil.joinPaths(null, "/api"))
        assertEquals("/api", SpringRsRouteUtil.joinPaths("/api", null))
    }

    @Test
    fun `joinPaths should strip trailing slashes`() {
        assertEquals("/api", SpringRsRouteUtil.joinPaths("", "/api/"))
        assertEquals("/api/users", SpringRsRouteUtil.joinPaths("/api/", "/users/"))
    }

    @Test
    fun `joinPaths should keep root path`() {
        assertEquals("/", SpringRsRouteUtil.joinPaths("", "/"))
        assertEquals("/", SpringRsRouteUtil.joinPaths("/", ""))
    }

    @Test
    fun `extractRouteParams should parse params correctly`() {
        val params = SpringRsRouteUtil.extractRouteParams("/users/{id}/files/{*rest}")
        assertEquals(2, params.size)
        assertEquals("id", params[0].name)
        assertFalse(params[0].isWildcard)
        assertEquals("rest", params[1].name)
        assertTrue(params[1].isWildcard)
    }

    @Test
    fun `extractRouteParams should handle no params`() {
        val params = SpringRsRouteUtil.extractRouteParams("/users/all")
        assertTrue(params.isEmpty())
    }

    @Test
    fun `formatRouteParams should format correctly`() {
        val params = SpringRsRouteUtil.extractRouteParams("/users/{id}/files/{*rest}")
        assertEquals("id, *rest", SpringRsRouteUtil.formatRouteParams(params))
    }

    // ==================== RustTypeUtils Tests ====================

    @Test
    fun `getDefaultValueExample should return correct defaults`() {
        assertEquals("false", RustTypeUtils.getDefaultValueExample("bool"))
        assertEquals("0", RustTypeUtils.getDefaultValueExample("i32"))
        assertEquals("0", RustTypeUtils.getDefaultValueExample("u64"))
        assertEquals("0.0", RustTypeUtils.getDefaultValueExample("f64"))
        assertEquals("\"\"", RustTypeUtils.getDefaultValueExample("String"))
        assertEquals("[]", RustTypeUtils.getDefaultValueExample("Vec<String>"))
    }

    @Test
    fun `isPrimitiveType should identify primitives`() {
        assertTrue(RustTypeUtils.isPrimitiveType("bool"))
        assertTrue(RustTypeUtils.isPrimitiveType("i32"))
        assertTrue(RustTypeUtils.isPrimitiveType("u64"))
        assertTrue(RustTypeUtils.isPrimitiveType("f64"))
        assertFalse(RustTypeUtils.isPrimitiveType("String"))
        assertFalse(RustTypeUtils.isPrimitiveType("MyStruct"))
    }

    @Test
    fun `isStringType should identify string types`() {
        assertTrue(RustTypeUtils.isStringType("String"))
        assertTrue(RustTypeUtils.isStringType("Option<String>"))
        assertTrue(RustTypeUtils.isStringType("Vec<String>")) // Vec is in WRAPPER_TYPES
        assertFalse(RustTypeUtils.isStringType("bool"))
        assertFalse(RustTypeUtils.isStringType("HashMap<String, String>"))
    }

    @Test
    fun `extractTypeName should strip wrappers`() {
        assertEquals("String", RustTypeUtils.extractTypeName("Option<String>"))
        assertEquals("User", RustTypeUtils.extractTypeName("Vec<User>"))
        assertEquals("String", RustTypeUtils.extractTypeName("String"))
    }

    @Test
    fun `isMapType should identify map types`() {
        assertTrue(RustTypeUtils.isMapType("HashMap<String, String>"))
        assertTrue(RustTypeUtils.isMapType("BTreeMap<String, i32>"))
        assertTrue(RustTypeUtils.isMapType("IndexMap<String, Value>"))
        assertFalse(RustTypeUtils.isMapType("Vec<String>"))
    }

    @Test
    fun `isBoolType should identify bool`() {
        assertTrue(RustTypeUtils.isBoolType("bool"))
        assertTrue(RustTypeUtils.isBoolType("Option<bool>"))
        assertFalse(RustTypeUtils.isBoolType("String"))
    }

    @Test
    fun `isNumericType should identify numeric types`() {
        assertTrue(RustTypeUtils.isNumericType("i32"))
        assertTrue(RustTypeUtils.isNumericType("u64"))
        assertTrue(RustTypeUtils.isNumericType("f64"))
        assertFalse(RustTypeUtils.isNumericType("String"))
        assertFalse(RustTypeUtils.isNumericType("bool"))
    }
}
