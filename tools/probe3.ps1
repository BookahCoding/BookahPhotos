New-Item -ItemType Directory -Force "C:\BookahPC\bookahphotos\spike" | Out-Null
. D:\Projects\BookahPhotos\tools\nc.ps1
$uid = "<user>"
# 1. Accurate placeholder count: attribute bits as integers (0x400000 RECALL_ON_DATA_ACCESS, 0x40000 RECALL_ON_OPEN, 0x1000 OFFLINE)
$files = Get-ChildItem "D:\Nextcloud\Photos" -Recurse -File -ErrorAction SilentlyContinue
$cnt = @{ online=0; present=0 }; $presentBytes = 0; $onlineBytes = 0
foreach ($f in $files) { $a = [int]$f.Attributes
  if (($a -band 0x400000) -or ($a -band 0x40000) -or ($a -band 0x1000)) { $cnt.online++; $onlineBytes += $f.Length } else { $cnt.present++; $presentBytes += $f.Length } }
Write-Host ("online-only: {0} files {1:N1} GB | on disk: {2} files {3:N1} GB" -f $cnt.online, ($onlineBytes/1GB), $cnt.present, ($presentBytes/1GB))
# 2. API test on one small JPEG that is online-only (to see preview cost without downloading it)
$one = $files | Where-Object { $_.Extension -match '^\.jpe?g$' -and (([int]$_.Attributes) -band 0x400000) } | Select-Object -First 1
if (-not $one) { $one = $files | Where-Object { $_.Extension -match '^\.jpe?g$' } | Select-Object -First 1 }
$rel = ($one.FullName.Substring("D:\Nextcloud\Photos".Length).TrimStart('\') -split '\\' | ForEach-Object { [Uri]::EscapeDataString($_) }) -join '/'
$propBody = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><oc:fileid/><d:getetag/><d:getcontentlength/><oc:favorite/><nc:has-preview/></d:prop></d:propfind>'
$r = Invoke-Nc "/remote.php/dav/files/$uid/Photos/$rel" "PROPFIND" $propBody @{Depth="0"}
$id = [regex]::Match($r.Content, '<oc:fileid>(\d+)</oc:fileid>').Groups[1].Value
Write-Host "test file id=$id size=$($one.Length) bytes, local file online-only=$((([int]$one.Attributes) -band 0x400000) -ne 0)"
foreach ($ep in @("/core/preview?fileId=$id&x=256&y=256&a=true&mode=cover", "/index.php/apps/memories/api/image/preview/$($id)?x=256&y=256&a=true", "/index.php/apps/memories/api/image/preview/$($id)?x=1024&y=1024&a=true")) {
  foreach ($try in 1,2) {
    $t = Get-Date
    try { $p = Invoke-Nc $ep "GET" $null @{} "C:\BookahPC\bookahphotos\spike\prev.bin"; Write-Host ("{0} try{1}: HTTP {2}, {3} bytes, {4:N2}s, {5}" -f $ep.Split('?')[0], $try, $p.StatusCode, $p.RawContentLength, ((Get-Date)-$t).TotalSeconds, $p.Headers['Content-Type']) }
    catch { Write-Host "$($ep.Split('?')[0]) try$try ERROR $($_.Exception.Message)" }
  }
}
# 3. Favourites and albums: details
$favBody = '<?xml version="1.0"?><oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/><d:getcontenttype/></d:prop><oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules></oc:filter-files>'
$f = Invoke-Nc "/remote.php/dav/files/$uid" "REPORT" $favBody
Write-Host ("favourites: " + ([regex]::Matches($f.Content,'<d:response>')).Count + " entries; types: " + (([regex]::Matches($f.Content,'<d:getcontenttype>([^<]*)') | ForEach-Object { $_.Groups[1].Value }) -join ","))
$a = Invoke-Nc "/remote.php/dav/photos/$uid/albums" "PROPFIND" $null @{Depth="1"}
$names = [regex]::Matches($a.Content, '<d:href>[^<]*/albums/([^<]*)/</d:href>') | ForEach-Object { [Uri]::UnescapeDataString($_.Groups[1].Value) }
Write-Host "albums: $($names.Count) -> $($names -join ' | ')"
if ($names.Count) { $ab = Invoke-Nc "/remote.php/dav/photos/$uid/albums/$([Uri]::EscapeDataString($names[0]))" "PROPFIND" '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><oc:fileid/><nc:file-metadata-size/></d:prop></d:propfind>' @{Depth="1"}; Write-Host "first album members: $(([regex]::Matches($ab.Content,'<d:response>')).Count - 1)" }
