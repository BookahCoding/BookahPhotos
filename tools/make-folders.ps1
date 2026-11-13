# Creates BookahPhotos/, BookahPhotos/test-root/ and BookahPhotos/index/ in Chris's Nextcloud (approved 11 Nov 2026).
# Creates folders only; writes no files; never touches Photos. MKCOL on an existing folder returns 405 and is harmless.
. D:\Projects\BookahPhotos\tools\nc.ps1
$uid = "<user>"
foreach ($p in @("BookahPhotos", "BookahPhotos/test-root", "BookahPhotos/index")) {
  try { $r = Invoke-Nc "/remote.php/dav/files/$uid/$p" "MKCOL"; "MKCOL $p -> HTTP $($r.StatusCode)" }
  catch { "MKCOL $p -> $($_.Exception.Message)" }
}
$prop = '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><d:getetag/><oc:fileid/></d:prop></d:propfind>'
$r = Invoke-Nc "/remote.php/dav/files/$uid/BookahPhotos" "PROPFIND" $prop @{Depth="1"}
"children of BookahPhotos: " + (([regex]::Matches($r.Content, '<d:href>[^<]*/BookahPhotos/([^<]*)</d:href>') | ForEach-Object { $_.Groups[1].Value }) -join ", ")
