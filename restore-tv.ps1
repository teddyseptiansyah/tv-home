# Restore script for TCL Google TV debloat + launcher
$ErrorActionPreference = 'Stop'
$py = 'tools\venv\Scripts\python.exe'
$cmds = New-Object System.Collections.Generic.List[string]
foreach ($line in (Get-Content 'debloat-tcl-g13.txt')) {
    $t = $line.Trim()
    if ($t -ne '' -and -not $t.StartsWith('#')) {
        $cmds.Add("pm enable $t")
    }
}
$cmds.Add('pm enable com.google.android.tungsten.setupwraith')
$cmds.Add('pm enable com.google.android.apps.tv.launcherx')
$cmds.Add('cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity')
$cmds.Add('am force-stop com.mihir.tvhome')
$cmds.Add('input keyevent KEYCODE_HOME')
$tmp = [System.IO.Path]::GetTempFileName()
$cmds | Set-Content $tmp
Get-Content $tmp | & $py -I tools\tv.py --batch
Remove-Item $tmp
Write-Host 'Restored. Press Home on the remote.'
