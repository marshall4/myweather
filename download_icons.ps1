$conditions = @('skc','few','sct','bkn','ovc','wind_skc','wind_few','wind_sct','wind_bkn','wind_ovc','snow','rain_snow','rain_sleet','snow_sleet','fzra','rain_fzra','snow_fzra','sleet','rain','rain_showers','rain_showers_hi','tsra','tsra_sct','tsra_hi','tornado','hurricane','tropical_storm','dust','smoke','haze','hot','cold','blizzard','fog')
$outDir = 'C:\Users\marsh\AndroidStudioProjects\MyWeather\app\src\main\res\drawable'
$ok = 0; $fail = 0

foreach ($tod in @('day','night')) {
    foreach ($cond in $conditions) {
        $url  = "https://api.weather.gov/icons/land/$tod/$cond?size=medium"
        $file = "$outDir\noaa_${tod}_${cond}.png"
        $status = & curl.exe -s -o $file -w "%{http_code}" --max-time 15 -H "User-Agent: MyWeatherApp/1.2 (contact@marshalllee.net)" $url 2>&1
        if ($status -eq "200") {
            $ok++
            Write-Host "OK: noaa_${tod}_${cond}.png"
        } else {
            $fail++
            Remove-Item $file -ErrorAction SilentlyContinue
            Write-Host "FAIL ($status): $tod/$cond"
        }
    }
}
Write-Host ""
Write-Host "Done: $ok downloaded, $fail failed"
