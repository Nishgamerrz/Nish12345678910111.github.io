package com.nishgamerrz.nishgamerr;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * The TV / phone control panel.
 *
 * Deliberately tiny: a big Running/Stopped status, the address other devices
 * should open, the password (editable, stored on this device), the port, the
 * start-on-boot switch, and the list of devices that have connected.
 *
 * All views are built in code so there is no layout XML to get wrong on a TV.
 */
public final class MainActivity extends Activity {

    private static final int BG = 0xFF0B0910;
    private static final int CARD = 0xFF151221;
    private static final int TEXT = 0xFFEDEAF5;
    private static final int MUTED = 0xFF9A93AE;
    private static final int ACCENT = 0xFF8B5CF6;
    private static final int CYAN = 0xFF22D3EE;
    private static final int OK = 0xFF34D399;
    private static final int BAD = 0xFFF87171;
    private static final int WARN = 0xFFFBBF24;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 2000L);
        }
    };

    private TextView statusText;
    private TextView urlText;
    private TextView devicesText;
    private TextView errorText;
    private TextView uptimeText;
    private EditText passwordField;
    private EditText portField;
    private Button startButton;
    private Button stopButton;
    private Switch bootSwitch;
    private TextView shellText;
    private TextView updateText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // /sdcard/Nishgamerr holds the surviving password: ask for it up front,
        // then adopt whatever a previous install left behind.
        askForStorage();
        Prefs.restoreFromVault(this);
        setContentView(buildUi());
        // Opening the app is the "install" step: shell + host come up on their own.
        HostState.provision(this);
        startHost();
        passwordField.setText(Prefs.password(this));
        portField.setText(String.valueOf(Prefs.port(this)));
        bootSwitch.setChecked(Prefs.startOnBoot(this));
        Updater.maybeCheck(this, false);
    }

    /** Android 6+ requires the grant; Android 8 TV grants it from a dialog. */
    private void askForStorage() {
        try {
            if (Build.VERSION.SDK_INT < 23) return;
            if (checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        "android.permission.WRITE_EXTERNAL_STORAGE",
                        "android.permission.READ_EXTERNAL_STORAGE"}, 1);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (Prefs.restoreFromVault(this)) passwordField.setText(Prefs.password(this));
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    /* ── UI ──────────────────────────────────────────────────────────────── */

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(24), dp(28), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        root.addView(header);

        TextView title = text("Nishgamerr", 28, TEXT, Typeface.BOLD);
        header.addView(title);
        TextView sub = text("Termux-style console host for this device", 14, MUTED, Typeface.NORMAL);
        sub.setPadding(0, dp(2), 0, dp(14));
        header.addView(sub);

        // status card
        LinearLayout statusCard = card(root);
        statusText = text("Starting…", 46, OK, Typeface.BOLD);
        statusCard.addView(statusText);
        uptimeText = text("", 14, MUTED, Typeface.NORMAL);
        uptimeText.setPadding(0, dp(4), 0, dp(10));
        statusCard.addView(uptimeText);

        TextView urlLabel = text("Open this on any device on the same Wi-Fi", 13, MUTED, Typeface.NORMAL);
        statusCard.addView(urlLabel);
        urlText = text("", 24, CYAN, Typeface.BOLD);
        urlText.setTypeface(Typeface.MONOSPACE);
        urlText.setTextIsSelectable(true);
        urlText.setPadding(0, dp(4), 0, dp(12));
        statusCard.addView(urlText);

        LinearLayout urlRow = row(statusCard);
        urlRow.addView(button("Copy address", false, new View.OnClickListener() {
            public void onClick(View v) {
                copy(urlText.getText().toString());
            }
        }));
        urlRow.addView(button("Open in browser", false, new View.OnClickListener() {
            public void onClick(View v) {
                openBrowser(urlText.getText().toString());
            }
        }));

        errorText = text("", 14, WARN, Typeface.NORMAL);
        errorText.setPadding(0, dp(10), 0, 0);
        statusCard.addView(errorText);

        // controls
        LinearLayout controls = card(root);
        controls.addView(sectionTitle("Host"));

        LinearLayout buttons = row(controls);
        startButton = button("Start host", true, new View.OnClickListener() {
            public void onClick(View v) {
                startHost();
                refresh();
            }
        });
        buttons.addView(startButton);
        stopButton = button("Stop host", false, new View.OnClickListener() {
            public void onClick(View v) {
                stopHost();
                refresh();
            }
        });
        buttons.addView(stopButton);

        bootSwitch = new Switch(this);
        bootSwitch.setText("Start automatically when the TV powers on");
        bootSwitch.setTextColor(TEXT);
        bootSwitch.setTextSize(16);
        bootSwitch.setPadding(0, dp(14), 0, 0);
        bootSwitch.setShowText(true);
        bootSwitch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Prefs.setStartOnBoot(MainActivity.this, bootSwitch.isChecked());
                toast(bootSwitch.isChecked()
                        ? "The host will start by itself after a reboot"
                        : "The host will not start on its own");
            }
        });
        controls.addView(bootSwitch);

        // password
        LinearLayout passwordCard = card(root);
        passwordCard.addView(sectionTitle("Password"));
        passwordCard.addView(text("Every device must type this to open the dashboard. " +
                "It is saved on this TV.", 13.5f, MUTED, Typeface.NORMAL));

        passwordField = new EditText(this);
        passwordField.setTextColor(TEXT);
        passwordField.setTextSize(22);
        passwordField.setTypeface(Typeface.MONOSPACE);
        passwordField.setSingleLine(true);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = dp(10);
        passwordField.setLayoutParams(fieldLp);
        passwordCard.addView(passwordField);

        LinearLayout pwRow = row(passwordCard);
        pwRow.addView(button("Save password", true, new View.OnClickListener() {
            public void onClick(View v) {
                savePassword(passwordField.getText().toString().trim());
            }
        }));
        pwRow.addView(button("New random", false, new View.OnClickListener() {
            public void onClick(View v) {
                String fresh = Auth.randomPassword(6);
                passwordField.setText(fresh);
                savePassword(fresh);
            }
        }));

        // port
        LinearLayout portCard = card(root);
        portCard.addView(sectionTitle("Port"));
        portCard.addView(text("8080 by default. Change it only if another app already uses it.",
                13.5f, MUTED, Typeface.NORMAL));
        portField = new EditText(this);
        portField.setTextColor(TEXT);
        portField.setTextSize(22);
        portField.setTypeface(Typeface.MONOSPACE);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        portField.setSingleLine(true);
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(dp(160),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        portLp.topMargin = dp(10);
        portField.setLayoutParams(portLp);
        portCard.addView(portField);
        LinearLayout portRow = row(portCard);
        portRow.addView(button("Save and restart", true, new View.OnClickListener() {
            public void onClick(View v) {
                savePort(portField.getText().toString().trim());
            }
        }));

        // shell (bundled busybox = the termux-style userland)
        LinearLayout shellCard = card(root);
        shellCard.addView(sectionTitle("Shell"));
        shellText = text("Preparing…", 14, TEXT, Typeface.NORMAL);
        shellText.setTypeface(Typeface.MONOSPACE);
        shellText.setPadding(0, dp(6), 0, dp(10));
        shellCard.addView(shellText);
        LinearLayout shellRow = row(shellCard);
        shellRow.addView(button("Reinstall shell", false, new View.OnClickListener() {
            public void onClick(View v) {
                reinstallShell();
            }
        }));

        // updates: the only place the TV says out loud what the updater is doing
        LinearLayout updateCard = card(root);
        updateCard.addView(sectionTitle("App updates"));
        updateText = text("Checking the main branch…", 14, TEXT, Typeface.NORMAL);
        updateText.setTypeface(Typeface.MONOSPACE);
        updateText.setPadding(0, dp(6), 0, dp(10));
        updateCard.addView(updateText);
        LinearLayout updateRow = row(updateCard);
        updateRow.addView(button("Check now", false, new View.OnClickListener() {
            public void onClick(View v) {
                Updater.check(MainActivity.this, true);
                refresh();
            }
        }));
        updateRow.addView(button("Install update", true, new View.OnClickListener() {
            public void onClick(View v) {
                if (Updater.install(MainActivity.this)) refresh();
                else toast("No downloaded update yet.");
            }
        }));

        // devices
        LinearLayout deviceCard = card(root);
        deviceCard.addView(sectionTitle("Devices that logged in"));
        devicesText = text("Nothing yet.", 15, TEXT, Typeface.NORMAL);
        devicesText.setTypeface(Typeface.MONOSPACE);
        devicesText.setPadding(0, dp(6), 0, dp(10));
        deviceCard.addView(devicesText);
        LinearLayout devRow = row(deviceCard);
        devRow.addView(button("Clear list", false, new View.OnClickListener() {
            public void onClick(View v) {
                HostState.clearDevices();
                refresh();
            }
        }));

        // footer
        TextView footer = text("Nishgamerr " + HostState.VERSION
                + " · serves itself from this device · nothing leaves your Wi-Fi",
                12, 0xFF6E6785, Typeface.NORMAL);
        footer.setPadding(0, dp(18), 0, 0);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(footer);

        return scroll;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(CARD);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(box, lp);
        return box;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(12), 0, 0);
        parent.addView(r);
        return r;
    }

    private TextView sectionTitle(String label) {
        TextView t = text(label.toUpperCase(), 12.5f, MUTED, Typeface.BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        return t;
    }

    private Button button(String label, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setBackgroundColor(primary ? ACCENT : 0xFF1B1730);
        b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setOnClickListener(listener);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    /* ── actions ─────────────────────────────────────────────────────────── */

    private void startHost() {
        try {
            Intent service = new Intent(this, HostService.class);
            service.setAction(HostService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception e) {
            HostState.start(this);
        }
        refresh();
    }

    private void stopHost() {
        try {
            Intent service = new Intent(this, HostService.class);
            service.setAction(HostService.ACTION_STOP);
            startService(service);
        } catch (Exception ignored) {
        }
        HostState.stop();
        refresh();
    }

    private void savePassword(String password) {
        if (password == null || password.trim().length() < 4) {
            toast("Use at least 4 characters");
            return;
        }
        Prefs.setPassword(this, password.trim());
        WebServer running = HostState.server();
        if (running != null) {
            // keep the server up, swap the verifier and log everyone out
            running.setAuth(Prefs.auth(this));
        }
        refresh();
        toast("Password saved — devices must sign in again");
    }

    private void savePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (Exception e) {
            toast("That is not a number");
            return;
        }
        if (port < Prefs.MIN_PORT || port > Prefs.MAX_PORT) {
            toast("Use a port between " + Prefs.MIN_PORT + " and " + Prefs.MAX_PORT);
            return;
        }
        Prefs.setPort(this, port);
        HostState.stop();
        startHost();
        toast("Now serving on port " + port);
    }

    private void reinstallShell() {
        try {
            java.io.File usr = new java.io.File(getFilesDir(), "shell/usr");
            deleteTree(usr);
            deleteTree(new java.io.File(getFilesDir(), "termux/usr"));
            deleteTree(new java.io.File(getFilesDir(), "termux/" + TermuxRuntime.READY_MARKER));
            HostState.consoles(this).shutdownAll();
        } catch (Exception ignored) {
        }
        HostState.provision(this);
        toast("Reinstalling the shell…");
        refresh();
    }

    private static void deleteTree(java.io.File file) {
        if (file == null || !file.exists()) return;
        java.io.File[] kids = file.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) deleteTree(kids[i]);
        }
        file.delete();
    }

    private void copy(String value) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Dashboard URL", value));
            toast("Address copied");
        } catch (Exception e) {
            toast("Could not copy");
        }
    }

    private void openBrowser(String value) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(value));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("No browser on this device");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /* ── refresh loop ────────────────────────────────────────────────────── */

    private void refresh() {
        boolean running = HostState.isRunning();
        String error = HostState.lastError();

        statusText.setText(running ? "Running" : "Stopped");
        statusText.setTextColor(running ? OK : BAD);

        urlText.setText(running ? HostState.url(this) : "—");

        if (running) {
            long up = HostState.server() != null ? HostState.server().uptimeMs() : 0;
            int sessions = HostState.server() != null ? HostState.server().sessionCount() : 0;
            uptimeText.setText("Up " + Pages.uptime(up) + " · port " + Prefs.port(this)
                    + " · " + sessions + (sessions == 1 ? " device signed in" : " devices signed in"));
        } else {
            uptimeText.setText("The dashboard is not being served right now.");
        }

        if (error != null && error.length() > 0) {
            errorText.setText(error + "\nTry another port below if the port is already taken.");
            errorText.setVisibility(View.VISIBLE);
        } else {
            errorText.setVisibility(View.GONE);
        }

        List<DeviceLog.Entry> list = HostState.devices().snapshot();
        if (list.isEmpty()) {
            devicesText.setText("Nothing yet.");
        } else {
            StringBuilder sb = new StringBuilder();
            long now = System.currentTimeMillis();
            for (int i = 0; i < list.size(); i++) {
                DeviceLog.Entry e = list.get(i);
                sb.append(e.ip).append("  ·  ").append(Pages.shortAgent(e.agent));
                sb.append("\n      ").append(Pages.ago(now - e.lastSeen))
                  .append("  ·  ").append(e.hits).append(" requests");
                if (i < list.size() - 1) sb.append("\n\n");
            }
            devicesText.setText(sb.toString());
        }

        String updateState = Updater.state();
        String latest = Updater.latest();
        if (Updater.DOWNLOADING.equals(updateState)) {
            updateText.setText("Downloading update " + Updater.percent() + "%"
                    + (latest.length() > 0 ? " (v" + latest + ")" : "") + "…");
            updateText.setTextColor(WARN);
        } else if (Updater.READY.equals(updateState)) {
            updateText.setText("Update v" + latest + " is ready\nPress \"Install update\" to finish.");
            updateText.setTextColor(OK);
        } else if (Updater.INSTALLING.equals(updateState)) {
            updateText.setText("Installer opened — confirm the update on screen.");
            updateText.setTextColor(WARN);
        } else if (Updater.FAILED.equals(updateState)) {
            updateText.setText("Update failed: " + Updater.error());
            updateText.setTextColor(BAD);
        } else if (Updater.UPTODATE.equals(updateState)) {
            updateText.setText("Up to date · v" + Prefs.versionName(this));
            updateText.setTextColor(TEXT);
        } else if (Updater.CHECKING.equals(updateState)) {
            updateText.setText("Checking for updates…");
            updateText.setTextColor(MUTED);
        } else {
            updateText.setText("Updates itself from the main branch\n"
                    + "in the background, and says so here.");
            updateText.setTextColor(TEXT);
        }

        String shell = HostState.shellState();
        int pct = HostState.provisionPercent();
        if (pct > 0 && pct < 100) {
            shellText.setText("Installing packages… " + pct + "% · " + HostState.provisionStep());
            shellText.setTextColor(WARN);
        } else if (shell != null && shell.startsWith("install failed")) {
            shellText.setText(shell);
            shellText.setTextColor(BAD);
        } else if (HostState.usingTermux(this)) {
            int n = HostState.consoles(this).size();
            shellText.setText("Termux " + HostState.runtime(this).termux().state().replace("Termux ", "")
                    + "\n" + n + (n == 1 ? " console" : " consoles")
                    + " · real bash, coreutils, pkg install <name>");
            shellText.setTextColor(OK);
        } else {
            int n = HostState.consoles(this).size();
            shellText.setText((shell == null ? "busybox" : shell) + "\n"
                    + n + (n == 1 ? " console" : " consoles")
                    + " · ash · waiting for the Termux userland");
            shellText.setTextColor(TEXT);
        }

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }
}
