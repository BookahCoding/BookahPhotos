$ErrorActionPreference = "Continue"
. D:\Projects\BookahPhotos\tools\nc.ps1
$uid = "<user>"
function Try-Req($label, [scriptblock]$sb) {
  try { $r = & $sb; Write-Host "[$label] HTTP $($r.StatusCode) len=$($r.RawContentLength)"; return $r }
  catch { Write-Host "[$label] ERROR $($_.Exception.Message)"; return $null }
}
$propBody = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns"><d:prop><oc:fileid/><d:getetag/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/><oc:favorite/><nc:has-preview/></d:prop></d:propfind>'
$t = Get-Date
$r = Try-Req "PROPFIND Photos depth1" { Invoke-Nc "/remote.php/dav/files/$uid/Photos" "PROPFIND" $propBody @{Depth="1"} }
Write-Host ("  took {0:N1}s" -f ((Get-Date)-$t).TotalSeconds)
if ($r) {
  [xml]$x = $r.Content
  $ns = New-Object Xml.XmlNamespaceManager($x.NameTable); $ns.AddNamespace("d","DAV:"); $ns.AddNamespace("oc","http://owncloud.org/ns")
  $resp = $x.SelectNodes("//d:response",$ns)
  Write-Host "  entries: $($resp.Count)"
  $files = @(); foreach($e in $resp){ $ct=$e.SelectSingleNode(".//d:getcontenttype",$ns); $id=$e.SelectSingleNode(".//oc:fileid",$ns); if($ct -and $ct.InnerText -match '^(image|video)/'){ $files += [pscustomobject]@{id=$id.InnerText; ct=$ct.InnerText; href=$e.href} } }
  Write-Host "  media files at top level: $($files.Count)"
  $files | Select-Object -First 3 | ForEach-Object { "  sample id=$($_.id) type=$($_.ct)" }
  $first = $files | Select-Object -First 1
  if ($first) {
    foreach($ep in @("/core/preview?fileId=$($first.id)&x=256&y=256&a=true&mode=cover", "/index.php/apps/memories/api/image/preview/$($first.id)?x=256&y=256&a=true")) {
      $tmp = "C:\BookahPC\bookahphotos\spike\prev.bin"; New-Item -ItemType Directory -Force (Split-Path $tmp) | Out-Null
      $t = Get-Date
      $p = Try-Req "preview $($ep.Split('?')[0])" { Invoke-Nc $ep "GET" $null @{} $tmp }
      Write-Host ("  took {0:N2}s" -f ((Get-Date)-$t).TotalSeconds)
      if ($p) { Write-Host "  content-type: $($p.Headers['Content-Type'])" }
    }
  }
}
$favBody = '<?xml version="1.0"?><oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop><oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules></oc:filter-files>'
$f = Try-Req "REPORT favourites" { Invoke-Nc "/remote.php/dav/files/$uid" "REPORT" $favBody @{} }
if ($f) { Write-Host "  favourite entries: $(([regex]::Matches($f.Content,'<d:response>')).Count)" }
$a = Try-Req "PROPFIND albums" { Invoke-Nc "/remote.php/dav/photos/$uid/albums" "PROPFIND" $null @{Depth="1"} }
if ($a) { Write-Host "  album entries: $(([regex]::Matches($a.Content,'<d:response>')).Count)" }
