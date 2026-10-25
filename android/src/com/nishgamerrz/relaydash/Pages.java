package com.nishgamerrz.relaydash;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Every byte of HTML the built-in dashboard serves.
 *
 * Server-rendered, no CDN, no framework, no external fonts: it has to work on a
 * phone browser talking to a TV that may have no internet of its own.
 */
public final class Pages {

    private Pages() {
    }

    private static final String CSS =
            ":root{--bg:#0B0910;--card:#151221;--card2:#1B1730;--line:#2A2338;--text:#EDEAF5;" +
            "--muted:#9A93AE;--accent:#8B5CF6;--cyan:#22D3EE;--ok:#34D399;--bad:#F87171;" +
            "--warn:#FBBF24}" +
            "*{box-sizing:border-box}" +
            "body{margin:0;background:var(--bg);color:var(--text);" +
            "font:15px/1.5 -apple-system,Roboto,'Segoe UI',system-ui,sans-serif;" +
            "-webkit-text-size-adjust:100%}" +
            "body::before{content:'';position:fixed;inset:-20% -20% auto -20%;height:300px;z-index:-1;" +
            "background:radial-gradient(55% 55% at 25% 0%,rgba(139,92,246,.32),transparent 70%)," +
            "radial-gradient(45% 45% at 95% 5%,rgba(34,211,238,.18),transparent 70%)}" +
            ".wrap{max-width:900px;margin:0 auto;padding:18px 15px 40px}" +
            "header{display:flex;align-items:center;gap:11px;margin-bottom:16px}" +
            ".mark{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;" +
            "background:linear-gradient(140deg,#8B5CF6,#22D3EE)}" +
            ".mark svg{width:21px;height:21px}" +
            "h1{font-size:17px;margin:0;font-weight:650}" +
            ".sub{font-size:12px;color:var(--muted);margin-top:2px}" +
            ".pill{margin-left:auto;font-size:11px;font-weight:700;letter-spacing:.5px;" +
            "text-transform:uppercase;padding:7px 11px;border-radius:999px;border:1px solid var(--line);" +
            "background:var(--card)}" +
            ".pill.ok{color:var(--ok);border-color:rgba(52,211,153,.45);background:rgba(52,211,153,.1)}" +
            ".pill.bad{color:var(--bad);border-color:rgba(248,113,113,.45);background:rgba(248,113,113,.1)}" +
            ".card{background:linear-gradient(180deg,var(--card),var(--card2));border:1px solid var(--line);" +
            "border-radius:16px;padding:16px;margin-bottom:13px;box-shadow:0 10px 26px rgba(0,0,0,.35)}" +
            "h2{font-size:11.5px;letter-spacing:1.1px;text-transform:uppercase;color:var(--muted);" +
            "margin:0 0 11px;font-weight:700}" +
            ".big{font:700 30px/1.2 ui-monospace,'Roboto Mono',monospace;color:var(--ok);margin:0}" +
            ".big.bad{color:var(--bad)}" +
            ".url{font:700 19px/1.3 ui-monospace,'Roboto Mono',monospace;color:var(--cyan);" +
            "word-break:break-all;margin:6px 0 0}" +
            ".muted{color:var(--muted);font-size:13px;margin:6px 0 0}" +
            "table{width:100%;border-collapse:collapse;font-size:13px}" +
            "th,td{text-align:left;padding:9px 8px;border-bottom:1px solid var(--line);vertical-align:top}" +
            "th{color:var(--muted);font-weight:600;font-size:11px;letter-spacing:.8px;text-transform:uppercase}" +
            "tr:last-child td{border-bottom:none}" +
            "td.mono{font-family:ui-monospace,'Roboto Mono',monospace;color:var(--cyan)}" +
            "input,button,textarea{font-family:inherit;font-size:15px}" +
            "input[type=password],input[type=text]{width:100%;padding:13px 14px;border-radius:12px;" +
            "border:1px solid var(--line);background:#0F0C19;color:var(--text);outline:none}" +
            "input:focus{ border-color:var(--accent);box-shadow:0 0 0 3px rgba(139,92,246,.2)}" +
            "button{border:1px solid var(--line);background:var(--card2);color:var(--text);" +
            "border-radius:12px;padding:12px 15px;font-weight:650;cursor:pointer}" +
            "button.primary{background:linear-gradient(135deg,var(--accent),#6D5AE6);border-color:transparent}" +
            "button:active{filter:brightness(1.15)}" +
            "button.ghost{background:#171325}" +
            ".row{display:flex;gap:9px;flex-wrap:wrap}" +
            ".row>*{flex:1 1 150px}" +
            "pre{background:#0B0915;border:1px solid var(--line);border-radius:12px;padding:13px;" +
            "font:12.5px/1.5 ui-monospace,'Roboto Mono',monospace;color:#D8D2EA;overflow:auto;" +
            "max-height:340px;white-space:pre-wrap;word-break:break-word;margin:10px 0 0}" +
            ".err{color:#FECACA;background:rgba(248,113,113,.1);border:1px solid rgba(248,113,113,.35);" +
            "padding:11px 13px;border-radius:11px;font-size:13.5px;margin-bottom:12px}" +
            ".warn{color:var(--warn);font-size:12.5px;margin-top:9px}" +
            "footer{color:#6E6785;font-size:11.5px;text-align:center;margin-top:18px;line-height:1.6}" +
            ".hint{color:var(--muted);font-size:12.5px;margin-top:8px}" +
            "@media(min-width:900px){body{font-size:16px}.wrap{padding:30px 26px 50px}h1{font-size:21px}" +
            ".big{font-size:38px}}";

    /* ── shared bits ─────────────────────────────────────────────────────── */

    private static String head(String title) {
        return "<!DOCTYPE html><html lang=en><head><meta charset=utf-8>" +
                "<meta name=viewport content='width=device-width,initial-scale=1'>" +
                "<meta name=theme-color content='#0B0910'>" +
                "<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body><div class=wrap>";
    }

    private static String header(String subtitle, boolean running, String right) {
        return "<header><div class=mark><svg viewBox='0 0 24 24' fill='none'>" +
                "<path d='M13.6 2 6.9 13h4.4l-1.5 9 7.3-11h-4.4l1.9-9Z' fill='#fff'/></svg></div>" +
                "<div><h1>Relay Console</h1><div class=sub>" + esc(subtitle) + "</div></div>" +
                "<span class='pill " + (running ? "ok" : "bad") + "'>" +
                (running ? "Running" : "Stopped") + "</span>" +
                (right == null ? "" : right) +
                "</header>";
    }

    private static String foot(String version) {
        return "<footer>Relay Dashboard " + esc(version) +
                " &middot; served by the TV itself &middot; LAN only, never the internet</footer>" +
                "</div></body></html>";
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /* ── login ───────────────────────────────────────────────────────────── */

    public static String login(String hostName, String error, boolean noPasswordSet, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append(head("Sign in - Relay Console"));
        sb.append(header(hostName, true, null));
        sb.append("<form class=card method=post action=/login>");
        sb.append("<h2>This dashboard is password protected</h2>");
        if (noPasswordSet) {
            sb.append("<div class=err>No password has been set on the device yet. " +
                    "Open the Relay Dashboard app on the TV and set one.</div>");
        } else if (error != null && error.length() > 0) {
            sb.append("<div class=err>").append(esc(error)).append("</div>");
        }
        sb.append("<input type=password name=password placeholder='Password' autofocus " +
                "autocomplete=current-password inputmode=text>");
        sb.append("<div class=row style='margin-top:11px'>");
        sb.append("<button class=primary type=submit>Unlock dashboard</button></div>");
        sb.append("<p class=hint>The password is the one stored on the TV. " +
                "It is checked on the TV, never sent anywhere else.</p>");
        sb.append("</form>");
        sb.append(foot(version));
        return sb.toString();
    }

    public static String notFound(String hostName, String version) {
        StringBuilder sb = new StringBuilder();
        sb.append(head("Not found - Relay Console"));
        sb.append(header(hostName, true, null));
        sb.append("<div class=card><h2>Page not found</h2>" +
                "<p class=muted>That address is not part of this dashboard.</p>" +
                "<p><a href='/' style='color:#22D3EE'>Back to the dashboard</a></p></div>");
        sb.append(foot(version));
        return sb.toString();
    }

    /* ── dashboard ───────────────────────────────────────────────────────── */

    public static String dashboard(String hostName, String version, String url,
                                   long uptimeMs, int port, String deviceModel,
                                   String androidVersion, List<DeviceLog.Entry> devices,
                                   long now, boolean hasPassword) {
        StringBuilder sb = new StringBuilder();
        sb.append(head("Relay Console"));
        sb.append(header(hostName, true,
                "<form method=post action=/logout style='margin:0 0 0 8px'>" +
                "<button class=ghost type=submit style='padding:8px 12px;font-size:12.5px'>Sign out</button></form>"));

        // status
        sb.append("<div class=card><h2>Status</h2><p class=big>Running</p>" +
                "<p class=url>").append(esc(url)).append("</p>");
        sb.append("<p class=muted>Serving for ").append(esc(uptime(uptimeMs)))
          .append(" &middot; port ").append(port)
          .append(" &middot; ").append(devices.size()).append(" device")
          .append(devices.size() == 1 ? "" : "s").append(" seen</p>");
        if (!hasPassword) {
            sb.append("<p class=warn>No password is set yet - anyone on this Wi-Fi can open " +
                    "the console. Set one in the app on the TV.</p>");
        }
        sb.append("</div>");

        // console
        sb.append("<div class=card><h2>Console</h2>");
        sb.append("<p class=hint style='margin-top:0'>Runs in the TV's own shell, as the " +
                "dashboard app's user (no root). Output is capped at 32 KB / 10 s.</p>");
        sb.append("<form id=cmdform style='margin-top:11px'>");
        sb.append("<input type=text id=cmd name=cmd placeholder='getprop ro.product.model'" +
                " autocapitalize=off autocorrect=off spellcheck=false autocomplete=off>");
        sb.append("<div class=row style='margin-top:10px'>");
        sb.append("<button class=primary type=submit id=runbtn>Run</button>");
        sb.append("<button class=ghost type=button id=clearout>Clear</button>");
        sb.append("</div></form>");
        sb.append("<pre id=out>Ready.</pre></div>");

        // devices
        sb.append("<div class=card><h2>Devices that opened this dashboard</h2>");
        if (devices.isEmpty()) {
            sb.append("<p class=muted>Nothing yet - you are the first request that was recorded.</p>");
        } else {
            sb.append("<table><tr><th>Device</th><th>Address</th><th>Last seen</th><th>Hits</th></tr>");
            for (int i = 0; i < devices.size(); i++) {
                DeviceLog.Entry e = devices.get(i);
                sb.append("<tr><td>").append(esc(shortAgent(e.agent)))
                  .append("<div class=muted style='margin:0;font-size:11.5px'>")
                  .append(esc(e.path == null ? "" : e.path)).append("</div></td>");
                sb.append("<td class=mono>").append(esc(e.ip)).append("</td>");
                sb.append("<td>").append(esc(ago(now - e.lastSeen))).append("</td>");
                sb.append("<td>").append(e.hits).append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>");

        // this host
        sb.append("<div class=card><h2>This host</h2><table>");
        sb.append("<tr><th>Device</th><td>").append(esc(deviceModel)).append("</td></tr>");
        sb.append("<tr><th>Android</th><td>").append(esc(androidVersion)).append("</td></tr>");
        sb.append("<tr><th>App</th><td>Relay Dashboard ").append(esc(version)).append("</td></tr>");
        sb.append("<tr><th>Port</th><td class=mono>").append(port).append("</td></tr>");
        sb.append("</table></div>");

        sb.append("<script>").append(DASHBOARD_JS).append("</script>");
        sb.append(foot(version));
        return sb.toString();
    }

    private static final String DASHBOARD_JS =
            "(function(){" +
            "var f=document.getElementById('cmdform'),o=document.getElementById('out')," +
            "b=document.getElementById('runbtn'),i=document.getElementById('cmd');" +
            "var hist=[],hi=-1;" +
            "function post(body){return fetch('/api/run',{method:'POST'," +
            "headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body," +
            "credentials:'same-origin'}).then(function(r){return r.json().then(function(j){" +
            "return {s:r.status,j:j};});});}" +
            "f.addEventListener('submit',function(ev){ev.preventDefault();" +
            "var cmd=i.value.trim();if(!cmd)return;hist.push(cmd);hi=hist.length;" +
            "b.disabled=true;o.textContent='$ '+cmd+'\\n\\n…';" +
            "post('cmd='+encodeURIComponent(cmd)).then(function(res){" +
            "o.textContent='$ '+cmd+'\\n\\n'+(res.j.output||res.j.error||'(no output)');" +
            "}).catch(function(e){o.textContent='Request failed: '+e.message;})" +
            ".then(function(){b.disabled=false;f.scrollIntoView({block:'nearest'});i.focus();});});" +
            "i.addEventListener('keydown',function(e){if(e.key==='ArrowUp'&&hi>0){hi--;i.value=hist[hi];" +
            "e.preventDefault();}else if(e.key==='ArrowDown'&&hi<hist.length-1){hi++;i.value=hist[hi];" +
            "e.preventDefault();}});" +
            "document.getElementById('clearout').addEventListener('click',function(){o.textContent='Ready.';});" +
            // keep the device/status cards fresh without touching the console output
            "setInterval(function(){fetch('/api/ping',{credentials:'same-origin'}).then(function(r){" +
            "if(r.status===401){location.reload();}}).catch(function(){});},8000);" +
            "})();";

    /* ── small helpers ───────────────────────────────────────────────────── */

    public static String uptime(long ms) {
        long s = Math.max(0, ms / 1000L);
        long d = s / 86400L;
        long h = (s % 86400L) / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (d > 0 || h > 0) sb.append(h).append("h ");
        if (d > 0 || h > 0 || m > 0) sb.append(m).append("m ");
        sb.append(sec).append("s");
        return sb.toString();
    }

    public static String ago(long ms) {
        if (ms < 1500) return "just now";
        long s = ms / 1000L;
        if (s < 60) return s + "s ago";
        long m = s / 60L;
        if (m < 60) return m + "m ago";
        long h = m / 60L;
        if (h < 24) return h + "h ago";
        return (h / 24) + "d ago";
    }

    /** "Mozilla/5.0 (Linux; Android 8.0; ...)" is useless on a TV screen. */
    public static String shortAgent(String agent) {
        if (agent == null || agent.trim().length() == 0) return "Unknown device";
        String a = agent.trim();
        String device = bracket(a);
        String platform = a.contains("Android") ? "Android"
                : a.contains("iPhone") ? "iPhone"
                : a.contains("iPad") ? "iPad"
                : a.contains("Windows") ? "Windows"
                : a.contains("Mac OS") ? "Mac"
                : a.contains("Linux") ? "Linux" : "";
        String browser = a.contains("Edg/") ? "Edge"
                : a.contains("OPR/") ? "Opera"
                : a.contains("Chrome/") ? "Chrome"
                : a.contains("Safari/") ? "Safari"
                : a.contains("Firefox/") ? "Firefox" : "";
        StringBuilder sb = new StringBuilder();
        if (device != null && device.length() > 0) sb.append(device);
        else if (platform.length() > 0) sb.append(platform);
        else sb.append("Unknown device");
        if (platform.length() > 0 && (device == null || device.indexOf(platform) < 0)) {
            sb.append(" · ").append(platform);
        }
        if (browser.length() > 0) sb.append(" · ").append(browser);
        String out = sb.toString();
        return out.length() > 90 ? out.substring(0, 88) + "…" : out;
    }

    private static String bracket(String s) {
        int open = s.indexOf('(');
        int close = s.indexOf(')', open + 1);
        if (open < 0 || close < 0) return "";
        String inner = s.substring(open + 1, close);
        String[] parts = inner.split(";");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < 3; i++) {
            String p = parts[i].trim();
            if (p.startsWith("Linux") || p.startsWith("U") || p.startsWith("K") || p.startsWith("wv"))
                continue;
            if (p.length() == 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        String out = sb.toString();
        return out.length() > 44 ? out.substring(0, 42) + "…" : out;
    }

    public static String stamp(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
