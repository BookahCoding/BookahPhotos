$ErrorActionPreference = "Continue"
. D:\Projects\BookahPhotos\tools\nc.ps1
"user=$NcUser"
function Try-Req($label, [scriptblock]$sb) {
  try { $r = & $sb; "[$label] HTTP $($r.StatusCode) len=$($r.Content.Length)"; return $r }
  catch { "[$label] ERROR $($_.Exception.Message)"; return $null }
}
$st = Try-Req "status.php" { Invoke-WebRequest "$NcBase/status.php" -UseBasicParsing }
if ($st) { $st.Content }
$cap = Try-Req "capabilities" { Invoke-Nc "/ocs/v1.php/cloud/capabilities?format=json" }
if ($cap) {
  $j = $cap.Content | ConvertFrom-Json
  "version: " + $j.ocs.data.version.string
  "capability apps: " + (($j.ocs.data.capabilities.PSObject.Properties.Name) -join ", ")
}
$m = Try-Req "memories describe" { Invoke-Nc "/apps/memories/api/describe" }
if ($m) { $m.Content.Substring(0,[Math]::Min(400,$m.Content.Length)) }
$a = Try-Req "albums list" { Invoke-Nc "/remote.php/dav/photos/$NcUser/albums" "PROPFIND" $null @{Depth="1"} }
if ($a) { "albums xml head: " + $a.Content.Substring(0,[Math]::Min(600,$a.Content.Length)) }
