package net.marshalllee.myweather

import org.junit.Assert.*
import org.junit.Test

/**
 * Contract tests for the two external APIs used by MyWeather.
 *
 * These tests hit the live APIs with a known stable location (Washington DC / ZIP 20500)
 * to verify that the response schema has not changed and that the app's parsing logic
 * will still work correctly.
 *
 * Run with:  ./gradlew test --tests "*.ExternalApiContractTest"
 *
 * APIs under test:
 *   1. NOAA National Weather Service — https://api.weather.gov
 *   2. Zippopotam.us                 — https://api.zippopotam.us
 */
class ExternalApiContractTest {

    // ── Known stable test fixture: Washington DC ──────────────────────────────
    //
    // ZIP 20500 (White House) resolves to:
    //   lat ~38.8977, lon ~-77.0366  →  NWS grid LWX / zone DCZ001
    //
    // Using a hardcoded LocationConfig avoids cascading failures — if the
    // points lookup test fails, forecast/alert tests still run independently.

    private val dcLocation = LocationConfig(
        displayName  = "Washington, DC",
        latitude     = 38.8977,
        longitude    = -77.0366,
        gridId       = "LWX",
        gridX        = 96,
        gridY        = 70,
        forecastZone = "DCZ001"
    )

    // ── Zippopotam.us ─────────────────────────────────────────────────────────

    @Test
    fun `zippopotam - ZIP 20500 resolves to DC area coordinates`() {
        val result = lookupByZip("20500")

        // Coordinates must be in the DC metro area
        assertTrue(
            "Expected latitude near 38.9, got ${result.latitude}",
            result.latitude in 38.0..40.0
        )
        assertTrue(
            "Expected longitude near -77.0, got ${result.longitude}",
            result.longitude in -79.0..-76.0
        )
    }

    @Test
    fun `zippopotam - response provides coordinates that resolve to a valid NWS grid`() {
        // Full chain: ZIP -> lat/lon -> NWS grid
        val result = lookupByZip("20500")

        assertTrue("gridId should not be blank", result.gridId.isNotBlank())
        assertTrue("gridX should be positive", result.gridX > 0)
        assertTrue("gridY should be positive", result.gridY > 0)
        assertTrue("forecastZone should not be blank", result.forecastZone.isNotBlank())
        assertTrue("displayName should not be blank", result.displayName.isNotBlank())
    }

    // ── NOAA — Points lookup ──────────────────────────────────────────────────

    @Test
    fun `noaa points - known DC coords return a valid LocationConfig`() {
        val result = lookupGridpoint(38.8977, -77.0366)

        assertTrue("gridId should not be blank", result.gridId.isNotBlank())
        assertTrue("gridX should be positive", result.gridX > 0)
        assertTrue("gridY should be positive", result.gridY > 0)
        assertTrue("forecastZone should not be blank", result.forecastZone.isNotBlank())
        assertTrue("displayName should contain city and state (e.g. 'City, ST')",
            result.displayName.contains(","))
    }

    @Test
    fun `noaa points - response contains forecastZone as a bare zone ID (not a URL)`() {
        // The app extracts the last path segment of the forecastZone URL.
        // Verify the stored value is the bare ID, not the full URL.
        val result = lookupGridpoint(38.8977, -77.0366)

        assertFalse(
            "forecastZone should be a bare ID like 'DCZ001', not a URL",
            result.forecastZone.startsWith("http")
        )
        assertFalse("forecastZone should not contain '/'", result.forecastZone.contains("/"))
    }

    // ── NOAA — Hourly forecast (current conditions) ───────────────────────────

    @Test
    fun `noaa hourly - returns a CurrentWeather with all required fields populated`() {
        val result = fetchCurrentWeather(dcLocation)

        assertTrue(
            "temperatureUnit must be 'F' or 'C', got '${result.temperatureUnit}'",
            result.temperatureUnit in listOf("F", "C")
        )
        assertTrue("windSpeed should not be blank", result.windSpeed.isNotBlank())
        assertTrue("windDirection should not be blank", result.windDirection.isNotBlank())
        assertTrue("shortForecast should not be blank", result.shortForecast.isNotBlank())
        assertTrue(
            "iconUrl should point to api.weather.gov/icons, got '${result.iconUrl}'",
            result.iconUrl.startsWith("https://api.weather.gov/icons")
        )
    }

    @Test
    fun `noaa hourly - humidity and precipChance are null or within valid percentage range`() {
        val result = fetchCurrentWeather(dcLocation)

        result.humidity?.let {
            assertTrue("humidity must be 0–100, got $it", it in 0..100)
        }
        result.precipChance?.let {
            assertTrue("precipChance must be 0–100, got $it", it in 0..100)
        }
    }

    // ── NOAA — Extended forecast ──────────────────────────────────────────────

    @Test
    fun `noaa forecast - returns at least one period`() {
        val result = fetchForecast(dcLocation)

        assertTrue("Forecast list should not be empty", result.isNotEmpty())
    }

    @Test
    fun `noaa forecast - each period has required fields`() {
        val result = fetchForecast(dcLocation)
        val first = result.first()

        assertTrue("name should not be blank", first.name.isNotBlank())
        assertTrue(
            "temperatureUnit must be 'F' or 'C', got '${first.temperatureUnit}'",
            first.temperatureUnit in listOf("F", "C")
        )
        assertTrue("windSpeed should not be blank", first.windSpeed.isNotBlank())
        assertTrue("windDirection should not be blank", first.windDirection.isNotBlank())
        assertTrue("shortForecast should not be blank", first.shortForecast.isNotBlank())
        assertTrue(
            "iconUrl should point to api.weather.gov/icons",
            first.iconUrl.startsWith("https://api.weather.gov/icons")
        )
    }

    @Test
    fun `noaa forecast - periods alternate between daytime and nighttime`() {
        val result = fetchForecast(dcLocation)

        // NWS always alternates day/night. Verify at least the first two periods differ.
        if (result.size >= 2) {
            assertNotEquals(
                "First two forecast periods should alternate isDaytime",
                result[0].isDaytime, result[1].isDaytime
            )
        }
    }

    // ── NOAA — Active alerts ──────────────────────────────────────────────────

    @Test
    fun `noaa alerts - call succeeds and returns a list (may be empty)`() {
        // Alerts can legitimately be empty — the test just verifies the API
        // still returns a parseable response and the list type is correct.
        val result = fetchAlerts(dcLocation)

        assertNotNull("fetchAlerts should never return null", result)
    }

    @Test
    fun `noaa alerts - any returned alerts have required fields`() {
        val result = fetchAlerts(dcLocation)

        for (alert in result) {
            assertTrue("event should not be blank", alert.event.isNotBlank())
            assertTrue("severity should not be blank", alert.severity.isNotBlank())
            // headline and description may be empty strings per the API spec,
            // but they must not throw during parsing (verified by reaching here)
        }
    }
}
