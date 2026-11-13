# Helper: read the stored app password from Credential Manager and make Nextcloud requests.
# Dot-source it:  . D:\Projects\BookahPhotos\tools\nc.ps1
# The password stays in memory only and is never printed.
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class CredReader {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  public struct CREDENTIAL { public int Flags; public int Type; public string TargetName; public string Comment;
    public long LastWritten; public int CredentialBlobSize; public IntPtr CredentialBlob; public int Persist;
    public int AttributeCount; public IntPtr Attributes; public string TargetAlias; public string UserName; }
  [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  public static extern bool CredRead(string target, int type, int flags, out IntPtr cred);
  [DllImport("advapi32.dll")] public static extern void CredFree(IntPtr cred);
  public static string[] Read(string target) {
    IntPtr p; if (!CredRead(target, 1, 0, out p)) return null;
    CREDENTIAL c = (CREDENTIAL)Marshal.PtrToStructure(p, typeof(CREDENTIAL));
    string pw = Marshal.PtrToStringUni(c.CredentialBlob, c.CredentialBlobSize / 2);
    string u = c.UserName; CredFree(p); return new string[]{u, pw};
  }
}
"@
$cred = [CredReader]::Read("BookahPhotos-nextcloud")
if (-not $cred) { throw "Credential BookahPhotos-nextcloud not found" }
$script:NcUser = $cred[0]
$script:NcBase = $env:BOOKAH_NC_BASE   # your own Nextcloud address, for example https://cloud.example.ts.net
$script:NcAuth = "Basic " + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($cred[0] + ":" + $cred[1]))
$cred = $null
Add-Type -AssemblyName System.Net.Http
$script:Http = New-Object System.Net.Http.HttpClient
$script:Http.Timeout = [TimeSpan]::FromSeconds(120)
function Invoke-Nc {
  # Uses HttpClient because Windows PowerShell 5.1 Invoke-WebRequest rejects PROPFIND/REPORT.
  param([string]$Path, [string]$Method = "GET", [string]$Body = $null, [hashtable]$Headers = @{}, [string]$OutFile = $null)
  $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($Method), ($script:NcBase + $Path))
  $req.Headers.TryAddWithoutValidation("Authorization", $script:NcAuth) | Out-Null
  $req.Headers.TryAddWithoutValidation("OCS-APIRequest", "true") | Out-Null
  foreach ($k in $Headers.Keys) { $req.Headers.TryAddWithoutValidation($k, [string]$Headers[$k]) | Out-Null }
  if ($Body) { $req.Content = New-Object System.Net.Http.StringContent($Body, [Text.Encoding]::UTF8, "application/xml") }
  $resp = $script:Http.SendAsync($req).GetAwaiter().GetResult()
  $bytes = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
  if ($OutFile) { [IO.File]::WriteAllBytes($OutFile, $bytes) }
  $code = [int]$resp.StatusCode
  if ($code -ge 400) { throw "HTTP $code $($resp.ReasonPhrase)" }
  $ct = if ($resp.Content.Headers.ContentType) { $resp.Content.Headers.ContentType.ToString() } else { "" }
  [pscustomobject]@{ StatusCode = $code; Content = [Text.Encoding]::UTF8.GetString($bytes); RawContentLength = $bytes.Length; Headers = @{ "Content-Type" = $ct } }
}
