// Homebase for Windows: screen time (only active playing/watching), parent locks and grounded apps.
// Built for a computer that more than one child uses: it asks who is using it, and counts, locks and grounds each person separately.
// No scripts, no PowerShell. Run it, type the 6-digit code from Homebase, and it links itself.
//   HomebaseTracker.exe            first run asks for the code; afterwards it just runs from the tray
//   HomebaseTracker.exe --code 123456 [--server https://...]    link without the window
//   HomebaseTracker.exe --remove   take it off this computer
// It also shows family texts, announcements and ringing calls here, like any chat app.
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
using System.Media;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

/// <summary>
/// A small dark pop-up that Homebase draws itself in the corner of the screen. Windows "balloon" notifications are silently hidden by
/// Focus Assist or notification settings, which made grounding warnings and messages invisible, so important things use this instead.
/// </summary>
class Toast : Form
{
    static readonly List<Toast> open = new List<Toast>();
    [DllImport("gdi32.dll")] static extern IntPtr CreateRoundRectRgn(int a, int b, int c, int d, int e, int f);
    protected override bool ShowWithoutActivation { get { return true; } }
    protected override CreateParams CreateParams { get { CreateParams cp = base.CreateParams; cp.ExStyle |= 0x08000000 | 0x80 | 0x8; return cp; } }   // no focus steal, not in alt-tab, always on top
    readonly System.Windows.Forms.Timer life = new System.Windows.Forms.Timer();

    public Toast(string title, string body, int seconds, string action, Action onAction) : this(title, body, seconds, action == null ? null : new[] { action }, action == null ? null : new Action<int>(delegate (int i) { onAction(); })) { }

    public Toast(string title, string body, int seconds, string[] buttons, Action<int> onPick)
    {
        AutoScaleDimensions = new SizeF(96f, 96f); AutoScaleMode = AutoScaleMode.Dpi;
        FormBorderStyle = FormBorderStyle.None; ShowInTaskbar = false; TopMost = true; StartPosition = FormStartPosition.Manual;
        BackColor = Color.FromArgb(24, 24, 28); ForeColor = Color.FromArgb(236, 236, 240); Width = 340;
        int extra = buttons == null ? 0 : (buttons.Length > 2 ? 34 * ((buttons.Length + 1) / 2) + 8 : 42);
        Height = 78 + extra;
        Label t = new Label { Text = title, Left = 16, Top = 10, Width = 308, Height = 24, Font = new Font("Segoe UI Emoji", 10.5f, FontStyle.Bold), ForeColor = Color.White, UseCompatibleTextRendering = false };
        Label b = new Label { Text = body, Left = 16, Top = 34, Width = 308, Height = 38, Font = new Font("Segoe UI Emoji", 9f), ForeColor = Color.FromArgb(170, 170, 180) };
        Controls.Add(t); Controls.Add(b);
        if (buttons != null)
        {
            for (int i = 0; i < buttons.Length; i++)
            {
                int idx = i; bool two = buttons.Length > 1;
                int w = buttons.Length == 1 ? 200 : 148, col = buttons.Length == 1 ? 0 : i % 2, row = buttons.Length == 1 ? 0 : i / 2;
                if (buttons.Length > 2 && buttons.Length % 2 == 1 && i == buttons.Length - 1) { w = 304; col = 0; }
                Button go = new Button { Text = buttons[i], Left = 16 + col * 156, Top = 74 + row * 34, Width = w, Height = 28, AutoSize = false, FlatStyle = FlatStyle.Flat, BackColor = i == 0 ? Color.FromArgb(91, 147, 255) : Color.FromArgb(40, 40, 46), ForeColor = i == 0 ? Color.FromArgb(8, 16, 31) : Color.White, Font = new Font("Segoe UI", 9f, FontStyle.Bold) };
                go.FlatAppearance.BorderSize = 0; go.Click += delegate { try { if (onPick != null) onPick(idx); } catch { } Close(); }; Controls.Add(go);
            }
        }
        Click += delegate { Close(); }; t.Click += delegate { Close(); }; b.Click += delegate { Close(); };
        Shown += delegate { Rounded(); Stack(); };
        SizeChanged += delegate { Rounded(); };
        life.Interval = Math.Max(3, seconds) * 1000; life.Tick += delegate { Close(); }; life.Start();
        FormClosed += delegate { open.Remove(this); Stack(); };
        open.Add(this); Stack();
    }

    void Rounded() { try { Region = System.Drawing.Region.FromHrgn(CreateRoundRectRgn(0, 0, Width + 1, Height + 1, 18, 18)); } catch { } }

    static void Stack()
    {
        Rectangle wa = Screen.PrimaryScreen.WorkingArea; int y = wa.Bottom - 12;
        for (int i = open.Count - 1; i >= 0; i--) { Toast x = open[i]; y -= x.Height; x.Location = new Point(wa.Right - x.Width - 14, y); y -= 10; }
    }
    public static void Snapshots(string dir)                                              // test helper: draw each pop-up to a picture
    {
        int i = 0; foreach (Toast t in open.ToArray()) { try { using (Bitmap bmp = new Bitmap(t.Width, t.Height)) { t.DrawToBitmap(bmp, new Rectangle(0, 0, t.Width, t.Height)); bmp.Save(Path.Combine(dir, "toast" + (i++) + ".png")); } } catch { } }
    }
    public static void Choose(string title, string body, int seconds, string[] buttons, Action<int> onPick)
    {
        while (open.Count >= 4) open[0].Close();
        new Toast(title, body, seconds, buttons, onPick).Show();
    }
    public static void Show(string title, string body, int seconds, string action, Action onAction)
    {
        while (open.Count >= 4) open[0].Close();
        new Toast(title, body, seconds, action, onAction).Show();
    }
}

static class Native
{
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
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
    public const int Build = 17;                                              // bump with windows/BUILD on the server; computers update themselves when the server's is higher
    const string DefaultServer = "https://homebase-family.onrender.com";
    const int ActiveSeconds = 150;
    static readonly string Dir = Environment.GetEnvironmentVariable("HB_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Homebase");
    static readonly string Installed = Path.Combine(Dir, "HomebaseTracker.exe");
    static readonly string ConfigFile = Path.Combine(Dir, "config.txt");
    static readonly string LogFile = Path.Combine(Dir, "tracker.log");
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    static bool Test { get { return Environment.GetEnvironmentVariable("HB_NO_START") != null; } }

    class Profile { public string Name, Token; }
    readonly List<Profile> profiles = new List<Profile>();
    Profile cur;
    string server = DefaultServer;
    string token { get { return cur == null ? "" : cur.Token; } }
    Control sync;                                                            // lets the background loop open windows on the UI thread
    DateTime lastPrompt = DateTime.MinValue; bool wasAway; DateTime lastApps = DateTime.MinValue; DateTime lastBeat = DateTime.MinValue;
    DateTime quietUntil = DateTime.MinValue;
    volatile string nowDoing = "nothing yet"; volatile string lastFgName = "";
    string TodayFile { get { return Path.Combine(Dir, "today_" + Regex.Replace(cur == null ? "x" : cur.Name, "[^A-Za-z0-9]", "_") + ".txt"); } }
    NotifyIcon tray;
    Dictionary<string, string[]> apps = new Dictionary<string, string[]>();      // key -> label, category, seconds
    string day = DateTime.Now.ToString("yyyy-MM-dd");
    bool dirty; DateTime lastSend = DateTime.MinValue, lastTick = DateTime.Now; int cursor = -1;
    readonly Dictionary<string, DateTime> told = new Dictionary<string, DateTime>();
    volatile bool stopping;
    volatile int ringingCall;                                               // the id of a call that is ringing right now, 0 = none
    readonly HashSet<int> seenCalls = new HashSet<int>();

    static readonly string[] Browsers = { "chrome", "msedge", "firefox", "brave", "opera", "opera_gx", "vivaldi", "iexplore" };
    static readonly string[][] WatchSites = { new[] { "YouTube", "YouTube" }, new[] { "Netflix", "Netflix" }, new[] { "Twitch", "Twitch" }, new[] { "Disney\\+|Disney Plus", "Disney+" },
        new[] { "Prime Video", "Prime Video" }, new[] { "Crunchyroll", "Crunchyroll" }, new[] { "Hulu", "Hulu" }, new[] { "Vimeo", "Vimeo" } };
    static readonly string[][] SocialSites = { new[] { "TikTok", "TikTok" }, new[] { "Instagram", "Instagram" }, new[] { "Facebook", "Facebook" }, new[] { "Snapchat", "Snapchat" },
        new[] { "Reddit", "Reddit" }, new[] { "Twitter|(^|[ \\-])X($|[ \\-])", "X (Twitter)" } };
    static readonly string[] WatchApps = { "vlc", "mpv", "wmplayer", "video.ui", "netflix", "disneyplus", "primevideo", "plex", "kodi", "potplayermini64", "stremio" };
    static readonly Regex GameNames = new Regex("^(minecraft.*|roblox.*|zenlesszonezero|honkai.*|wutheringwaves.*|marvel.*|tarkov|rust|pubg.*|tslgame|warframe.*|destiny2|bf20.*|bf4|bf1|modernwarfare.*|cod.*|warzone.*|callofduty.*|battlefront.*|nba2k.*|madden.*|nfs.*|forza.*|halo.*|gta.*|rdr2|sims4|spore|undertale|deltarune|hollow ?knight|celeste|hades|among ?us|goose ?goose.*|fall ?guys.*|stumble.*|brawl.*|clash.*|supercell.*|subway.*|geometry ?dash|plants ?vs.*|pvz.*|bluestacks.*|hd-player|ldplayer.*|memu.*|nox.*|gameloop|epicgameslauncher_|fortnite.*|valorant.*|overwatch.*|cs2|csgo|dota2|r5apex|gta5|eldenring|terraria|stardew valley|amongus|rocketleague|fallguys.*|palworld.*|hogwarts.*|league of legends|genshinimpact|yuanshen|apex.*|rainbowsix.*|fifa.*|eafc.*|cyberpunk2077|witcher3|skyrim.*|portal2|hl2|left4dead2|tf2|brawlhalla|osu!|lethalcompany|helldivers2|content warning)$", RegexOptions.IgnoreCase);
    static readonly string[] Launchers = { "steam", "steamwebhelper", "epicgameslauncher", "riotclientservices", "riotclientux", "battle.net", "origin", "ubisoftconnect", "gog galaxy", "galaxyclient" };

    [STAThread]
    static void Main(string[] args)
    {
        try { Native.SetProcessDPIAware(); } catch { }                              // real pixels on scaled screens, so pop-ups land where they should
        ServicePointManager.SecurityProtocol = (SecurityProtocolType)3072;           // TLS 1.2
        Application.EnableVisualStyles();
        if (args.Length > 0 && args[0] == "--selftest") { SelfTest(); return; }        // runs even while the real tracker is open
        if (args.Length > 0 && args[0] == "--choosetest")                               // shows the "who is using this computer?" pop-up so it can be seen
        {
            Toast.Choose("Who is using this computer?", "So the right person is counted and locked.", 8, new[] { "✓ Aws", "Laith", "Link someone else..." }, delegate (int i) { });
            System.Windows.Forms.Timer sn = new System.Windows.Forms.Timer { Interval = 1200 }; sn.Tick += delegate { sn.Stop(); Toast.Snapshots(Environment.GetEnvironmentVariable("HB_SNAP") ?? Path.GetTempPath()); Application.Exit(); }; sn.Start();
            Application.Run(); return;
        }
        if (args.Length > 0 && args[0] == "--popuptest")                                // shows the pop-ups for a few seconds so they can be seen
        {
            Toast.Show("🚫 Roblox is grounded", "Closed because it is grounded until 8:51 PM.", 9, null, null);
            Toast.Show("📞 Mum is calling", "Homebase call", 9, "Answer in Homebase", delegate { });
            Toast.Show("Homebase is on", "Counting only playing and watching.", 9, null, null);
            System.Windows.Forms.Timer snap = new System.Windows.Forms.Timer { Interval = 1500 }; snap.Tick += delegate { snap.Stop(); Toast.Snapshots(Environment.GetEnvironmentVariable("HB_SNAP") ?? Path.GetTempPath()); }; snap.Start();
            System.Windows.Forms.Timer t = new System.Windows.Forms.Timer { Interval = 11000 }; t.Tick += delegate { Application.Exit(); }; t.Start();
            Application.Run(); return;
        }
        bool created;
        string hbDir = Environment.GetEnvironmentVariable("HB_DIR");                   // tests use their own folder, so they can run beside the real tracker
        using (Mutex mx = new Mutex(true, "Local\\HomebaseTracker" + (hbDir == null ? "" : "_" + Math.Abs(hbDir.GetHashCode())), out created))   // tests use their own folder, so they can run beside the real tracker
        {
            if (!created)
            {
                // another copy is running, possibly an OLD version. Launching a new one means "update me": stop the old one and take over.
                if (hbDir == null)
                    foreach (Process other in Process.GetProcessesByName("HomebaseTracker"))
                        if (other.Id != Process.GetCurrentProcess().Id) { try { other.Kill(); other.WaitForExit(4000); } catch { } }
                try { created = mx.WaitOne(8000); } catch (AbandonedMutexException) { created = true; }
                if (!created) { if (args.Length == 0) MessageBox.Show("Homebase screen time is already running. Look for its icon near the clock.", "Homebase"); return; }
            }
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
            if (args[i] == "--selftest") { SelfTest(); Environment.Exit(0); }
            if (args[i] == "--remove") { Remove(true); Environment.Exit(0); }
            if (args[i] == "--code" && i + 1 < args.Length) code = args[i + 1];
            if (args[i] == "--server" && i + 1 < args.Length) server = args[i + 1].TrimEnd('/');
        }
        LoadConfig();
        if (profiles.Count == 0)
        {
            if (code == null) code = AskForCode();
            if (code == null) Environment.Exit(0);
            string err = Claim(code);
            if (err != null) { MessageBox.Show(err, "Homebase screen time"); Environment.Exit(1); }
            Install();
        }
        else if (code != null)
        {
            string err = Claim(code);                                          // running it again with a code adds another person
            if (err != null) MessageBox.Show(err, "Homebase screen time"); else { Install(); }
        }
        sync = new Control(); sync.CreateControl(); IntPtr force = sync.Handle;
        if (profiles.Count > 1) PickPerson(false);                              // two kids, one laptop: ask who is sitting down
        LoadToday(); LoadCustom();
        MakeTray();
        if (LastShownBuild() != Build) { SetShownBuild(Build); Say("Homebase is on", (cur == null ? "" : cur.Name + ": ") + "counting only playing and watching. Right-click the icon near the clock to see what it counts.", 10, null, null); }
        new Thread(Loop) { IsBackground = true }.Start();
    }

    // ---------- who is using the computer? (honest and visible: the family can see which person was counted)
    void PickPerson(bool allowLink)
    {
        if (Test) return;
        if (profiles.Count < 2 && !allowLink) return;
        if ((DateTime.Now - lastPrompt).TotalSeconds < 3) return; lastPrompt = DateTime.Now;
        List<string> names = profiles.Select(p => (cur != null && cur.Name == p.Name ? "\u2713 " : "") + p.Name).ToList();
        if (allowLink) names.Add("Link someone else...");
        // a small pop-up in the corner with one button per person: it never takes over the screen, and it goes away by itself (keeping the current person)
        Toast.Choose("Who is using this computer?", "So the right person is counted and locked.", 30, names.ToArray(), delegate (int i)
        {
            if (i < profiles.Count) { if (profiles[i] != cur) Switch(profiles[i]); return; }
            string c = AskForCode(); if (c != null) { string err = Claim(c); if (err != null) MessageBox.Show(err, "Homebase screen time"); else PickPerson(false); }
        });
    }

    void Switch(Profile p)
    {
        lock (apps) { if (dirty) { Send(); } SaveToday(); cur = p; apps.Clear(); dirty = false; cursor = -1; told.Clear(); LoadToday(); }
        SaveConfig();
        Say("Homebase", p.Name + " is using this computer.", 4, null, null);
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
        if (token != "" && !path.StartsWith("/api/devices/claim")) r.Headers["Authorization"] = "Bearer " + token;
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
            var mem = d.ContainsKey("member") ? d["member"] as Dictionary<string, object> : null;
            string name = mem != null && mem.ContainsKey("name") ? mem["name"].ToString() : "Me";
            Profile existing = profiles.FirstOrDefault(p => p.Name == name);
            if (existing != null) existing.Token = d["token"].ToString(); else { existing = new Profile { Name = name, Token = d["token"].ToString() }; profiles.Add(existing); }
            cur = existing; SaveConfig(); return null;
        }
        catch (Exception e) { Log("claim: " + e.Message); return "Could not reach Homebase. Check the internet and try again. (The first try can take a minute while it wakes up.)"; }
    }

    // ---------- saving things
    void LoadConfig()
    {
        if (!File.Exists(ConfigFile)) return;
        string active = "";
        foreach (string line in File.ReadAllLines(ConfigFile))
        {
            int i = line.IndexOf('='); if (i < 0) continue; string k = line.Substring(0, i), v = line.Substring(i + 1);
            if (k == "server" && v != "") server = v;
            if (k == "active") active = v;
            if (k == "profile")
            {
                int bar = v.IndexOf('|'); if (bar < 0) continue;
                try { profiles.Add(new Profile { Name = v.Substring(0, bar), Token = Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(v.Substring(bar + 1)), null, DataProtectionScope.CurrentUser)) }); } catch { }
            }
            if (k == "token")                                                      // the first version kept one person
            {
                try { profiles.Add(new Profile { Name = "Me", Token = Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(v), null, DataProtectionScope.CurrentUser)) }); } catch { }
            }
        }
        cur = profiles.FirstOrDefault(p => p.Name == active) ?? (profiles.Count > 0 ? profiles[0] : null);
        string forced = Environment.GetEnvironmentVariable("HB_ACTIVE");           // tests only
        if (forced != null) cur = profiles.FirstOrDefault(p => p.Name == forced) ?? cur;
    }
    void SaveConfig()
    {
        Directory.CreateDirectory(Dir);
        List<string> lines = new List<string> { "server=" + server, "active=" + (cur == null ? "" : cur.Name) };
        foreach (Profile p in profiles) lines.Add("profile=" + p.Name + "|" + Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(p.Token), null, DataProtectionScope.CurrentUser)));
        File.WriteAllLines(ConfigFile, lines.ToArray());
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
        try { File.Delete(ConfigFile); foreach (string f in Directory.GetFiles(Dir, "today_*.txt")) File.Delete(f); } catch { }
        if (ask) MessageBox.Show("Done. Homebase screen time is removed from this computer.", "Homebase");
    }

    // ---------- the icon near the clock (always visible: nothing is hidden)
    void MakeTray()
    {
        ContextMenuStrip m = new ContextMenuStrip();
        ToolStripMenuItem who = new ToolStripMenuItem("Homebase screen time is on"); who.Enabled = false; m.Items.Add(who);
        ToolStripMenuItem today = new ToolStripMenuItem("Today: counting...");
        ToolStripMenuItem grounded = new ToolStripMenuItem("Grounded apps: none");
        grounded.Enabled = false; m.Items.Add(grounded);
        m.Items.Add(today);
        ToolStripMenuItem quiet = new ToolStripMenuItem("Pause everyday pop-ups for 1 hour");
        quiet.Click += delegate { if (DateTime.Now < quietUntil) { quietUntil = DateTime.MinValue; Toast.Show("Pop-ups are back on", "", 4, null, null); } else { quietUntil = DateTime.Now.AddHours(1); Toast.Show("Quiet for an hour", "Calls, grounding and announcements still show.", 5, null, null); } };
        m.Items.Add(quiet);
        ToolStripMenuItem addGame = new ToolStripMenuItem("Count the app I was just in as playing");
        addGame.Click += delegate { AddCustom("play"); };
        ToolStripMenuItem addVid = new ToolStripMenuItem("Count the app I was just in as watching");
        addVid.Click += delegate { AddCustom("watch"); };
        m.Items.Add(addGame); m.Items.Add(addVid);
        ToolStripMenuItem doing = new ToolStripMenuItem("What am I counting now?");
        doing.Click += delegate { Say("Right now", nowDoing, 8, null, null); };
        m.Items.Add(doing);
        m.Items.Add("Who is using this computer...", null, delegate { sync.BeginInvoke(new Action(delegate { PickPerson(true); })); });
        m.Items.Add("Open Homebase", null, delegate { try { Process.Start(server + "/app"); } catch { } });
        m.Items.Add(new ToolStripSeparator());
        m.Items.Add("Remove from this computer", null, delegate { Remove(true); Quit(); });
        m.Opening += delegate { quiet.Text = DateTime.Now < quietUntil ? "Turn pop-ups back on" : "Pause everyday pop-ups for 1 hour"; grounded.Text = groundedNow.Count == 0 ? "Grounded apps: none" : "Grounded: " + string.Join(", ", groundedNow.ToArray()); who.Text = cur == null ? "Homebase screen time is on" : "Counting for " + cur.Name; today.Text = "Today: " + Duration(Total()) + " playing or watching"; };
        tray = new NotifyIcon { Icon = SystemIcons.Information, Text = "Homebase", Visible = true, ContextMenuStrip = m };
        tray.DoubleClick += delegate { Say("Homebase" + (cur == null ? "" : " - " + cur.Name), nowDoing + "\nToday: " + Duration(Total()) + " playing or watching", 9, "Open Homebase", delegate { Open("/app"); }); };
        tray.BalloonTipClicked += delegate { Open(pendingOpen ?? "/app"); pendingOpen = null; };
    }
    static bool hbDirIsNull() { return Environment.GetEnvironmentVariable("HB_DIR") == null; }       // tests never update themselves
    static string ShownFile { get { return Path.Combine(Dir, "shown.txt"); } }
    static int LastShownBuild() { try { return int.Parse(File.ReadAllText(ShownFile).Trim()); } catch { return 0; } }
    static void SetShownBuild(int b) { try { File.WriteAllText(ShownFile, b.ToString()); } catch { } }
    string pendingOpen;
    DateTime lastUpdateCheck = DateTime.MinValue;

    // Ask the server for the newest build; if it is newer, download it, swap the files and restart. Nobody has to re-download again.
    void CheckUpdate()
    {
        int st; string txt = Http("GET", "/api/windows/version", null, out st); if (st != 200) return;
        var d = Json.Deserialize<Dictionary<string, object>>(txt);
        if (!d.ContainsKey("build") || Convert.ToInt32(d["build"]) <= Build) return;
        string tmp = Path.Combine(Dir, "update.exe");
        HttpWebRequest r = (HttpWebRequest)WebRequest.Create(server + "/download/windows"); r.Timeout = 60000; r.UserAgent = "HomebaseTracker/" + Build;
        using (HttpWebResponse x = (HttpWebResponse)r.GetResponse()) using (Stream src = x.GetResponseStream()) using (FileStream dst = File.Create(tmp)) src.CopyTo(dst);
        FileInfo fi = new FileInfo(tmp);
        byte[] head = new byte[2]; using (FileStream f = File.OpenRead(tmp)) f.Read(head, 0, 2);
        if (fi.Length < 20000 || head[0] != (byte)'M' || head[1] != (byte)'Z') { try { File.Delete(tmp); } catch { } Log("update: download looked wrong"); return; }
        // a running program can be renamed on Windows even though it cannot be overwritten, so move the old one aside and put the new one in its place
        try { if (File.Exists(Installed + ".old")) File.Delete(Installed + ".old"); } catch { }
        try { if (File.Exists(Installed)) File.Move(Installed, Installed + ".old"); } catch (Exception e) { Log("update rename: " + e.Message); }
        File.Move(tmp, Installed);
        Log("updated from build " + Build + " to " + d["build"]);
        Say("Homebase updated", "Now on the newest version.", 6, null, null);
        Process.Start(Installed);                                                          // the new copy takes over from this one
        stopping = true; Thread.Sleep(600); Quit();
    }
    readonly List<string> groundedNow = new List<string>();
    static string CustomFile { get { return Path.Combine(Dir, "custom.txt"); } }
    void LoadCustom() { try { if (File.Exists(CustomFile)) foreach (string l in File.ReadAllLines(CustomFile)) { string[] p = l.Split('|'); if (p.Length == 3) CustomPicks[p[0]] = new[] { p[1], p[2] }; } } catch { } }
    void AddCustom(string cat)
    {
        string n = (lastFgName ?? "").ToLower();
        if (n == "" || n == "homebasetracker" || n == "explorer") { Say("Homebase", "Open the game or video first, then pick this from the tray menu.", 8, null, null); return; }
        CustomPicks[n] = new[] { lastFgName, cat };
        try { File.WriteAllLines(CustomFile, CustomPicks.Select(kv => kv.Key + "|" + kv.Value[0] + "|" + kv.Value[1]).ToArray()); } catch { }
        Say("Got it", lastFgName + " will now count as " + (cat == "play" ? "playing" : "watching") + " on this computer.", 8, null, null);
    }
    void Open(string path) { try { Process.Start(server + path); } catch { } }

    // a call ringing on this computer: beep a ring pattern and show a balloon you can click to answer in the web app
    void Ring(int callId, string who)
    {
        if (ringingCall == callId) return;
        ringingCall = callId; pendingOpen = "/app";
        Say("\U0001F4DE " + who + " is calling", "Homebase call", 30, "Answer in Homebase", delegate { Open("/app"); });
        new Thread(delegate ()
        {
            DateTime start = DateTime.Now;
            while (!stopping && ringingCall == callId && (DateTime.Now - start).TotalSeconds < 35)
            {
                try { Console.Beep(880, 350); Thread.Sleep(180); Console.Beep(660, 350); } catch { try { SystemSounds.Exclamation.Play(); } catch { } }
                Thread.Sleep(1600);
                try { int st; string txt = Http("GET", "/api/calls/" + callId, null, out st); var d = Json.Deserialize<Dictionary<string, object>>(txt); if (!d.ContainsKey("status") || Convert.ToString(d["status"]) != "ringing") break; } catch { }
            }
            if (ringingCall == callId) ringingCall = 0;
        }) { IsBackground = true }.Start();
    }

    void Quit() { stopping = true; if (tray != null) { tray.Visible = false; tray.Dispose(); } Application.Exit(); }
    // an always-visible pop-up (drawn by us), plus a Windows balloon as a bonus when the user has notifications on
    // Everyday information (nudges, tips, confirmations) can be paused. Important things (calls, SOS, grounding, announcements) always show.
    void Say(string title, string text) { Say(title, text, 7, null, null); }
    void Important(string title, string text, int seconds) { try { if (sync != null && sync.IsHandleCreated) sync.BeginInvoke(new Action(delegate { try { Toast.Show(title, text, seconds, null, null); } catch { } })); } catch { } }
    void Say(string title, string text, int seconds, string action, Action onAction)
    {
        if (DateTime.Now < quietUntil && action == null) return;
        try { if (sync != null && sync.IsHandleCreated) sync.BeginInvoke(new Action(delegate { try { Toast.Show(title, text, seconds, action, onAction); } catch (Exception e) { Log("toast: " + e.Message); } })); } catch { }
    }
    int Total() { int t = 0; foreach (var v in apps.Values) { int s; int.TryParse(v[2], out s); t += s; } return t; }
    static string Duration(int sec) { int m = sec / 60; return m >= 60 ? (m / 60) + " h " + (m % 60).ToString("00") + " min" : m + " min"; }

    void DropCurrent()
    {
        if (cur == null) return;
        string gone = cur.Name; profiles.Remove(cur);
        if (profiles.Count == 0) { Say("Homebase", "This computer was removed from Homebase."); Remove(false); Quit(); return; }
        cur = profiles[0]; apps.Clear(); dirty = false; cursor = -1; LoadToday(); SaveConfig();
        Say("Homebase", gone + " was removed from this computer. Now counting for " + cur.Name + ".");
    }

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
            if (st == 401) { DropCurrent(); return; }
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
                    {
                        string type = Convert.ToString(ev["type"]);
                        var pl = ev.ContainsKey("payload") ? ev["payload"] as Dictionary<string, object> : null;
                        if (type == "nudge" && pl != null) Say("Time for a break?", Convert.ToString(pl["text"]));
                        else if (type == "message" && pl != null)
                        {
                            string kind = pl.ContainsKey("kind") ? Convert.ToString(pl["kind"]) : "text";
                            string body = pl.ContainsKey("body") ? Convert.ToString(pl["body"]) : "";
                            string from = pl.ContainsKey("name") ? Convert.ToString(pl["name"]) : "Homebase";
                            bool quiet = pl.ContainsKey("quiet") && Convert.ToBoolean(pl["quiet"]);
                            if (!quiet)
                            {
                                if (kind == "sos") { Important("\U0001F6A8 " + from + " needs help", body, 20); try { SystemSounds.Hand.Play(); } catch { } }
                                else if (from == "Homebase") Important("\U0001F4E2 Announcement", body, 12);
                                else { Say(from, body); try { SystemSounds.Asterisk.Play(); } catch { } }
                            }
                        }
                        else if (type == "call" && pl != null)
                        {
                            int cid = pl.ContainsKey("id") ? Convert.ToInt32(pl["id"]) : 0;
                            if (cid > 0 && seenCalls.Add(cid)) Ring(cid, pl.ContainsKey("name") ? Convert.ToString(pl["name"]) : "Someone");
                        }
                    }
                }
        }
        catch (Exception e) { Log("events: " + e.Message); }
        try { EnforceBlocks(); } catch (Exception e) { Log("blocks: " + e.Message); }
        if ((hbDirIsNull() || Environment.GetEnvironmentVariable("HB_UPDATE") != null) && (now - lastUpdateCheck).TotalMinutes > 20) { lastUpdateCheck = now; try { CheckUpdate(); } catch (Exception e) { Log("update: " + e.Message); } }
        if ((now - lastBeat).TotalSeconds > 30) { lastBeat = now; try { int hs; Http("POST", "/api/devices/status", new Dictionary<string, object> { { "status", nowDoing }, { "version", "b" + Build } }, out hs); } catch (Exception e) { Log("beat: " + e.Message); } }
        if ((now - lastApps).TotalMinutes > 60) { lastApps = now; try { ReportApps(); } catch (Exception e) { Log("apps: " + e.Message); } }
        double idleNow = Native.Idle();
        if (idleNow > 1800) wasAway = true;                                           // away for 30+ minutes: someone else may sit down next
        if (wasAway && idleNow < 20 && profiles.Count > 1) { wasAway = false; sync.BeginInvoke(new Action(delegate { PickPerson(false); })); }
        if (locked) return;
        try { Count(elapsed); } catch (Exception e) { Log("count: " + e.Message); }
        if (dirty && (now - lastSend).TotalSeconds >= (Environment.GetEnvironmentVariable("HB_TICK") != null ? 1 : 25)) { if (Send()) { dirty = false; lastSend = now; } SaveToday(); }
    }

    static void SelfTest()
    {
        var cases = new object[][] {
            // procName, procTitle, groundKey, groundLabel, shouldMatch
            new object[] { "RobloxPlayerBeta", "Roblox", "robloxplayerlauncher", "Roblox", true },   // launcher grounded, game process running
            new object[] { "RobloxPlayerBeta", "Roblox", "roblox", "Roblox", true },
            new object[] { "javaw", "Minecraft 1.21", "minecraft", "Minecraft", true },               // Minecraft runs as javaw, matched by title
            new object[] { "FortniteClient-Win64-Shipping", "Fortnite", "fortnite", "Fortnite", true },
            new object[] { "Discord", "general - Discord", "discord", "Discord", true },
            new object[] { "notepad", "Untitled - Notepad", "notepad", "Notepad", true },
            new object[] { "steamwebhelper", "Steam", "steam", "Steam", true },
            new object[] { "Code", "file.cs - Visual Studio Code", "roblox", "Roblox", false },        // homework app not grounded
            // the over-closing bug: unrelated programs whose WINDOW TITLE merely mentions the grounded app must stay open
            new object[] { "Discord", "Roblox server - Discord", "roblox", "Roblox", false },
            new object[] { "NotesApp", "my roblox notes", "roblox", "Roblox", false },
            new object[] { "Claude", "Roblox grounding fix - Claude", "roblox", "Roblox", false },
            new object[] { "explorer", "Roblox - File Explorer", "roblox", "Roblox", false },
            new object[] { "WINWORD", "Roblox essay - Word", "robloxplayerlauncher", "Roblox", false },
            new object[] { "svchost", "", "steam", "Steam", false },
            new object[] { "GoogleCrashHandler", "", "chrome", "Google Chrome", false },               // the word "google" in the label must not match unrelated Google helpers
            new object[] { "javaw", "Minecraft 1.21", "minecraft", "Minecraft", true },                // but real Minecraft (a java program, found by its title) still closes
            new object[] { "javaw", "IntelliJ IDEA", "minecraft", "Minecraft", false },                // and other java programs do not
            new object[] { "RobloxStudioBeta", "Roblox Studio", "roblox", "Roblox", true },
            new object[] { "WINWORD", "Essay - Word", "minecraft", "Minecraft", false },
            new object[] { "chrome", "Homework - Google Docs", "roblox", "Roblox", false },
        };
        int ok = 0;
        foreach (var c in cases)
        {
            bool got = MatchesBlock((string)c[0], (string)c[1], (string)c[2], (string)c[3], "");
            bool want = (bool)c[4];
            Console.WriteLine((got == want ? "PASS " : "FAIL ") + c[0] + " + ground[" + c[3] + "] -> " + got + " (want " + want + ")");
            if (got == want) ok++;
        }
        Console.WriteLine(ok + "/" + cases.Length + " passed");
    }

    // only letters and digits, lower case: so "Google Chrome", "google-chrome" and "GoogleChrome" all compare the same
    static string Norm(string s) { if (s == null) return ""; var sb = new StringBuilder(); foreach (char ch in s.ToLower()) if (char.IsLetterOrDigit(ch)) sb.Append(ch); return sb.ToString(); }

    // the name of a program without the noise that differs between a launcher and the game it starts:
    // "RobloxPlayerLauncher" and "RobloxPlayerBeta" both become "roblox"
    static readonly string[] NoiseWords = { "launcher", "bootstrapper", "updater", "update", "installer", "setup", "helper", "service", "client", "shipping", "win64", "win32", "x64", "x86", "beta", "player", "game", "app", "exe" };
    static string Core(string name)
    {
        string n = Norm(name); bool again = true;
        while (again) { again = false; foreach (string w in NoiseWords) if (n.Length > w.Length + 3 && n.EndsWith(w)) { n = n.Substring(0, n.Length - w.Length); again = true; } }
        return n;
    }

    // Programs that are never closed, whatever is grounded. Closing these would break Windows or the person's other work.
    static readonly string[] Protected = { "system", "idle", "registry", "smss", "csrss", "wininit", "winlogon", "services", "lsass", "svchost", "dwm", "explorer", "taskmgr", "conhost", "cmd", "powershell", "pwsh",
        "windowsterminal", "wt", "claude", "code", "devenv", "homebasetracker", "searchhost", "startmenuexperiencehost", "shellexperiencehost", "textinputhost", "sihost", "ctfmon", "runtimebroker",
        "applicationframehost", "msedgewebview2", "securityhealthsystray", "fontdrvhost", "audiodg", "spoolsv", "dllhost", "winlogon", "lockapp", "logonui", "backgroundtaskhost", "googlecrashhandler", "googleupdate" };

    // Two names are "the same app" when one is the other plus more, and the shared part is long enough to be meaningful:
    // "roblox" ~ "robloxstudio", "steam" ~ "steamwebhelper", but never a short chance overlap.
    static bool SameApp(string a, string b)
    {
        if (a.Length < 4 || b.Length < 4) return false;
        string shorter = a.Length <= b.Length ? a : b, longer = a.Length <= b.Length ? b : a;
        return longer == shorter || (shorter.Length >= 5 && longer.StartsWith(shorter));
    }

    // Does a running program belong to a grounded app? It is decided by WHAT THE PROGRAM IS (its exe name and, when known, the product name
    // written into the file), never by text in a window title, because a chat, a notes file or a document can mention any app by name.
    // The one exception is Java programs (Minecraft's Java edition runs as "javaw"), which are recognised by a title that names the app.
    public static bool MatchesBlock(string procName, string procTitle, string key, string label, string product = "")
    {
        string pn = Norm(procName);
        if (pn.Length == 0 || Array.IndexOf(Protected, pn) >= 0) return false;
        string pc = Core(procName), kc = Core(key), lc = Core(label);
        if (SameApp(pc, kc) || SameApp(pc, lc)) return true;                                             // the program is the app (or its launcher / helper)
        string pr = Norm(product);
        if (pr.Length >= 4 && ((lc.Length >= 4 && pr.Contains(lc)) || (kc.Length >= 4 && pr.Contains(kc)))) return true;   // the file says it is the app
        if (pn == "javaw" || pn == "java")                                                                // Minecraft (Java edition) is just "javaw"; its window says what it is
        {
            string t = Norm(procTitle);
            if ((kc.Length >= 4 && t.Contains(kc)) || (lc.Length >= 4 && t.Contains(lc))) return true;
        }
        return false;
    }

    static string ProductOf(Process p) { try { var v = p.MainModule.FileVersionInfo; return (v.ProductName ?? "") + " " + (v.FileDescription ?? ""); } catch { return ""; } }

    // grounded apps: close them (or minimise a grounded website) and say why
    void EnforceBlocks()
    {
        int st; string txt = Http("GET", "/api/blocks", null, out st); if (st != 200) return;
        var d = Json.Deserialize<Dictionary<string, object>>(txt);
        if (!d.ContainsKey("blocks")) return;
        groundedNow.Clear();
        Process[] procs = Process.GetProcesses();
        foreach (object o in (System.Collections.ArrayList)d["blocks"])
        {
            var b = (Dictionary<string, object>)o; string key = Convert.ToString(b["key"]), label = Convert.ToString(b["label"]);
            DateTime until = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddSeconds(Convert.ToDouble(b["until"])).ToLocalTime();
            double activeAt = b.ContainsKey("active_at") ? Convert.ToDouble(b["active_at"]) : 0;
            double nowEpoch = (DateTime.UtcNow - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalSeconds;
            if (activeAt > nowEpoch)                                                     // the warning minute: let them finish, warn once
            {
                DateTime lw;
                if (!told.TryGetValue("warn:" + label, out lw) || (DateTime.Now - lw).TotalSeconds > 50) { told["warn:" + label] = DateTime.Now; Important("\u23F3 Finish up: " + label, label + " will be grounded in about a minute.", 12); }
                continue;
            }
            bool hit = false;
            if (Environment.GetEnvironmentVariable("HB_DEBUG") != null) Log("enforce key=" + key + " label=" + label + " active_at_in=" + (int)(activeAt - nowEpoch) + "s processes=" + procs.Length);
            foreach (Process p in procs)
            {
                try
                {
                    string name = p.ProcessName, title = p.MainWindowTitle ?? "";
                    if (Environment.GetEnvironmentVariable("HB_DEBUG") != null && MatchesBlock(name, title, key, label, ProductOf(p))) Log("  will close " + name + " title=[" + title + "]");
                    if (name.Equals("explorer", StringComparison.OrdinalIgnoreCase) || p.Id == Process.GetCurrentProcess().Id) continue;
                    bool browser = Array.IndexOf(Browsers, name.ToLower()) >= 0;
                    if (browser)
                    {
                        // a grounded website: minimise the browser only when its current tab is the grounded one
                        string kt = Core(key), lt = Core(label); string tn = Norm(title);
                        if (p.MainWindowHandle != IntPtr.Zero && ((kt.Length >= 4 && tn.Contains(kt)) || (lt.Length >= 4 && tn.Contains(lt)))) { Native.ShowWindow(p.MainWindowHandle, 6); hit = true; }
                    }
                    else if (MatchesBlock(name, title, key, label, ProductOf(p))) { try { p.Kill(); hit = true; } catch (Exception ke) { Log("kill " + name + ": " + ke.Message); } }
                }
                catch { }
            }
            DateTime last;
            if (hit && (!told.TryGetValue(label, out last) || (DateTime.Now - last).TotalSeconds > 180)) { told[label] = DateTime.Now; Important("\U0001F6AB " + label + " is grounded", "Closed because it is grounded until " + until.ToString("t") + ".", 8); try { SystemSounds.Hand.Play(); } catch { } }
            groundedNow.Add(label + " until " + until.ToString("t"));
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
        string[] c = Classify(name, title, path);
        if (c == null && Array.IndexOf(NotGames, name.ToLower()) < 0 && Array.IndexOf(Browsers, name.ToLower()) < 0 && (D3DFullScreen() || (full && path != null && !Regex.IsMatch(path, "\\\\(Windows|Microsoft Office|Office1\\d)\\\\", RegexOptions.IgnoreCase))))
            c = new[] { "pc:" + name.ToLower(), name, "play" };                                 // Windows says it is a full-screen game
        lastFgName = name;
        bool active = c != null && (c[2] == "watch" ? (idle <= ActiveSeconds || full) : idle <= ActiveSeconds);
        if (Environment.GetEnvironmentVariable("HB_DEBUG") != null) Log("fg=" + name + " title=\"" + title + "\" idle=" + (int)idle + " full=" + full + " class=" + (c == null ? "none" : c[2]) + " active=" + active);
        if (c == null) { nowDoing = name + " - not counted (not a game or video)"; return; }
        if (!active) { nowDoing = c[1] + " - open but idle, not counting"; return; }
        nowDoing = "counting " + c[1] + " (" + c[2] + ")";
        string[] cur; int sec = 0;
        if (apps.TryGetValue(c[0], out cur)) int.TryParse(cur[2], out sec);
        apps[c[0]] = new[] { c[1], c[2], (sec + (int)elapsed).ToString() }; dirty = true;
    }

    // returns { key, label, category } for playing/watching, or null (homework, work, everyday apps)
    [DllImport("shell32.dll")] static extern int SHQueryUserNotificationState(out int state);
    static bool D3DFullScreen() { try { int st; return SHQueryUserNotificationState(out st) == 0 && st == 3; } catch { return false; } }    // 3 = a full-screen game is running

    static readonly Regex GameSites = new Regex("Poki|CrazyGames|Miniclip|itch\\.io|Friv|Y8 Games|Kongregate|Coolmath ?Games|Krunker|Slither\\.io|Agar\\.io|Roblox|Minecraft|Shell Shockers|1v1\\.LOL|Geoguessr|Chess\\.com|Lichess|Wordle|Kahoot|Gimkit|Blooket|PlayStation|Xbox Cloud|GeForce NOW|Steam|Epic Games|Game", RegexOptions.IgnoreCase);
    static readonly string[] NotGames = { "winword", "excel", "powerpnt", "onenote", "outlook", "acrobat", "acrord32", "code", "devenv", "teams", "zoom", "mstsc", "anydesk", "teamviewer", "notepad", "mspaint", "obs64", "claude", "explorer", "taskmgr", "cmd", "powershell", "windowsterminal", "slack", "discord" };
    public static readonly Dictionary<string, string[]> CustomPicks = new Dictionary<string, string[]>();     // apps the family told us count (process name -> { label, category })

    static string[] Classify(string proc, string title, string path)
    {
        string n = proc.ToLower();
        string[] mine; if (CustomPicks.TryGetValue(n, out mine)) return new[] { "pc:" + n, mine[0], mine[1] };
        if (Array.IndexOf(Browsers, n) >= 0)
        {
            if (GameSites.IsMatch(title) && !Regex.IsMatch(title, "Docs|Sheets|Slides|Classroom|Drive", RegexOptions.IgnoreCase)) return new[] { "pc:web:games", "Browser games", "play" };
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

    // the apps on this computer, so a parent can pick them to ground instead of typing. Names only, nothing about what is inside them.
    void ReportApps()
    {
        Dictionary<string, string> found = new Dictionary<string, string>();                     // key (exe name) -> label
        // 1. Start Menu shortcuts: the apps someone actually has, with friendly names
        foreach (Environment.SpecialFolder sf in new[] { Environment.SpecialFolder.StartMenu, Environment.SpecialFolder.CommonStartMenu, Environment.SpecialFolder.Programs, Environment.SpecialFolder.CommonPrograms, Environment.SpecialFolder.Desktop })
        {
            string root; try { root = Environment.GetFolderPath(sf); } catch { continue; }
            if (string.IsNullOrEmpty(root) || !Directory.Exists(root)) continue;
            string[] links; try { links = Directory.GetFiles(root, "*.lnk", SearchOption.AllDirectories); } catch { continue; }
            foreach (string lnk in links)
            {
                try
                {
                    string label = Path.GetFileNameWithoutExtension(lnk);
                    dynamic sh = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell"));
                    dynamic sc = sh.CreateShortcut(lnk);
                    string target = (string)sc.TargetPath;
                    if (string.IsNullOrEmpty(target) || !target.ToLower().EndsWith(".exe")) continue;
                    string key = Path.GetFileNameWithoutExtension(target).ToLower();
                    if (key == "" || key == "unins000" || key.StartsWith("setup") || key.StartsWith("install") || key == "explorer") continue;
                    if (!found.ContainsKey(key)) found[key] = label;
                }
                catch { }
            }
        }
        // 2. anything with a window open right now (catches games launched from elsewhere)
        foreach (Process p in Process.GetProcesses())
        {
            try { if (p.MainWindowHandle != IntPtr.Zero && !string.IsNullOrEmpty(p.MainWindowTitle)) { string key = p.ProcessName.ToLower(); if (!found.ContainsKey(key)) found[key] = p.ProcessName; } } catch { }
        }
        List<object> list = new List<object>();
        foreach (var kv in found) list.Add(new Dictionary<string, object> { { "key", kv.Key }, { "label", kv.Value } });
        int st; Http("POST", "/api/devices/apps", new Dictionary<string, object> { { "apps", list } }, out st);
    }

    bool Send()
    {
        try
        {
            List<object> list = new List<object>();
            foreach (var kv in apps) list.Add(new Dictionary<string, object> { { "pkg", kv.Key }, { "label", kv.Value[0] }, { "cat", kv.Value[1] }, { "sec", int.Parse(kv.Value[2]) } });
            int st; Http("POST", "/api/screentime", new Dictionary<string, object> { { "day", day }, { "apps", list } }, out st);
            if (st == 401) { DropCurrent(); }
            return st == 200;
        }
        catch (Exception e) { Log("send: " + e.Message); return false; }
    }
}
