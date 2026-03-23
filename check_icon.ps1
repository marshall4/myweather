$headers = @{ 'User-Agent' = 'MyWeatherApp/1.2 (contact@marshalllee.net)'; 'Accept' = 'application/geo+json' }
$resp = Invoke-WebRequest -Uri 'https://api.weather.gov/gridpoints/REV/41,114/forecast/hourly' -Headers $headers -UseBasicParsing
$raw = $resp.Content
# Find the first icon URL using regex
$matches = [regex]::Matches($raw, '"icon"\s*:\s*"([^"]+)"')
for ($i = 0; $i -lt [Math]::Min(3, $matches.Count); $i++) {
    Write-Host "Icon $i : $($matches[$i].Groups[1].Value)"
}
