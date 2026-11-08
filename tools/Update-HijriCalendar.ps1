<#
.SYNOPSIS
    Refreshes the app's Hijri month table from the HijriCalendarMaghrib repository.

.DESCRIPTION
    Downloads data/calendar.json from github.com/viryaka-lang/HijriCalendarMaghrib and rewrites
    app/src/main/assets/morocco_hijri_months.csv in the format Hijri.java reads. Prints which
    months changed. Rebuild and reinstall the app afterwards for the new dates to show.

.PARAMETER Branch
    Repository branch to read (default: main).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\Update-HijriCalendar.ps1
#>
param([string]$Branch = 'main')

$ErrorActionPreference = 'Stop'
$repo = 'viryaka-lang/HijriCalendarMaghrib'
$csvPath = Join-Path $PSScriptRoot '..\app\src\main\assets\morocco_hijri_months.csv'

# Arabic names aren't in calendar.json.
$arabic = @{
    1 = 'محرم'; 2 = 'صفر'; 3 = 'ربيع الأول'; 4 = 'ربيع الآخر'; 5 = 'جمادى الأولى'; 6 = 'جمادى الآخرة'
    7 = 'رجب'; 8 = 'شعبان'; 9 = 'رمضان'; 10 = 'شوال'; 11 = 'ذو القعدة'; 12 = 'ذو الحجة'
}

$url = "https://raw.githubusercontent.com/$repo/$Branch/data/calendar.json"
Write-Host "Downloading $url"
$json = (Invoke-WebRequest $url -UseBasicParsing -Headers @{ 'User-Agent' = 'RabatPrayerTimes-updater' }).Content

# ConvertFrom-Json in Windows PowerShell rejects this file (keys differing only in case).
Add-Type -AssemblyName System.Web.Extensions
$serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
$serializer.MaxJsonLength = [int]::MaxValue
$months = $serializer.DeserializeObject($json)['months']
if (-not $months -or $months.Count -lt 12) { throw "No months found in calendar.json" }

$old = @{}
if (Test-Path $csvPath) {
    foreach ($r in Import-Csv $csvPath -Encoding UTF8) {
        $old["$($r.hijri_year)-$($r.month_no)"] = "$($r.start_date) $($r.days)d $($r.status)"
    }
}

$culture = [Globalization.CultureInfo]::InvariantCulture
$lines = @('hijri_year,month_no,month_name,month_name_ar,start_date,start_weekday,days,status,start_probability')
$changes = @()
foreach ($m in $months) {
    $start = [datetime]::ParseExact($m['start'], 'yyyy-MM-dd', $culture)
    $probability = 1
    if ($m['status'] -ne 'official' -and $m['dist']) { $probability = [math]::Round([double]$m['dist'][$m['start']], 4) }
    $startText = $start.ToString('M/d/yyyy', $culture)
    $lines += '{0},{1},{2},{3},{4},{5},{6},{7},{8}' -f $m['hy'], $m['hm'], $m['name'], $arabic[[int]$m['hm']],
        $startText, $start.DayOfWeek, $m['len'], $m['status'], $probability.ToString($culture)

    $key = "$($m['hy'])-$($m['hm'])"
    $now = "$startText $($m['len'])d $($m['status'])"
    if (-not $old.ContainsKey($key)) { $changes += "  new      $key $($m['name']): $now" }
    elseif ($old[$key] -ne $now) { $changes += "  changed  $key $($m['name']): $($old[$key]) -> $now" }
}

# UTF-8 without BOM, as the app reads it.
[IO.File]::WriteAllText((Resolve-Path (Split-Path $csvPath)).Path + '\' + (Split-Path $csvPath -Leaf),
    ($lines -join "`n") + "`n", (New-Object Text.UTF8Encoding $false))

Write-Host "$($months.Count) months written to $csvPath"
if ($changes.Count -eq 0) {
    Write-Host 'No changes: the app already has these dates.'
} else {
    Write-Host "$($changes.Count) month(s) differ from before:"
    $changes | ForEach-Object { Write-Host $_ }
    Write-Host 'Rebuild and reinstall the app to use them.'
}
