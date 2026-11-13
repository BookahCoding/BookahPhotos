# Read-only inventory of the synced Photos folder. Lists names and sizes only; never opens file contents.
$root = "D:\Nextcloud\Photos"
$files = Get-ChildItem $root -Recurse -File -ErrorAction SilentlyContinue
Write-Host "total files: $($files.Count)  GB: $([math]::Round(($files|Measure-Object Length -Sum).Sum/1GB,1))"
Write-Host "--- by extension (count, GB)"
$files | Group-Object { $_.Extension.ToLower() } | Sort-Object Count -Descending | Select-Object -First 15 |
  ForEach-Object { "{0,-8} {1,6}  {2,7:N1}" -f $_.Name, $_.Count, (($_.Group|Measure-Object Length -Sum).Sum/1GB) }
Write-Host "--- by year (last-write time)"
$files | Group-Object { $_.LastWriteTime.Year } | Sort-Object Name | ForEach-Object { "{0} {1,6}" -f $_.Name, $_.Count }
Write-Host "--- top-level subfolders"
Get-ChildItem $root -Directory | ForEach-Object { $c=(Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count; "{0,-30} {1,6}" -f "(folder $($_.Name.Length) chars)", $c } | Select-Object -First 30
$notOnline = $files | Where-Object { -not ($_.Attributes -band 0x400000) -and -not ($_.Attributes -band 0x40000) }
Write-Host "files actually present on disk (not placeholders): $($notOnline.Count)"
$one = $files | Where-Object { $_.Extension -match 'jpe?g' } | Select-Object -First 1
if ($one) { $rel = $one.FullName.Substring($root.Length).TrimStart('\') -replace '\\','/'; Set-Content "C:\BookahPC\bookahphotos\spike\sample-rel-path.txt" $rel -Encoding UTF8; Write-Host "saved one relative path for the API test" }
