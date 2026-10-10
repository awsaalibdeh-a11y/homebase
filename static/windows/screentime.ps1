<#
  Homebase screen time for Windows
  ---------------------------------
  Counts the minutes you spend ACTIVELY PLAYING or WATCHING, and the apps and sites they were in. Nothing else:
    - not when you are away (no mouse or keyboard for 2.5 minutes), the screen is locked, or you are on homework,
      documents, email or other work
    - it never reads what is on your screen, your files, passwords or what you type. It only looks at which program
      is in front, the title of its window (to tell YouTube from a homework tab) and how long since you last touched
      the mouse or keyboard.
  It also does two more things you can see: it shows a friendly nudge every 20 minutes ("ride your bike", "bring Mum a glass of
  water"), and when a parent locks your devices in Homebase (never more than 4 hours a day) it locks Windows until the time is up.
  Every few minutes it sends the totals to YOUR Homebase family. You can see exactly the same numbers in Homebase.
  Your parents see them only while "See my screen time" is switched on in your Homebase settings.

  To start:   right-click this file, choose "Run with PowerShell"
  To remove:  run it again from the Homebase folder with  -Remove   (or delete the "Homebase screen time" Startup shortcut)

  This file was made for one person and contains their private Homebase key. Do not share it.
#>
param([switch]$Run, [switch]$Remove)

$Server = "{{SERVER}}"
$Token  = "{{TOKEN}}"
$Dir     = Join-Path $env:APPDATA "Homebase"
$Self    = Join-Path $Dir "Homebase-ScreenTime.ps1"
$State   = Join-Path $Dir "today.json"
$Startup = Join-Path ([Environment]::GetFolderPath("Startup")) "Homebase screen time.lnk"
$ActiveSeconds = 150
$Tick = if ($env:HB_TICK) { [int]$env:HB_TICK } else { 10 }      # seconds between looks (only changed by tests)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Stop-Tracker {
    Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" | Where-Object { $_.CommandLine -like "*Homebase-ScreenTime.ps1*-Run*" -and $_.ProcessId -ne $PID } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Remove) {
    Stop-Tracker
    Remove-Item $Startup -Force -ErrorAction SilentlyContinue
    Remove-Item $Dir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Homebase screen time is removed from this computer." -ForegroundColor Green
    Read-Host "Press Enter to close"
    exit
}

if (-not $Run) {
    # first run: install quietly for this user, start with Windows, and begin
    Write-Host ""
    Write-Host "  Homebase screen time" -ForegroundColor Cyan
    Write-Host "  Counts only time spent playing or watching (not idle time, not homework)."
    Write-Host "  It sends minutes per app to your Homebase family. It never sees what is on your screen."
    Write-Host ""
    New-Item -ItemType Directory -Force $Dir | Out-Null
    Copy-Item $MyInvocation.MyCommand.Path $Self -Force
    Stop-Tracker
    $ws = New-Object -ComObject WScript.Shell
    $lnk = $ws.CreateShortcut($Startup)
    $lnk.TargetPath = "powershell.exe"
    $lnk.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Self`" -Run"
    $lnk.WindowStyle = 7
    $lnk.Save()
    Start-Process powershell.exe -ArgumentList "-NoProfile", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", "`"$Self`"", "-Run"
    Write-Host "  Done. It is running now and starts with Windows." -ForegroundColor Green
    Write-Host "  To remove it later: run  $Self -Remove" -ForegroundColor DarkGray
    Write-Host ""
    Read-Host "  Press Enter to close this window"
    exit
}

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class HBWin {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] struct LII { public uint cb; public uint t; }
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern bool GetLastInputInfo(ref LII p);
    [DllImport("user32.dll")] static extern int GetSystemMetrics(int i);
    public static double Idle() {
        LII l = new LII(); l.cb = (uint)Marshal.SizeOf(l); GetLastInputInfo(ref l);
        return unchecked((uint)Environment.TickCount - l.t) / 1000.0;
    }
    public static string Front(out uint pid, out bool full) {
        pid = 0; full = false;
        IntPtr h = GetForegroundWindow(); if (h == IntPtr.Zero) return "";
        GetWindowThreadProcessId(h, out pid);
        StringBuilder sb = new StringBuilder(300); GetWindowText(h, sb, 300);
        RECT r; if (GetWindowRect(h, out r)) full = (r.R - r.L) >= GetSystemMetrics(0) && (r.B - r.T) >= GetSystemMetrics(1);
        return sb.ToString();
    }
}
"@

$Browsers = "chrome", "msedge", "firefox", "brave", "opera", "opera_gx", "vivaldi", "iexplore"
$WatchSites = @(@("YouTube", "YouTube"), @("Netflix", "Netflix"), @("Twitch", "Twitch"), @("Disney\+|Disney Plus", "Disney+"), @("Prime Video", "Prime Video"),
                @("Crunchyroll", "Crunchyroll"), @("Hulu", "Hulu"), @("Vimeo", "Vimeo"))
$SocialSites = @(@("TikTok", "TikTok"), @("Instagram", "Instagram"), @("Facebook", "Facebook"), @("Snapchat", "Snapchat"), @("Reddit", "Reddit"), @("Twitter|(^|[ \-])X($|[ \-])", "X (Twitter)"))
$WatchApps = "vlc", "mpv", "wmplayer", "video.ui", "netflix", "disneyplus", "primevideo", "plex", "kodi", "potplayermini64", "stremio"
$GameNames = "^(minecraft|roblox.*|fortnite.*|valorant.*|overwatch.*|cs2|csgo|dota2|r5apex|gta5|eldenring|terraria|stardew valley|amongus|rocketleague|fallguys.*|palworld.*|hogwarts.*|league of legends|genshinimpact|yuanshen|apex.*|rainbowsix.*|fifa.*|eafc.*|cyberpunk2077|witcher3|skyrim.*|portal2|hl2|left4dead2|tf2|brawlhalla|osu!|lethalcompany|helldivers2|content warning)$"
$Launchers = "steam", "steamwebhelper", "epicgameslauncher", "riotclientservices", "riotclientux", "battle.net", "origin", "ubisoftconnect", "gog galaxy", "galaxyclient"

function Classify($proc, $title, $path, $full) {
    $n = $proc.ToLower()
    if ($Browsers -contains $n) {
        foreach ($s in $WatchSites)  { if ($title -match $s[0]) { return @{ pkg = "pc:web:" + $s[1]; label = $s[1] + " (in browser)"; cat = "watch" } } }
        foreach ($s in $SocialSites) { if ($title -match $s[0]) { return @{ pkg = "pc:web:" + $s[1]; label = $s[1] + " (in browser)"; cat = "social" } } }
        return $null                                                  # an ordinary web page is treated as homework or reading
    }
    if ($Launchers -contains $n) { return $null }
    if ($WatchApps -contains $n) { return @{ pkg = "pc:" + $n; label = $proc; cat = "watch" } }
    if ($n -match "^javaw?$") { if ($title -match "Minecraft") { return @{ pkg = "pc:minecraft"; label = "Minecraft"; cat = "play" } }; return $null }
    if ($n -match $GameNames) { return @{ pkg = "pc:" + $n; label = $proc; cat = "play" } }
    if ($path -and ($path -match "\\steamapps\\common\\" -or $path -match "\\Epic Games\\" -or $path -match "\\Riot Games\\" -or $path -match "\\XboxGames\\")) { return @{ pkg = "pc:" + $n; label = $proc; cat = "play" } }
    return $null
}

$day = (Get-Date).ToString("yyyy-MM-dd")
$apps = @{}
if (Test-Path $State) {
    try {
        $saved = Get-Content $State -Raw | ConvertFrom-Json
        if ($saved.day -eq $day) { foreach ($p in $saved.apps.PSObject.Properties) { $apps[$p.Name] = @{ label = $p.Value.label; cat = $p.Value.cat; sec = [int]$p.Value.sec } } }
    } catch { }
}
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$Notify = New-Object System.Windows.Forms.NotifyIcon
$Notify.Icon = [System.Drawing.SystemIcons]::Information
$Notify.Visible = $true
function Show-Note($title, $text) { try { $Notify.BalloonTipTitle = $title; $Notify.BalloonTipText = $text; $Notify.ShowBalloonTip(12000) } catch { } }
function Ask($path) { Invoke-RestMethod -Uri "$Server$path" -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 8 }
$cursor = -1
$lastTick = Get-Date
$lastSend = Get-Date "2000-01-01"
$dirty = $false

function Save-State { try { New-Item -ItemType Directory -Force $Dir | Out-Null; (@{ day = $day; apps = $apps } | ConvertTo-Json -Depth 4 -Compress) | Set-Content $State -Encoding UTF8 } catch { } }
function Send-Totals {
    $list = @(); foreach ($k in $apps.Keys) { $list += @{ pkg = $k; label = $apps[$k].label; cat = $apps[$k].cat; sec = [int]$apps[$k].sec } }
    $body = ConvertTo-Json -InputObject @{ day = $day; apps = $list } -Depth 4 -Compress
    try {
        Invoke-RestMethod -Method Post -Uri "$Server/api/screentime" -Headers @{ Authorization = "Bearer $Token" } -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 20 | Out-Null
        return $true
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 401) { Remove-Item $Startup -Force -ErrorAction SilentlyContinue; exit }   # no longer in the family
        return $false
    }
}

while ($true) {
    Start-Sleep -Seconds $Tick
    $now = Get-Date
    $elapsed = [Math]::Min(45, ($now - $lastTick).TotalSeconds); $lastTick = $now
    $today = $now.ToString("yyyy-MM-dd")
    $locked = $false
    try {                                                              # a parent's lock (never more than 4 hours a day): lock Windows until it ends
        $st = Ask "/api/lock"
        if ($st.lock -and $st.lock.until -gt $st.now) { $locked = $true; Start-Process rundll32.exe -ArgumentList "user32.dll,LockWorkStation" }
    } catch { }
    try {                                                              # friendly nudges and anything new for this child
        $ev = Ask "/api/events?after=$cursor&wait=0"
        $cursor = $ev.cursor
        foreach ($e in $ev.events) { if ($e.type -eq "nudge") { Show-Note "Time for a break?" $e.payload.text } }
    } catch { }
    if ($locked) { continue }
    if ($today -ne $day) { if ($dirty) { Send-Totals | Out-Null }; $day = $today; $apps = @{}; $dirty = $false }
    try {
        if ($env:HB_FAKE) { $f = $env:HB_FAKE -split "\|"; $pname = $f[0]; $title = $f[1]; $idle = [double]$f[2]; $full = ($f[3] -eq "1"); $path = $null; $have = $true }   # tests only
        else {
            $idle = [HBWin]::Idle()
            $pid2 = [uint32]0; $full = $false
            $title = [HBWin]::Front([ref]$pid2, [ref]$full)
            $have = $false
            if ($pid2 -gt 0) { $p = Get-Process -Id $pid2 -ErrorAction Stop; $pname = $p.ProcessName; $path = $null; try { $path = $p.Path } catch { }; $have = $true }
        }
        if ($have) {
            if ($pname -notin "LockApp", "LogonUI", "SearchHost", "explorer") {
                $c = Classify $pname $title $path $full
                if ($c) {
                    $active = switch ($c.cat) { "watch" { ($idle -le $ActiveSeconds) -or $full } default { $idle -le $ActiveSeconds } }
                    if ($active) {
                        if (-not $apps.ContainsKey($c.pkg)) { $apps[$c.pkg] = @{ label = $c.label; cat = $c.cat; sec = 0 } }
                        $apps[$c.pkg].sec = [int]$apps[$c.pkg].sec + [int]$elapsed
                        $dirty = $true
                    }
                }
            }
        }
    } catch { }
    if ($dirty -and ($now - $lastSend).TotalSeconds -ge $(if ($env:HB_TICK) { 1 } else { 120 })) { if (Send-Totals) { $dirty = $false; $lastSend = $now }; Save-State }
}
