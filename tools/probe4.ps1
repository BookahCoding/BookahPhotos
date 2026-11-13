# Read-only: speed of getting photos from Nextcloud (full original, preview, Memories EXIF info). Names never printed.
. D:\Projects\BookahPhotos\tools\nc.ps1
$uid = "<user>"; $root = "D:\Nextcloud\Photos"
$all = Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue | Where-Object { ([int]$_.Attributes) -band 0x400000 }
$rnd = New-Object Random 42
function Pick($ext, $n) { $c = @($all | Where-Object { $_.Extension -match $ext }); ($c | Sort-Object { $rnd.Next() } | Select-Object -First $n) }
$prop = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>'
function FileId($f) {
  $rel = ($f.FullName.Substring($root.Length).TrimStart('\') -split '\\' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
  $r = Invoke-Nc "/remote.php/dav/files/$uid/Photos/$rel" "PROPFIND" $prop @{Depth="0"}
  @{ id = [regex]::Match($r.Content, '<oc:fileid>(\d+)</oc:fileid>').Groups[1].Value; rel = $rel }
}
function Stat($label, $arr) { if ($arr.Count) { $s = $arr | Sort-Object; "{0}: n={1} median={2:N2}s p90={3:N2}s max={4:N2}s" -f $label, $arr.Count, $s[[int]($s.Count/2)], $s[[math]::Min($s.Count-1,[int]($s.Count*0.9))], $s[-1] } }
$tmp = "C:\BookahPC\bookahphotos\spike\probe.bin"
# 1. JPEG/HEIC originals: full download speed
$jp = Pick '^\.(jpg|jpeg|heic)$' 20; $dl = @(); $mb = 0; $prev1024 = @(); $prev256 = @(); $info = @(); $infoOk = 0; $infoSample = ""
foreach ($f in $jp) {
  $x = FileId $f
  $t = Get-Date; $r = Invoke-Nc "/remote.php/dav/files/$uid/Photos/$($x.rel)" "GET" $null @{} $tmp; $dl += ((Get-Date)-$t).TotalSeconds; $mb += $r.RawContentLength/1MB
  $t = Get-Date; try { Invoke-Nc "/core/preview?fileId=$($x.id)&x=256&y=256&a=true&mode=cover" "GET" $null @{} $tmp | Out-Null } catch {}; $prev256 += ((Get-Date)-$t).TotalSeconds
  $t = Get-Date; try { Invoke-Nc "/core/preview?fileId=$($x.id)&x=1024&y=1024&a=true" "GET" $null @{} $tmp | Out-Null } catch {}; $prev1024 += ((Get-Date)-$t).TotalSeconds
  $t = Get-Date; try { $r = Invoke-Nc "/index.php/apps/memories/api/image/info/$($x.id)" ; $info += ((Get-Date)-$t).TotalSeconds; $infoOk++; if (-not $infoSample) { $j = $r.Content | ConvertFrom-Json; $infoSample = ($j.PSObject.Properties.Name -join ",") } } catch { $info += ((Get-Date)-$t).TotalSeconds }
}
"JPEG/HEIC originals: {0:N1} MB total, average {1:N1} MB/s" -f $mb, ($mb / ($dl | Measure-Object -Sum).Sum)
Stat "  full GET" $dl; Stat "  preview 256 (cold)" $prev256; Stat "  preview 1024" $prev1024; Stat "  Memories image/info" $info
"  Memories info answered for $infoOk of $($jp.Count); fields: $infoSample"
# 2. TIFF and MOV: preview only, never the original
foreach ($e in @('^\.(tif|tiff)$', '^\.(mov|mp4)$')) {
  $a = Pick $e 5; $p = @(); $sizes = @(); $okc = 0
  foreach ($f in $a) { $x = FileId $f; $sizes += $f.Length/1MB; $t = Get-Date; try { Invoke-Nc "/core/preview?fileId=$($x.id)&x=256&y=256&a=true&mode=cover" "GET" $null @{} $tmp | Out-Null; $okc++ } catch {}; $p += ((Get-Date)-$t).TotalSeconds }
  "{0}: sizes MB {1}; preview256 ok {2}/5" -f $e, (($sizes | ForEach-Object { [math]::Round($_) }) -join ","), $okc; Stat "  preview 256 (cold)" $p
}
Remove-Item $tmp -ErrorAction SilentlyContinue
