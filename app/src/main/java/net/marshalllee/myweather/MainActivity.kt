package net.marshalllee.myweather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import net.marshalllee.myweather.ui.theme.MyWeatherTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Data models ───────────────────────────────────────────────────────────────

data class ForecastPeriod(
    val name: String,
    val temperature: Int,
    val temperatureUnit: String,
    val windSpeed: String,
    val windDirection: String,
    val shortForecast: String,
    val isDaytime: Boolean,
    val iconUrl: String
)

data class CurrentWeather(
    val temperature: Int,
    val temperatureUnit: String,
    val windSpeed: String,
    val windDirection: String,
    val shortForecast: String,
    val iconUrl: String,
    val humidity: Int?,
    val precipChance: Int?
)

data class WeatherAlert(
    val event: String,
    val headline: String,
    val severity: String,
    val onset: String?,
    val expires: String?,
    val description: String,
    val instruction: String?
)

data class LocationConfig(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val gridId: String,
    val gridX: Int,
    val gridY: Int,
    val forecastZone: String
)

// ── Location persistence ──────────────────────────────────────────────────────

private const val PREFS_NAME   = "weather_settings"
private const val KEY_NAME     = "display_name"
private const val KEY_LAT      = "latitude"
private const val KEY_LON      = "longitude"
private const val KEY_GRID_ID  = "grid_id"
private const val KEY_GRID_X   = "grid_x"
private const val KEY_GRID_Y   = "grid_y"
private const val KEY_ZONE     = "forecast_zone"

private val DEFAULT_LOCATION = LocationConfig(
    displayName  = "Cold Springs, NV",
    latitude     = 39.6766,
    longitude    = -119.9929,
    gridId       = "REV",
    gridX        = 41,
    gridY        = 114,
    forecastZone = "NVZ003"
)

private fun loadLocation(prefs: SharedPreferences): LocationConfig = LocationConfig(
    displayName  = prefs.getString(KEY_NAME,    DEFAULT_LOCATION.displayName)!!,
    latitude     = java.lang.Double.longBitsToDouble(
                       prefs.getLong(KEY_LAT, java.lang.Double.doubleToLongBits(DEFAULT_LOCATION.latitude))),
    longitude    = java.lang.Double.longBitsToDouble(
                       prefs.getLong(KEY_LON, java.lang.Double.doubleToLongBits(DEFAULT_LOCATION.longitude))),
    gridId       = prefs.getString(KEY_GRID_ID, DEFAULT_LOCATION.gridId)!!,
    gridX        = prefs.getInt(KEY_GRID_X,     DEFAULT_LOCATION.gridX),
    gridY        = prefs.getInt(KEY_GRID_Y,     DEFAULT_LOCATION.gridY),
    forecastZone = prefs.getString(KEY_ZONE,    DEFAULT_LOCATION.forecastZone)!!
)

private fun saveLocation(prefs: SharedPreferences, config: LocationConfig) {
    prefs.edit {
        putString(KEY_NAME,    config.displayName)
        putLong(KEY_LAT,       java.lang.Double.doubleToLongBits(config.latitude))
        putLong(KEY_LON,       java.lang.Double.doubleToLongBits(config.longitude))
        putString(KEY_GRID_ID, config.gridId)
        putInt(KEY_GRID_X,     config.gridX)
        putInt(KEY_GRID_Y,     config.gridY)
        putString(KEY_ZONE,    config.forecastZone)
    }
}

// ── Refresh controller ────────────────────────────────────────────────────────

class WeatherRefreshController(
    val staleThresholdMs: Long      = 5L  * 60 * 1000,
    val autoRefreshIntervalMs: Long = 30L * 60 * 1000,
    val retryIntervalMs: Long       = 2L  * 60 * 1000,
    private val clock: () -> Long   = System::currentTimeMillis
) {
    var lastFetchTimeMs: Long = clock()
        private set

    fun recordFetchTime() { lastFetchTimeMs = clock() }

    fun isDataStale(): Boolean = (clock() - lastFetchTimeMs) > staleThresholdMs

    // onRefresh returns true on success, false on failure.
    // Uses a shorter retry interval when the fetch failed.
    suspend fun startAutoRefreshLoop(onRefresh: suspend () -> Boolean) {
        while (true) {
            val success = onRefresh()
            delay(if (success) autoRefreshIntervalMs else retryIntervalMs)
        }
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyWeatherTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

// ── Main screen with tabs ─────────────────────────────────────────────────────

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var location     by remember { mutableStateOf(loadLocation(prefs)) }
    var forecast     by remember { mutableStateOf<List<ForecastPeriod>>(emptyList()) }
    var currentWeather by remember { mutableStateOf<CurrentWeather?>(null) }
    var alerts       by remember { mutableStateOf<List<WeatherAlert>>(emptyList()) }
    var lastFetchedAt by remember { mutableStateOf<String?>(null) }
    var isLoading    by remember { mutableStateOf(true) }
    var error        by remember { mutableStateOf<String?>(null) }
    val controller   = remember { WeatherRefreshController() }
    val pagerState   = rememberPagerState(pageCount = { 4 })
    val scope        = rememberCoroutineScope()

    suspend fun doFetch(): Boolean {
        // Fail immediately if no network — no timeout needed
        if (!isNetworkAvailable(context)) {
            error    = "No internet connection — will retry automatically."
            isLoading = false
            return false
        }
        val loc = location
        return try {
            isLoading = forecast.isEmpty() && currentWeather == null
            val (current, periods, activeAlerts) = withContext(Dispatchers.IO) {
                val a = async { fetchCurrentWeather(loc) }
                val b = async { fetchForecast(loc) }
                val c = async { fetchAlerts(loc) }
                Triple(a.await(), b.await(), c.await())
            }
            currentWeather = current
            forecast       = periods
            alerts         = activeAlerts
            lastFetchedAt  = ZonedDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a"))
            controller.recordFetchTime()
            error = null
            true
        } catch (e: Exception) {
            error = when (e) {
                is java.net.UnknownHostException   -> "No internet connection.\nWill retry automatically."
                is java.net.SocketTimeoutException -> "Request timed out.\nWill retry automatically."
                else                               -> "Unable to reach weather service.\nWill retry automatically."
            }
            false
        } finally {
            isLoading = false
        }
    }

    // Auto-refresh on launch, then every 30 minutes
    LaunchedEffect(Unit) {
        controller.startAutoRefreshLoop { doFetch() }
    }

    // Refresh on resume if data is stale
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && controller.isDataStale()) {
                scope.launch { doFetch() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        TabRow(selectedTabIndex = pagerState.currentPage) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick  = { scope.launch { pagerState.animateScrollToPage(0) } },
                text     = { Text("Current") }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick  = { scope.launch { pagerState.animateScrollToPage(1) } },
                text     = { Text("7-Day Forecast") }
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick  = { scope.launch { pagerState.animateScrollToPage(2) } },
                text     = { Text("Settings") }
            )
            Tab(
                selected = pagerState.currentPage == 3,
                onClick  = { scope.launch { pagerState.animateScrollToPage(3) } },
                text     = { Text("About") }
            )
        }

        // Always show the pager — About/Settings never need network data
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> CurrentWeatherTab(
                    current      = currentWeather,
                    alerts       = alerts,
                    lastFetchedAt = lastFetchedAt,
                    locationName = location.displayName,
                    isLoading    = isLoading,
                    error        = error,
                    todayHigh    = forecast.firstOrNull { it.isDaytime }?.let { "${it.temperature}°${it.temperatureUnit}" },
                    todayLow     = forecast.firstOrNull { !it.isDaytime }?.let { "${it.temperature}°${it.temperatureUnit}" }
                )
                1 -> ForecastTab(forecast, location.displayName, isLoading, error)
                3 -> AboutTab()
                2 -> SettingsTab(
                            location      = location,
                            onLocationSaved = { newConfig ->
                                saveLocation(prefs, newConfig)
                                location = newConfig
                                scope.launch { doFetch() }
                            },
                            onRefreshNow  = { scope.launch { doFetch() } }
                        )
            }
        }
    }
}

// ── Current weather tab ───────────────────────────────────────────────────────

@Composable
fun CurrentWeatherTab(
    current: CurrentWeather?,
    alerts: List<WeatherAlert>,
    lastFetchedAt: String?,
    locationName: String,
    isLoading: Boolean,
    error: String?,
    todayHigh: String?,
    todayLow: String?
) {
    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text     = error ?: "No data available.",
                color    = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top)
        ) {
            Text(
                text     = locationName,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )

            AsyncImage(
                model           = getWeatherIconModel(current.iconUrl),
                contentDescription = current.shortForecast,
                modifier        = Modifier.size(120.dp).padding(vertical = 8.dp)
            )

            Text(
                text     = "${current.temperature}°${current.temperatureUnit}",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold
            )

            if (todayHigh != null || todayLow != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (todayHigh != null) Text(
                        text  = "H: $todayHigh",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (todayLow != null) Text(
                        text  = "L: $todayLow",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text     = current.shortForecast,
                fontSize = 18.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            WeatherDetailRow("Wind", "${current.windSpeed} ${current.windDirection}")
            if (current.humidity != null)    WeatherDetailRow("Humidity",      "${current.humidity}%")
            if (current.precipChance != null) WeatherDetailRow("Precip. Chance", "${current.precipChance}%")

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = if (alerts.isEmpty()) "No Active Alerts" else "Active Alerts (${alerts.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp
                )
            }

            if (alerts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                alerts.forEach { AlertCard(it) }
            }
        }

        if (!lastFetchedAt.isNullOrBlank()) {
            Text(
                text     = "Updated $lastFetchedAt",
                fontSize = 10.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 8.dp)
            )
        }
    }
}

@Composable
fun WeatherDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        Text(text = value, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

@Composable
fun AlertCard(alert: WeatherAlert) {
    val containerColor = when (alert.severity.lowercase()) {
        "extreme", "severe" -> MaterialTheme.colorScheme.errorContainer
        "moderate"          -> MaterialTheme.colorScheme.tertiaryContainer
        else                -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = alert.event, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (alert.headline.isNotBlank()) Text(text = alert.headline, fontSize = 13.sp)
            if (!alert.onset.isNullOrBlank())
                Text("From: ${formatAlertTime(alert.onset)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!alert.expires.isNullOrBlank())
                Text("Until: ${formatAlertTime(alert.expires)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!alert.instruction.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = alert.instruction, fontSize = 12.sp)
            }
        }
    }
}

fun formatAlertTime(iso: String): String = try {
    ZonedDateTime.parse(iso).format(DateTimeFormatter.ofPattern("EEE MMM d, h:mm a"))
} catch (_: Exception) { iso }

// ── 7-day forecast tab ────────────────────────────────────────────────────────

@Composable
fun ForecastTab(forecast: List<ForecastPeriod>, locationName: String, isLoading: Boolean, error: String?) {
    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (forecast.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text     = error ?: "No forecast data available.",
                color    = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp)
            )
        }
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text     = "7-Day Forecast — $locationName",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(forecast) { period -> ForecastCard(period) }
        }
    }
}

@Composable
fun ForecastCard(period: ForecastPeriod) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = if (period.isDaytime)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = getWeatherIconModel(period.iconUrl),
                contentDescription = period.shortForecast,
                modifier = Modifier.size(56.dp).padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = period.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(text = period.shortForecast, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = "Wind: ${period.windSpeed} ${period.windDirection}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text     = if (period.isDaytime) "H" else "L",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text     = "${period.temperature}°${period.temperatureUnit}",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Settings tab ──────────────────────────────────────────────────────────────

@Composable
fun SettingsTab(
    location: LocationConfig,
    onLocationSaved: (LocationConfig) -> Unit,
    onRefreshNow: () -> Unit
) {
    val context       = LocalContext.current
    var shownName     by remember(location.displayName) { mutableStateOf(location.displayName) }
    var latText       by remember(location.latitude)  { mutableStateOf(location.latitude.toString()) }
    var lonText       by remember(location.longitude) { mutableStateOf(location.longitude.toString()) }
    var zipText       by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isLookingUp   by remember { mutableStateOf(false) }
    val scope         = rememberCoroutineScope()

    // Runs after the user responds to the permission dialog
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            isLookingUp   = true
            statusMessage = null
            scope.launch {
                try {
                    val (lat, lon) = getCurrentLocation(context)
                    val newConfig  = withContext(Dispatchers.IO) { lookupGridpoint(lat, lon) }
                    latText   = newConfig.latitude.toString()
                    lonText   = newConfig.longitude.toString()
                    zipText   = ""
                    shownName = newConfig.displayName
                    onLocationSaved(newConfig)
                    statusMessage = "Location saved: ${newConfig.displayName}"
                } catch (e: Exception) {
                    statusMessage = when (e) {
                        is java.net.UnknownHostException   -> "No internet connection. Check your network and try again."
                        is java.net.SocketTimeoutException -> "Request timed out. Check your connection and try again."
                        is SecurityException               -> "Location permission denied. Enable it in phone Settings."
                        else                               -> "Could not get current location. Please try again."
                    }
                } finally {
                    isLookingUp = false
                }
            }
        } else {
            statusMessage = "Location permission denied. Enable it in phone Settings."
        }
    }

    fun useCurrentLocation() {
        val fineOk   = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)   == PackageManager.PERMISSION_GRANTED
        val coarseOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fineOk || coarseOk) {
            isLookingUp   = true
            statusMessage = null
            scope.launch {
                try {
                    val (lat, lon) = getCurrentLocation(context)
                    val newConfig  = withContext(Dispatchers.IO) { lookupGridpoint(lat, lon) }
                    latText   = newConfig.latitude.toString()
                    lonText   = newConfig.longitude.toString()
                    zipText   = ""
                    shownName = newConfig.displayName
                    onLocationSaved(newConfig)
                    statusMessage = "Location saved: ${newConfig.displayName}"
                } catch (e: Exception) {
                    statusMessage = when (e) {
                        is java.net.UnknownHostException   -> "No internet connection. Check your network and try again."
                        is java.net.SocketTimeoutException -> "Request timed out. Check your connection and try again."
                        is SecurityException               -> "Location permission denied. Enable it in phone Settings."
                        else                               -> "Could not get current location. Please try again."
                    }
                } finally {
                    isLookingUp = false
                }
            }
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Location ────────────────────────────────────────────
        Text("Location", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        Text(
            text     = "Current: $shownName",
            fontSize = 14.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── GPS / current location ──────────────────────────────
        OutlinedButton(
            onClick  = { useCurrentLocation() },
            enabled  = !isLookingUp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Use Current Location")
        }

        HorizontalDivider()

        // ── Zip code lookup ─────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value         = zipText,
                onValueChange = { if (it.length <= 5) zipText = it.filter { c -> c.isDigit() } },
                label         = { Text("Zip Code") },
                placeholder   = { Text("e.g. 89506") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction    = ImeAction.Search
                ),
                singleLine    = true,
                modifier      = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (zipText.length != 5) {
                        statusMessage = "Enter a valid 5-digit zip code"
                        return@Button
                    }
                    isLookingUp   = true
                    statusMessage = null
                    scope.launch {
                        try {
                            val newConfig = withContext(Dispatchers.IO) { lookupByZip(zipText) }
                            latText   = newConfig.latitude.toString()
                            lonText   = newConfig.longitude.toString()
                            shownName = newConfig.displayName
                            onLocationSaved(newConfig)
                            statusMessage = "Location saved: ${newConfig.displayName}"
                        } catch (e: Exception) {
                            statusMessage = when (e) {
                                is java.net.UnknownHostException   -> "No internet connection. Check your network and try again."
                                is java.net.SocketTimeoutException -> "Request timed out. Check your connection and try again."
                                else                               -> "Zip code lookup failed. Check the zip code and try again."
                            }
                        } finally {
                            isLookingUp = false
                        }
                    }
                },
                enabled = !isLookingUp
            ) {
                Text("Look Up")
            }
        }

        HorizontalDivider()

        // ── Manual lat/lon ──────────────────────────────────────
        Text(
            text     = "Or enter coordinates manually:",
            fontSize = 13.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value         = latText,
            onValueChange = { latText = it },
            label         = { Text("Latitude") },
            placeholder   = { Text("e.g. 39.6766") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value         = lonText,
            onValueChange = { lonText = it },
            label         = { Text("Longitude") },
            placeholder   = { Text("e.g. -119.9929") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                val lat = latText.trim().toDoubleOrNull()
                val lon = lonText.trim().toDoubleOrNull()
                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    statusMessage = "Enter valid decimal coordinates (lat −90..90, lon −180..180)"
                    return@Button
                }
                isLookingUp   = true
                statusMessage = null
                scope.launch {
                    try {
                        val newConfig = withContext(Dispatchers.IO) { lookupGridpoint(lat, lon) }
                        zipText   = ""
                        shownName = newConfig.displayName
                        onLocationSaved(newConfig)
                        statusMessage = "Location saved: ${newConfig.displayName}"
                    } catch (e: Exception) {
                        statusMessage = when (e) {
                            is java.net.UnknownHostException   -> "No internet connection. Check your network and try again."
                            is java.net.SocketTimeoutException -> "Request timed out. Check your connection and try again."
                            is NumberFormatException           -> "Invalid coordinates. Please enter valid numbers."
                            else                               -> "Location lookup failed. Check your coordinates and try again."
                        }
                    } finally {
                        isLookingUp = false
                    }
                }
            },
            enabled  = !isLookingUp,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLookingUp) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Look Up & Save Location")
        }

        if (!statusMessage.isNullOrBlank()) {
            Text(
                text     = statusMessage!!,
                fontSize = 13.sp,
                color    = if (statusMessage!!.startsWith("Location saved"))
                               MaterialTheme.colorScheme.primary
                           else
                               MaterialTheme.colorScheme.error
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Data ────────────────────────────────────────────────
        Text("Data", fontWeight = FontWeight.Bold, fontSize = 18.sp)

        Button(onClick = onRefreshNow, modifier = Modifier.fillMaxWidth()) {
            Text("Refresh Now")
        }
    }
}

// ── About tab ─────────────────────────────────────────────────────────────────

@Composable
fun AboutTab() {
    val context     = LocalContext.current
    val uriHandler  = LocalUriHandler.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.2"
        } catch (_: Exception) { "1.2" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        AsyncImage(
            model              = R.mipmap.ic_launcher_round,
            contentDescription = "MyWeather app icon",
            modifier           = Modifier.size(96.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text       = "MyWeather",
            fontSize   = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text     = "Version $versionName",
            fontSize = 16.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text     = "Weather data provided by",
            fontSize = 14.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text       = "National Oceanic and Atmospheric Administration (NOAA)",
            fontSize   = 13.sp,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign  = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text       = "© marshalllee.net",
            fontSize   = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color      = MaterialTheme.colorScheme.primary
        )

        Text(
            text     = "All rights reserved.",
            fontSize = 12.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text            = "contact@marshalllee.net",
            fontSize        = 14.sp,
            color           = MaterialTheme.colorScheme.primary,
            textDecoration  = TextDecoration.Underline,
            modifier        = Modifier.clickable {
                uriHandler.openUri("mailto:contact@marshalllee.net")
            }
        )
    }
}

// ── Icon resolver ─────────────────────────────────────────────────────────────
// Returns a local drawable resource ID when the icon is bundled, otherwise
// falls back to the original network URL so the app always shows something.

private fun localWeatherIcon(tod: String, cond: String): Int? = when ("${tod}_${cond}") {
    "day_skc"               -> R.drawable.noaa_day_skc
    "night_skc"             -> R.drawable.noaa_night_skc
    "day_few"               -> R.drawable.noaa_day_few
    "night_few"             -> R.drawable.noaa_night_few
    "day_sct"               -> R.drawable.noaa_day_sct
    "night_sct"             -> R.drawable.noaa_night_sct
    "day_bkn"               -> R.drawable.noaa_day_bkn
    "night_bkn"             -> R.drawable.noaa_night_bkn
    "day_ovc"               -> R.drawable.noaa_day_ovc
    "night_ovc"             -> R.drawable.noaa_night_ovc
    "day_wind_skc"          -> R.drawable.noaa_day_wind_skc
    "night_wind_skc"        -> R.drawable.noaa_night_wind_skc
    "day_wind_few"          -> R.drawable.noaa_day_wind_few
    "night_wind_few"        -> R.drawable.noaa_night_wind_few
    "day_wind_sct"          -> R.drawable.noaa_day_wind_sct
    "night_wind_sct"        -> R.drawable.noaa_night_wind_sct
    "day_wind_bkn"          -> R.drawable.noaa_day_wind_bkn
    "night_wind_bkn"        -> R.drawable.noaa_night_wind_bkn
    "day_wind_ovc"          -> R.drawable.noaa_day_wind_ovc
    "night_wind_ovc"        -> R.drawable.noaa_night_wind_ovc
    "day_snow"              -> R.drawable.noaa_day_snow
    "night_snow"            -> R.drawable.noaa_night_snow
    "day_rain_snow"         -> R.drawable.noaa_day_rain_snow
    "night_rain_snow"       -> R.drawable.noaa_night_rain_snow
    "day_rain_sleet"        -> R.drawable.noaa_day_rain_sleet
    "night_rain_sleet"      -> R.drawable.noaa_night_rain_sleet
    "day_snow_sleet"        -> R.drawable.noaa_day_snow_sleet
    "night_snow_sleet"      -> R.drawable.noaa_night_snow_sleet
    "day_fzra"              -> R.drawable.noaa_day_fzra
    "night_fzra"            -> R.drawable.noaa_night_fzra
    "day_rain_fzra"         -> R.drawable.noaa_day_rain_fzra
    "night_rain_fzra"       -> R.drawable.noaa_night_rain_fzra
    "day_snow_fzra"         -> R.drawable.noaa_day_snow_fzra
    "night_snow_fzra"       -> R.drawable.noaa_night_snow_fzra
    "day_sleet"             -> R.drawable.noaa_day_sleet
    "night_sleet"           -> R.drawable.noaa_night_sleet
    "day_rain"              -> R.drawable.noaa_day_rain
    "night_rain"            -> R.drawable.noaa_night_rain
    "day_rain_showers"      -> R.drawable.noaa_day_rain_showers
    "night_rain_showers"    -> R.drawable.noaa_night_rain_showers
    "day_rain_showers_hi"   -> R.drawable.noaa_day_rain_showers_hi
    "night_rain_showers_hi" -> R.drawable.noaa_night_rain_showers_hi
    "day_tsra"              -> R.drawable.noaa_day_tsra
    "night_tsra"            -> R.drawable.noaa_night_tsra
    "day_tsra_sct"          -> R.drawable.noaa_day_tsra_sct
    "night_tsra_sct"        -> R.drawable.noaa_night_tsra_sct
    "day_tsra_hi"           -> R.drawable.noaa_day_tsra_hi
    "night_tsra_hi"         -> R.drawable.noaa_night_tsra_hi
    "day_tornado"           -> R.drawable.noaa_day_tornado
    "night_tornado"         -> R.drawable.noaa_night_tornado
    "day_hurricane"         -> R.drawable.noaa_day_hurricane
    "night_hurricane"       -> R.drawable.noaa_night_hurricane
    "day_tropical_storm"    -> R.drawable.noaa_day_tropical_storm
    "night_tropical_storm"  -> R.drawable.noaa_night_tropical_storm
    "day_dust"              -> R.drawable.noaa_day_dust
    "night_dust"            -> R.drawable.noaa_night_dust
    "day_smoke"             -> R.drawable.noaa_day_smoke
    "night_smoke"           -> R.drawable.noaa_night_smoke
    "day_haze"              -> R.drawable.noaa_day_haze
    "night_haze"            -> R.drawable.noaa_night_haze
    "day_hot"               -> R.drawable.noaa_day_hot
    "night_hot"             -> R.drawable.noaa_night_hot
    "day_cold"              -> R.drawable.noaa_day_cold
    "night_cold"            -> R.drawable.noaa_night_cold
    "day_blizzard"          -> R.drawable.noaa_day_blizzard
    "night_blizzard"        -> R.drawable.noaa_night_blizzard
    "day_fog"               -> R.drawable.noaa_day_fog
    "night_fog"             -> R.drawable.noaa_night_fog
    else                    -> null
}

fun getWeatherIconModel(iconUrl: String): Any {
    return try {
        val path  = URL(iconUrl).path
        val parts = path.split("/")
        val tod   = parts.getOrNull(3) ?: return iconUrl
        val seg   = parts.getOrNull(4) ?: return iconUrl
        val cond  = seg.substringBefore(",").substringBefore("?")
        localWeatherIcon(tod, cond) ?: iconUrl
    } catch (_: Exception) { iconUrl }
}

// ── Network ───────────────────────────────────────────────────────────────────

@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(context: Context): Pair<Double, Double> =
    suspendCancellableCoroutine { cont ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Fast path: use a recent cached location if available (< 60 seconds old)
        val lastKnown = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }

        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 60_000L) {
            cont.resume(Pair(lastKnown.latitude, lastKnown.longitude))
            return@suspendCancellableCoroutine
        }

        // Slow path: request a fresh fix
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)     -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                cont.resumeWithException(Exception("No location provider available — enable GPS in Settings."))
                return@suspendCancellableCoroutine
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lm.removeUpdates(this)
                if (cont.isActive) cont.resume(Pair(location.latitude, location.longitude))
            }
            override fun onProviderDisabled(provider: String) {
                lm.removeUpdates(this)
                if (cont.isActive) cont.resumeWithException(Exception("Location provider disabled."))
            }
        }

        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps    = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun openConnection(urlStr: String): java.net.HttpURLConnection {
    val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
    conn.setRequestProperty("User-Agent", "MyWeatherApp/1.3 (contact@marshalllee.net)")
    conn.setRequestProperty("Accept", "application/geo+json")
    conn.connectTimeout = 10000
    conn.readTimeout    = 10000
    return conn
}

fun lookupByZip(zip: String): LocationConfig {
    val conn     = openConnection("https://api.zippopotam.us/us/$zip")
    val response = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    val json     = JSONObject(response)
    val place    = json.getJSONArray("places").getJSONObject(0)
    val lat      = place.getString("latitude").toDouble()
    val lon      = place.getString("longitude").toDouble()
    return lookupGridpoint(lat, lon)
}

fun lookupGridpoint(lat: Double, lon: Double): LocationConfig {
    val conn     = openConnection("https://api.weather.gov/points/$lat,$lon")
    val response = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    val props    = JSONObject(response).getJSONObject("properties")

    val gridId  = props.getString("gridId")
    val gridX   = props.getInt("gridX")
    val gridY   = props.getInt("gridY")
    val zone    = props.getString("forecastZone").substringAfterLast("/")
    val relLoc  = props.getJSONObject("relativeLocation").getJSONObject("properties")
    val city    = relLoc.getString("city")
    val state   = relLoc.getString("state")

    return LocationConfig(
        displayName  = "$city, $state",
        latitude     = lat,
        longitude    = lon,
        gridId       = gridId,
        gridX        = gridX,
        gridY        = gridY,
        forecastZone = zone
    )
}

fun fetchCurrentWeather(location: LocationConfig): CurrentWeather {
    val conn     = openConnection("https://api.weather.gov/gridpoints/${location.gridId}/${location.gridX},${location.gridY}/forecast/hourly")
    val response = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }

    val p = JSONObject(response)
        .getJSONObject("properties")
        .getJSONArray("periods")
        .getJSONObject(0)

    return CurrentWeather(
        temperature     = p.getInt("temperature"),
        temperatureUnit = p.getString("temperatureUnit"),
        windSpeed       = p.getString("windSpeed"),
        windDirection   = p.getString("windDirection"),
        shortForecast   = p.getString("shortForecast"),
        iconUrl         = p.getString("icon"),
        humidity        = p.optJSONObject("relativeHumidity")?.optInt("value")?.takeIf { it >= 0 },
        precipChance    = p.optJSONObject("probabilityOfPrecipitation")?.optInt("value")?.takeIf { it >= 0 }
    )
}

fun fetchAlerts(location: LocationConfig): List<WeatherAlert> {
    val conn     = openConnection("https://api.weather.gov/alerts/active?zone=${location.forecastZone}")
    val response = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }

    val features = JSONObject(response).getJSONArray("features")
    return (0 until features.length()).map { i ->
        val props = features.getJSONObject(i).getJSONObject("properties")
        WeatherAlert(
            event       = props.optString("event", "Alert"),
            headline    = props.optString("headline", ""),
            severity    = props.optString("severity", "Unknown"),
            onset       = props.optString("onset").takeIf { it.isNotBlank() },
            expires     = props.optString("expires").takeIf { it.isNotBlank() },
            description = props.optString("description", ""),
            instruction = props.optString("instruction").takeIf { it.isNotBlank() }
        )
    }
}

fun fetchForecast(location: LocationConfig): List<ForecastPeriod> {
    val conn     = openConnection("https://api.weather.gov/gridpoints/${location.gridId}/${location.gridX},${location.gridY}/forecast")
    val response = conn.inputStream.bufferedReader().readText().also { conn.disconnect() }

    val periods = JSONObject(response).getJSONObject("properties").getJSONArray("periods")
    return (0 until periods.length()).map { i ->
        val p = periods.getJSONObject(i)
        ForecastPeriod(
            name            = p.getString("name"),
            temperature     = p.getInt("temperature"),
            temperatureUnit = p.getString("temperatureUnit"),
            windSpeed       = p.getString("windSpeed"),
            windDirection   = p.getString("windDirection"),
            shortForecast   = p.getString("shortForecast"),
            isDaytime       = p.getBoolean("isDaytime"),
            iconUrl         = p.getString("icon")
        )
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyWeatherTheme { MainScreen() }
}
