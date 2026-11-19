<#
Installs (or removes) the Windows Task Scheduler job "BookahPhotos daily index". NOT RUN unless Chris says yes.
Run on BookahPC in Windows PowerShell:   powershell -ExecutionPolicy Bypass -File D:\Projects\BookahPhotos\tools\install-daily-task.ps1
  -Remove      deletes the job again
  -At 14:00    time of day (default 14:00)
  -Minutes 60  total time budget of one run (default 60)
It runs `bookahphotos-indexer daily` as the current user, only while that user is logged on, never wakes the PC, starts only if the network is up,
ignores a second start while one is running, and is stopped after 2 hours at the latest. Nothing else on the PC is changed.
#>
param([switch]$Remove, [string]$At = "14:00", [int]$Minutes = 60,
      # repeat every N hours for RepeatHours hours after -At (0 = once a day only). Example: -At 06:00 -EveryHours 3 -RepeatHours 17 runs at 06, 09, 12, 15, 18, 21.
      [int]$EveryHours = 0, [int]$RepeatHours = 17)

$name = "BookahPhotos daily index"
if ($Remove) {
    Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
    "Removed '$name' (if it existed)."
    return
}
$cmd = "Set-Location 'D:\Projects\BookahPhotos\indexer'; uv run --extra content bookahphotos-indexer daily --minutes $Minutes"
$action   = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -WindowStyle Hidden -Command `"$cmd`""
$trigger  = New-ScheduledTaskTrigger -Daily -At $At
if ($EveryHours -gt 0) {
    $rep = New-ScheduledTaskTrigger -Once -At $At -RepetitionInterval (New-TimeSpan -Hours $EveryHours) -RepetitionDuration (New-TimeSpan -Hours $RepeatHours)
    $trigger.Repetition = $rep.Repetition
}
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable:$false -WakeToRun:$false -RunOnlyIfNetworkAvailable -MultipleInstances IgnoreNew `
            -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
$when = if ($EveryHours -gt 0) { "every day from $At, then every $EveryHours hours for $RepeatHours hours" } else { "every day at $At" }
"Installed '$name': $when, up to $Minutes minutes per run, only while $env:USERNAME is logged on, never wakes the PC."
"Look at it in Task Scheduler, or run it now with:  Start-ScheduledTask -TaskName '$name'"
