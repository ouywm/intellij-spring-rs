package com.springrs.plugin

import com.springrs.plugin.routes.SpringRsComponentIndex
import com.springrs.plugin.routes.SpringRsRouteUiUtil
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for component, stream, and UI utility methods.
 */
class SpringRsStreamListenerUtilTest {

    // ==================== ComponentType Tests ====================

    @Test
    fun `ComponentType should have correct display names`() {
        assertEquals("Service", SpringRsComponentIndex.ComponentType.SERVICE.displayName)
        assertEquals("Configuration", SpringRsComponentIndex.ComponentType.CONFIGURATION.displayName)
        assertEquals("Plugin", SpringRsComponentIndex.ComponentType.PLUGIN.displayName)
    }

    // ==================== ConfigEntry Tests ====================

    @Test
    fun `ConfigEntry displayValue should prefer TOML value`() {
        val entry = SpringRsComponentIndex.ConfigEntry(
            key = "port",
            value = "9090",
            defaultValue = "8080",
            isFromToml = true
        )
        assertEquals("9090", entry.displayValue())
    }

    @Test
    fun `ConfigEntry displayValue should fallback to default`() {
        val entry = SpringRsComponentIndex.ConfigEntry(
            key = "port",
            value = null,
            defaultValue = "8080",
            isFromToml = false
        )
        assertEquals("8080", entry.displayValue())
    }

    @Test
    fun `ConfigEntry displayValue should return null when no value`() {
        val entry = SpringRsComponentIndex.ConfigEntry(
            key = "custom_field",
            value = null,
            defaultValue = null,
            isFromToml = false
        )
        assertNull(entry.displayValue())
    }

    // ==================== RouteUiUtil Color Tests ====================

    @Test
    fun `getMethodColor should not return null`() {
        assertNotNull(SpringRsRouteUiUtil.getMethodColor("GET"))
        assertNotNull(SpringRsRouteUiUtil.getMethodColor("POST"))
        assertNotNull(SpringRsRouteUiUtil.getMethodColor("PUT"))
        assertNotNull(SpringRsRouteUiUtil.getMethodColor("DELETE"))
        assertNotNull(SpringRsRouteUiUtil.getMethodColor("PATCH"))
        assertNotNull(SpringRsRouteUiUtil.getMethodColor("UNKNOWN"))
    }

    @Test
    fun `getJobTypeColor should not return null`() {
        assertNotNull(SpringRsRouteUiUtil.getJobTypeColor("CRON"))
        assertNotNull(SpringRsRouteUiUtil.getJobTypeColor("FIX_DELAY"))
        assertNotNull(SpringRsRouteUiUtil.getJobTypeColor("FIX_RATE"))
        assertNotNull(SpringRsRouteUiUtil.getJobTypeColor("ONE_SHOT"))
        assertNotNull(SpringRsRouteUiUtil.getJobTypeColor("UNKNOWN"))
    }

    @Test
    fun `getComponentTypeColor should not return null`() {
        assertNotNull(SpringRsRouteUiUtil.getComponentTypeColor("SERVICE"))
        assertNotNull(SpringRsRouteUiUtil.getComponentTypeColor("CONFIGURATION"))
        assertNotNull(SpringRsRouteUiUtil.getComponentTypeColor("PLUGIN"))
        assertNotNull(SpringRsRouteUiUtil.getComponentTypeColor("UNKNOWN"))
    }
}
