# Read-only: checksums, folder etags, Memories bulk (days) API, and Memories date handling for a few local files.
. D:\Projects\BookahPhotos\tools\nc.ps1
$uid = "<user>"; $root = "D:\Nextcloud\Photos"; $out = "C:\BookahPC\bookahphotos\spike\probe5.json"
$res = [ordered]@{}
# 1. checksums + etag on the root folder and on a few files
$prop = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><oc:checksums/><d:getetag/><oc:fileid/><oc:size/><nc:upload_time/></d:prop></d:propfind>'
$r = Invoke-Nc "/remote.php/dav/files/$uid/Photos" "PROPFIND" $prop @{Depth="0"}
$res.rootPropfind = $r.Content.Substring(0, [Math]::Min(900, $r.Content.Length))
$files = Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue
function RelUrl($f) { ($f.FullName.Substring($root.Length).TrimStart('\') -split '\\' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/' }
$sel = @()
$rnd = New-Object Random 7
function Pick($filter, $n) { @($files | Where-Object $filter | Sort-Object { $rnd.Next() } | Select-Object -First $n) }
$present = { -not (([int]$_.Attributes) -band 0x400000) }
$groups = @(
  @{ kind = "heic";  f = Pick { $_.Extension -match '^\.heic$' -and (& $present) } 3 },
  @{ kind = "jpg";   f = Pick { $_.Extension -match '^\.jpe?g$' -and (& $present) } 5 },
  @{ kind = "png";   f = Pick { $_.Extension -match '^\.png$' } 2 },
  @{ kind = "mov";   f = Pick { $_.Extension -match '^\.mov$' -and (& $present) } 2 },
  @{ kind = "tiff";  f = Pick { $_.Extension -match '^\.tiff?$' } 2 }
)
$items = @()
foreach ($g in $groups) { foreach ($f in $g.f) {
  $p = Invoke-Nc "/remote.php/dav/files/$uid/Photos/$(RelUrl $f)" "PROPFIND" $prop @{Depth="0"}
  $id = [regex]::Match($p.Content, '<oc:fileid>(\d+)</oc:fileid>').Groups[1].Value
  $chk = [regex]::Match($p.Content, '<oc:checksums>(.*?)</oc:checksums>').Groups[1].Value
  $info = $null; try { $info = (Invoke-Nc "/index.php/apps/memories/api/image/info/$id").Content | ConvertFrom-Json } catch {}
  $items += [ordered]@{ kind = $g.kind; local = $f.FullName; id = $id; checksums = $chk; datetaken = $info.datetaken; mtime = $info.mtime; exif = $info.exif; w = $info.w; h = $info.h }
} }
$res.items = $items
# 2. bulk days API
try { $d = Invoke-Nc "/index.php/apps/memories/api/days"; $dj = $d.Content | ConvertFrom-Json; $res.daysCount = $dj.Count; $res.daysFirst = ($dj | Select-Object -First 2 | ConvertTo-Json -Compress); $res.daysTotalPhotos = ($dj | Measure-Object -Property count -Sum).Sum
  $one = $dj | Select-Object -First 1; $dd = Invoke-Nc "/index.php/apps/memories/api/days/$($one.dayid)"; $res.dayOneSample = $dd.Content.Substring(0, [Math]::Min(700, $dd.Content.Length)) } catch { $res.daysError = $_.Exception.Message }
$res | ConvertTo-Json -Depth 6 | Set-Content $out -Encoding UTF8
"saved $out"
