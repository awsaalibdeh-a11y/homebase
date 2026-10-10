// Homebase for Windows: screen time (only active playing/watching), parent locks and grounded apps.
// No scripts, no PowerShell. Run it, type the 6-digit code from Homebase, and it links itself.
//   HomebaseTracker.exe            first run asks for the code; afterwards it just runs from the tray
//   HomebaseTracker.exe --code 123456 [--server https://...]    link without the window
//   HomebaseTracker.exe --remove   take it off this computer
// It never reads your screen, files, passwords or what you type. It only sees which program is in front, the title of
// its window (to tell YouTube from a homework tab) and how long since the mouse or keyboard was last used.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

static class Native
{
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] public struct LII { public uint cb; public uint t; }
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool GetLastInputInfo(ref LII p);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int i);
    [DllImport("user32.dll")] public static extern bool LockWorkStation();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    public static double Idle() { LII l = new LII(); l.cb = (uint)Marshal.SizeOf(l); GetLastInputInfo(ref l); return unchecked((uint)Environment.TickCount - l.t) / 1000.0; }
}

class App : ApplicationContext
{
    const string DefaultServer = "https://homebase-family.onrender.com";
    const int ActiveSeconds = 150;
    static readonly string Dir = Environment.GetEnvironmentVariable("HB_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Homebase");
    static readonly string Installed = Path.Combine(Dir, "HomebaseTracker.exe");
    static readonly string ConfigFile = Path.Combine(Dir, "config.txt");
    static readonly string TodayFile = Path.Combine(Dir, "today.txt");
    static readonly string LogFile = Path.Combine(Dir, "tracker.log");
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    static bool Test { get { return Environment.GetEnvironmentVariable("HB_NO_START") != null; } }

    string server = DefaultServer, token = "";
    NotifyIcon tray;
    Dictionary<string, string[]> apps = new Dictionary<string, string[]>();      // key -> label, category, seconds
    string day = DateTime.Now.ToString("yyyy-MM-dd");
    bool dirty; DateTime lastSend = DateTime.MinValue, lastTick = DateTime.Now; int cursor = -1;
    readonly Dictionary<string, DateTime> told = new Dictionary<string, DateTime>();
    volatile bool stopping;

    static readonly string[] Browsers = { "chrome", "msedge", "firefox", "brave", "opera", "opera_gx", "vivaldi", "iexplore" };
    static readonly string[][] WatchSites = { new[] { "YouTube", "YouTube" }, new[] { "Netflix", "Netflix" }, new[] { "Twitch", "Twitch" }, new[] { "Disney\\+|Disney Plus", "Disney+" },
        new[] { "Prime Video", "Prime Video" }, new[] { "Crunchyroll", "Crunchyroll" }, new[] { "Hulu", "Hulu" }, new[] { "Vimeo", "Vimeo" } };
    static readonly string[][] SocialSites = { new[] { "TikTok", "TikTok" }, new[] { "Instagram", "Instagram" }, new[] { "Facebook", "Facebook" }, new[] { "Snapchat", "Snapchat" },
        new[] { "Reddit", "Reddit" }, new[] { "Twitter|(^|[ \\-])X($|[ \\-])", "X (Twitter)" } };
    static readonly string[] WatchApps = { "vlc", "mpv", "wmplayer", "video.ui", "netflix", "disneyplus", "primevideo", "plex", "kodi", "potplayermini64", "stremio" };
    static readonly Regex GameNames = new Regex("^(minecraft|roblox.*|fortnite.*|valorant.*|overwatch.*|cs2|csgo|dota2|r5apex|gta5|eldenring|terraria|stardew valley|amongus|rocketleague|fallguys.*|palworld.*|hogwarts.*|league of legends|genshinimpact|yuanshen|apex.*|rainbowsix.*|fifa.*|eafc.*|cyberpunk2077|witcher3|skyrim.*|portal2|hl2|left4dead2|tf2|brawlhalla|osu!|lethalcompany|helldivers2|content warning)$", RegexOptions.IgnoreCase);
    static readonly string[] Launchers = { "steam", "steamwebhelper", "epicgameslauncher", "riotclientservices", "riotclientux", "battle.net", "origin", "ubisoftconnect", "gog galaxy", "galaxyclient" };

    [STAThread]
    static void Main(string[] args)
    {
        ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;           // TLS 1.2
        Application.EnableVisualStyles();
        bool created;
        using (new Mutex(true, "Local\\HomebaseTracker", out created))
        {
            if (!created) { if (args.Length == 0) MessageBox.Show("Homebase screen time is already running. Look for its icon near the clock.", "Homebase"); return; }
            try { Application.Run(new App(args)); }
            catch (Exception e) { Log("fatal: " + e); MessageBox.Show("Something went wrong:\n\n" + e.Message + "\n\nDetails are in " + LogFile, "Homebase screen time"); }
        }
    }

    App(string[] args)
    {
        Directory.CreateDirectory(Dir);
        string code = null;
        for (int i = 0; i < args.Length; i++)
        {
            if (args[i] == "--remove") { Remove(true); Environment.Exit(0); }
            if (args[i] == "--code" && i + 1 < args.Length) code = args[i + 1];
            if (args[i] == "--server" && i + 1 < args.Length) server = args[i + 1].TrimEnd('/');
        }
        LoadConfig();
        if (token == "")
        {
            if (code == null) code = AskForCode();
            if (code == null) Environment.Exit(0);
            string err = Claim(code);
            if (err != null) { MessageBox.Show(err, "Homebase screen time"); Environment.Exit(1); }
            Install();
        }
        LoadToday();
        MakeTray();
        new Thread(Loop) { IsBackground = true }.Start();
    }

    // ---------- the window that asks for the code
    string AskForCode()
    {
        Form f = new Form { Text = "Homebase screen time", Width = 420, Height = 290, FormBorderStyle = FormBorderStyle.FixedDialog, StartPosition = FormStartPosition.CenterScreen, MaximizeBox = false, MinimizeBox = false, BackColor = Color.FromArgb(18, 18, 20), ForeColor = Color.FromArgb(236, 236, 240), Font = new Font("Segoe UI", 10f) };
        Label title = new Label { Text = "Link this computer to Homebase", Font = new Font("Segoe UI", 14f, FontStyle.Bold), Left = 24, Top = 18, Width = 360, Height = 30 };
        Label help = new Label { Text = "In Homebase open Menu, then Screen time, then \"Get the Windows tracker\". Type the 6 digits you see there.", Left = 24, Top = 56, Width = 360, Height = 50, ForeColor = Color.FromArgb(160, 160, 170) };
        TextBox box = new TextBox { Left = 24, Top = 112, Width = 360, MaxLength = 6, Font = new Font("Consolas", 22f, FontStyle.Bold), TextAlign = HorizontalAlignment.Center, BackColor = Color.FromArgb(10, 10, 11), ForeColor = Color.White, BorderStyle = BorderStyle.FixedSingle };
        Label note = new Label { Text = "It counts only time spent playing or watching, and your family can see exactly the same numbers.", Left = 24, Top = 160, Width = 360, Height = 40, ForeColor = Color.FromArgb(130, 130, 140), Font = new Font("Segoe UI", 8.5f) };
        Button ok = new Button { Text = "Link this computer", Left = 24, Top = 208, Width = 360, Height = 36, BackColor = Color.FromArgb(91, 147, 255), ForeColor = Color.FromArgb(8, 16, 31), FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 10f, FontStyle.Bold), DialogResult = DialogResult.OK };
        ok.FlatAppearance.BorderSize = 0;
        f.Controls.AddRange(new Control[] { title, help, box, note, ok }); f.AcceptButton = ok;
        if (f.ShowDialog() != DialogResult.OK) return null;
        return Regex.Replace(box.Text, "\\D", "");
    }

    // ---------- talking to Homebase
    string Http(string method, string path, object body, out int status)
    {
        HttpWebRequest r = (HttpWebRequest)WebRequest.Create(server + path);
        r.Method = method; r.Timeout = 20000; r.UserAgent = "HomebaseTracker/1.0";
        if (token != "") r.Headers["Authorization"] = "Bearer " + token;
        if (body != null) { byte[] b = Encoding.UTF8.GetBytes(Json.Serialize(body)); r.ContentType = "application/json"; r.ContentLength = b.Length; using (Stream s = r.GetRequestStream()) s.Write(b, 0, b.Length); }
        try { using (HttpWebResponse x = (HttpWebResponse)r.GetResponse()) { status = (int)x.StatusCode; using (StreamReader sr = new StreamReader(x.GetResponseStream())) return sr.ReadToEnd(); } }
        catch (WebException e) { HttpWebResponse x = e.Response as HttpWebResponse; if (x == null) throw; status = (int)x.StatusCode; using (StreamReader sr = new StreamReader(x.GetResponseStream())) return sr.ReadToEnd(); }
    }

    string Claim(string code)
    {
        try
        {
            int st; string txt = Http("POST", "/api/devices/claim", new Dictionary<string, object> { { "code", code }, { "name", Environment.MachineName }, { "kind", "windows" } }, out st);
            Dictionary<string, object> d = Json.Deserialize<Dictionary<string, object>>(txt);
            if (st != 200) return d.ContainsKey("error") ? d["error"].ToString() : "That did not work. Make a new code in Homebase.";
            token = d["token"].ToString(); SaveConfig(); return null;
        }
        catch (Exception e) { Log("claim: " + e.Message); return "Could not reach Homebase. Check the internet and try again. (The first try can take a minute while it wakes up.)"; }
    }

    // ---------- saving things
    void LoadConfig()
    {
        if (!File.Exists(ConfigFile)) return;
        foreach (string line in File.ReadAllLines(ConfigFile))
        {
            int i = line.IndexOf('='); if (i < 0) continue; string k = line.Substring(0, i), v = line.Substring(i + 1);
            if (k == "server" && v != "") server = v;
            if (k == "token") { try { token = Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(v), null, DataProtectionScope.CurrentUser)); } catch { token = ""; } }
        }
    }
    void SaveConfig()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllLines(ConfigFile, new[] { "server=" + server, "token=" + Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(token), null, DataProtectionScope.CurrentUser)) });
    }
    void LoadToday()
    {
        try
        {
            if (!File.Exists(TodayFile)) return;
            string[] lines = File.ReadAllLines(TodayFile); if (lines.Length == 0 || lines[0] != day) return;
            foreach (string l in lines.Skip(1)) { string[] p = l.Split('|'); if (p.Length == 4) apps[p[0]] = new[] { p[1], p[2], p[3] }; }
        }
        catch { }
    }
    void SaveToday() { try { File.WriteAllLines(TodayFile, new[] { day }.Concat(apps.Select(kv => kv.Key + "|" + kv.Value[0] + "|" + kv.Value[1] + "|" + kv.Value[2]))); } catch { } }
    static void Log(string s) { try { Directory.CreateDirectory(Dir); File.AppendAllText(LogFile, DateTime.Now + " " + s + Environment.NewLine); } catch { } }

    // ---------- install and remove
    void Install()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            string me = Process.GetCurrentProcess().MainModule.FileName;
            if (!string.Equals(me, Installed, StringComparison.OrdinalIgnoreCase)) File.Copy(me, Installed, true);
            if (!Test)
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run", true)) k.SetValue("HomebaseTracker", "\"" + Installed + "\"");
            }
        }
        catch (Exception e) { Log("install: " + e.Message); }
    }
    static void Remove(bool ask)
    {
        if (ask && MessageBox.Show("Take Homebase screen time off this computer?", "Homebase", MessageBoxButtons.YesNo) != DialogResult.Yes) return;
        try { using (RegistryKey k = Registry.CurrentUser.OpenSubKey("Software\\Microsoft\\Windows\\CurrentVersion\\Run", true)) if (k != null) k.DeleteValue("HomebaseTracker", false); } catch { }
        try { File.Delete(ConfigFile); File.Delete(TodayFile); } catch { }
        if (ask) MessageBox.Show("Done. Homebase screen time is removed from this computer.", "Homebase");
    }

    // ---------- the icon near the clock (always visible: nothing is hidden)
    void MakeTray()
    {
        ContextMenuStrip m = new ContextMenuStrip();
        m.Items.Add("Homebase screen time is on").Enabled = false;
        ToolStripMenuItem today = new ToolStripMenuItem("Today: counting...");
        m.Opening += delegate { today.Text = "Today: " + Duration(Total()) + " playing or watching"; };
        m.Items.Add(today);
        m.Items.Add("Open Homebase", null, delegate { try { Process.Start(server + "/app"); } catch { } });
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Remove from this computer", null, delegate { Remove(true); Quit(); });
        tray = new NotifyIcon { Icon = SystemIcons.Information, Text = "Homebase screen time", Visible = true, ContextMenuStrip = m };
        tray.DoubleClick += delegate { tray.ShowBalloonTip(5000, "Homebase", "Today: " + Duration(Total()) + " playing or watching", ToolTipIcon.Info); };
    }
    void Quit() { stopping = true; if (tray != null) { tray.Visible = false; tray.Dispose(); } Application.Exit(); }
    void Say(string title, string text) { try { if (tray != null) tray.ShowBalloonTip(10000, title, text, ToolTipIcon.Info); } catch { } }
    int Total() { int t = 0; foreach (var v in apps.Values) { int s; int.TryParse(v[2], out s); t += s; } return t; }
    static string Duration(int sec) { int m = sec / 60; return m >= 60 ? (m / 60) + " h " + (m % 60).ToString("00") + " min" : m + " min"; }

    // ---------- the loop: lock, nudges, grounded apps, counting
    void Loop()
    {
        int tick = int.Parse(Environment.GetEnvironmentVariable("HB_TICK") ?? "10");
        while (!stopping)
        {
            Thread.Sleep(tick * 1000);
            try { Step(); } catch (Exception e) { Log("step: " + e.Message); }
        }
    }

    void Step()
    {
        DateTime now = DateTime.Now; double elapsed = Math.Min(45, (now - lastTick).TotalSeconds); lastTick = now;
        string today = now.ToString("yyyy-MM-dd");
        if (today != day) { if (dirty) Send(); day = today; apps.Clear(); dirty = false; }
        int st; bool locked = false;
        try
        {
            string txt = Http("GET", "/api/lock", null, out st);
            if (st == 401) { Say("Homebase", "This computer was removed from Homebase."); Remove(false); Quit(); return; }
            var d = Json.Deserialize<Dictionary<string, object>>(txt);
            if (d.ContainsKey("lock") && d["lock"] != null)
            {
                var lk = (Dictionary<string, object>)d["lock"];
                if (Convert.ToDouble(lk["until"]) > Convert.ToDouble(d["now"])) { locked = true; Native.LockWorkStation(); }       // a parent's lock: lock Windows until it ends
            }
        }
        catch (Exception e) { Log("lock: " + e.Message); }
        try
        {
            string txt = Http("GET", "/api/events?after=" + cursor + "&wait=0", null, out st);
            var d = Json.Deserialize<Dictionary<string, object>>(txt);
            if (d.ContainsKey("cursor")) cursor = Convert.ToInt32(d["cursor"]);
            if (d.ContainsKey("events") && cursor >= 0)
                foreach (object o in (System.Collections.ArrayList)d["events"])
                {
                    var ev = (Dictionary<string, object>)o;
                    if (Convert.ToString(ev["type"]) == "nudge") Say("Time for a break?", Convert.ToString(((Dictionary<string, object>)ev["payload"])["text"]));
                }
        }
        catch (Exception e) { Log("events: " + e.Message); }
        try { EnforceBlocks(); } catch (Exception e) { Log("blocks: " + e.Message); }
        if (locked) return;
        try { Count(elapsed); } catch (Exception e) { Log("count: " + e.Message); }
        if (dirty && (now - lastSend).TotalSeconds >= (Environment.GetEnvironmentVariable("HB_TICK") != null ? 1 : 120)) { if (Send()) { dirty = false; lastSend = now; } SaveToday(); }
    }

    // grounded apps: close them (or minimise a grounded website) and say why
    void EnforceBlocks()
    {
        int st; string txt = Http("GET", "/api/blocks", null, out st); if (st != 200) return;
        var d = Json.Deserialize<Dictionary<string, object>>(txt);
        if (!d.ContainsKey("blocks")) return;
        foreach (object o in (System.Collections.ArrayList)d["blocks"])
        {
            var b = (Dictionary<string, object>)o; string key = Convert.ToString(b["key"]).ToLower(), label = Convert.ToString(b["label"]);
            DateTime until = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddSeconds(Convert.ToDouble(b["until"])).ToLocalTime();
            bool hit = false;
            foreach (Process p in Process.GetProcesses())
            {
                try
                {
                    string name = p.ProcessName.ToLower(), title = (p.MainWindowTitle ?? "").ToLower();
                    if (Array.IndexOf(Browsers, name) >= 0) { if (title.Contains(key) && p.MainWindowHandle != IntPtr.Zero) { Native.ShowWindow(p.MainWindowHandle, 6); hit = true; } }
                    else if (name.Contains(key) || (title != "" && title.Contains(key) && p.MainWindowHandle != IntPtr.Zero)) { p.Kill(); hit = true; }
                }
                catch { }
            }
            DateTime last;
            if (hit && (!told.TryGetValue(label, out last) || (DateTime.Now - last).TotalSeconds > 60)) { told[label] = DateTime.Now; Say(label + " is grounded", "A parent grounded " + label + " until " + until.ToString("t") + "."); }
        }
    }

    void Count(double elapsed)
    {
        string fake = Environment.GetEnvironmentVariable("HB_FAKE"); string name, title, path = null; double idle; bool full;
        if (fake != null) { string[] f = fake.Split('|'); name = f[0]; title = f[1]; idle = double.Parse(f[2]); full = f[3] == "1"; }
        else
        {
            idle = Native.Idle(); IntPtr h = Native.GetForegroundWindow(); if (h == IntPtr.Zero) return;
            uint pid; Native.GetWindowThreadProcessId(h, out pid); if (pid == 0) return;
            StringBuilder sb = new StringBuilder(300); Native.GetWindowText(h, sb, 300); title = sb.ToString();
            Native.RECT r; full = Native.GetWindowRect(h, out r) && (r.R - r.L) >= Native.GetSystemMetrics(0) && (r.B - r.T) >= Native.GetSystemMetrics(1);
            Process p = Process.GetProcessById((int)pid); name = p.ProcessName;
            try { path = p.MainModule.FileName; } catch { }
        }
        if (name == "LockApp" || name == "LogonUI" || name == "SearchHost" || name == "explorer") return;
        string[] c = Classify(name, title, path); if (c == null) return;
        bool active = c[2] == "watch" ? (idle <= ActiveSeconds || full) : idle <= ActiveSeconds;
        if (!active) return;
        string[] cur; int sec = 0;
        if (apps.TryGetValue(c[0], out cur)) int.TryParse(cur[2], out sec);
        apps[c[0]] = new[] { c[1], c[2], (sec + (int)elapsed).ToString() }; dirty = true;
    }

    // returns { key, label, category } for playing/watching, or null (homework, work, everyday apps)
    static string[] Classify(string proc, string title, string path)
    {
        string n = proc.ToLower();
        if (Array.IndexOf(Browsers, n) >= 0)
        {
            foreach (var s in WatchSites) if (Regex.IsMatch(title, s[0], RegexOptions.IgnoreCase)) return new[] { "pc:web:" + s[1], s[1] + " (in browser)", "watch" };
            foreach (var s in SocialSites) if (Regex.IsMatch(title, s[0])) return new[] { "pc:web:" + s[1], s[1] + " (in browser)", "social" };
            return null;                                              // an ordinary web page is homework or reading
        }
        if (Array.IndexOf(Launchers, n) >= 0) return null;
        if (Array.IndexOf(WatchApps, n) >= 0) return new[] { "pc:" + n, proc, "watch" };
        if (n == "java" || n == "javaw") return title.IndexOf("Minecraft", StringComparison.OrdinalIgnoreCase) >= 0 ? new[] { "pc:minecraft", "Minecraft", "play" } : null;
        if (GameNames.IsMatch(n)) return new[] { "pc:" + n, proc, "play" };
        if (path != null && Regex.IsMatch(path, "\\\\(steamapps\\\\common|Epic Games|Riot Games|XboxGames)\\\\", RegexOptions.IgnoreCase)) return new[] { "pc:" + n, proc, "play" };
        return null;
    }

    bool Send()
    {
        try
        {
            List<object> list = new List<object>();
            foreach (var kv in apps) list.Add(new Dictionary<string, object> { { "pkg", kv.Key }, { "label", kv.Value[0] }, { "cat", kv.Value[1] }, { "sec", int.Parse(kv.Value[2]) } });
            int st; Http("POST", "/api/screentime", new Dictionary<string, object> { { "day", day }, { "apps", list } }, out st);
            if (st == 401) { Say("Homebase", "This computer was removed from Homebase."); Remove(false); Quit(); }
            return st == 200;
        }
        catch (Exception e) { Log("send: " + e.Message); return false; }
    }
}
