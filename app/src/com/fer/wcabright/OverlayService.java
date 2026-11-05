package com.fer.wcabright;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import io.github.muntashirakon.adb.AdbStream;
import java.io.InputStream;
import java.net.*;
import java.util.*;

public class OverlayService extends Service {
    static final String PAIR = "_adb-tls-pairing._tcp", CONN = "_adb-tls-connect._tcp";
    WindowManager wm; WindowManager.LayoutParams lp; LinearLayout root;
    EditText portF, codeF, connF; TextView st; Button pairB, manualB; boolean submitted;
    final Handler h = new Handler(Looper.getMainLooper());
    NsdManager nsd; final List<NsdManager.DiscoveryListener> dls = new ArrayList<>();
    final ArrayDeque<NsdServiceInfo> resolveQ = new ArrayDeque<>(); boolean resolving;
    volatile int pairPort = -1, connPort = -1; boolean focusable, busy, alsoWca;
    final Set<String> localIps = new HashSet<>();
    final StringBuilder log = new StringBuilder();

    static volatile boolean running;
    android.content.BroadcastReceiver homeRx; final Runnable lostClose = this::stopSelf;
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null && "close".equals(i.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (i != null) alsoWca = i.getBooleanExtra("alsoWca", false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("o", "Bright WCA", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "o").setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Bright WCA").setContentText(getString(R.string.ov_notif))
                .addAction(new Notification.Action.Builder(null, getString(R.string.close), PendingIntent.getService(this, 0,
                        new Intent(this, OverlayService.class).setAction("close"), PendingIntent.FLAG_IMMUTABLE)).build())
                .build(),
                Build.VERSION.SDK_INT >= 34 ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
        running = true;
        if (root == null) {
            showOverlay(); startNsd();
            h.postDelayed(this::stopSelf, 5 * 60 * 1000);
            homeRx = new android.content.BroadcastReceiver() {
                public void onReceive(Context c, Intent x) {
                    String why = x.getStringExtra("reason");
                    if (why != null && (why.contains("home") || why.contains("recent"))) stopSelf();
                }
            };
            try { registerReceiver(homeRx, new android.content.IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), Context.RECEIVER_EXPORTED); }
            catch (Exception ignored) { homeRx = null; }
        }
        return START_NOT_STICKY;
    }

    static void openWirelessDebugging(Context c) {
        try { c.startActivity(new Intent("com.android.settings.WIRELESS_DEBUGGING_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Exception ignored) {}
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception e) {
            try { c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) {}
        }
    }

    EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.GRAY); e.setTextSize(15);
        e.setInputType(InputType.TYPE_CLASS_NUMBER); e.setSingleLine(true);
        root.addView(e); return e;
    }

    void showOverlay() {
        wm = getSystemService(WindowManager.class);
        root = new LinearLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                if (ev.getKeyCode() == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP && focusable) {
                    stopSelf();
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14, 8, 14, 8);
        root.setBackgroundColor(Color.argb(235, 20, 40, 70));
        TextView t = new TextView(this); t.setText(getString(R.string.ov_title)); t.setTextColor(Color.WHITE); t.setTextSize(15);
        root.addView(t);
        st = new TextView(this); st.setTextColor(Color.rgb(255, 230, 140)); st.setTextSize(14);
        st.setText(getString(R.string.ov_searching));
        root.addView(st);
        portF = field(getString(R.string.ov_port)); portF.setVisibility(View.GONE);
        codeF = field(getString(R.string.ov_code)); codeF.setText(""); codeF.setVisibility(View.GONE);
        codeF.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(6) });
        codeF.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(android.text.Editable e) {
                if (e.length() == 6 && !busy && parse(portF) > 0 && !submitted) { submitted = true; doPair(true); }
                if (e.length() < 6) submitted = false;
            }
        });
        connF = new EditText(this); // hidden; filled by NSD
        pairB = new Button(this); pairB.setText(getString(R.string.ov_pair)); pairB.setVisibility(View.GONE);
        pairB.setOnClickListener(v -> doPair(true)); MainActivity.style(pairB); root.addView(pairB);
        manualB = new Button(this); manualB.setText(getString(R.string.ov_manual)); manualB.setTextSize(12);
        manualB.setVisibility(View.GONE); MainActivity.style(manualB); root.addView(manualB);
        manualB.setOnClickListener(v -> {
            manualB.setVisibility(View.GONE); portF.setVisibility(View.VISIBLE);
            codeF.setVisibility(View.VISIBLE); pairB.setVisibility(View.VISIBLE);
            st.setText(getString(R.string.ov_enter_port));
            portF.requestFocus(); getSystemService(InputMethodManager.class).showSoftInput(portF, 0);
        });
        Button x = new Button(this); x.setText(getString(R.string.close));
        x.setOnClickListener(v -> stopSelf()); MainActivity.style(x); root.addView(x);
        lp = new WindowManager.LayoutParams(380, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START; lp.x = 16; lp.y = 16;
        wm.addView(root, lp);
    }

    void setFocusableWin(boolean on, String msg) {
        focusable = on;
        if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
        if (msg != null) st.setText(msg);
        if (on) {
            if (codeF.getText().toString().equals(portF.getText().toString())) codeF.setText("");
            if (codeF.getVisibility() == View.VISIBLE) {
                EditText target = portF.getVisibility() == View.VISIBLE && portF.getText().length() == 0 ? portF : codeF;
                target.requestFocus();
                h.postDelayed(() -> getSystemService(InputMethodManager.class).showSoftInput(target, 0), 300);
            } else if (manualB.getVisibility() == View.VISIBLE) manualB.requestFocus();
        }
    }

    // ---------- NSD ----------
    void startNsd() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements(); )
                for (Enumeration<InetAddress> a = e.nextElement().getInetAddresses(); a.hasMoreElements(); )
                    localIps.add(a.nextElement().getHostAddress());
        } catch (Exception ignored) {}
        nsd = getSystemService(NsdManager.class);
        discover(PAIR); discover(CONN);
        h.postDelayed(() -> { if (pairPort < 0 && root != null) manualB.setVisibility(View.VISIBLE); }, 60000);
        h.postDelayed(() -> { if (!focusable && pairPort < 0 && root != null)
            setFocusableWin(true, getString(R.string.ov_notfound)); }, 60000);
    }

    void discover(String type) {
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String s, int e) { addLog("NSD " + s + " error " + e); }
            public void onStopDiscoveryFailed(String s, int e) {}
            public void onDiscoveryStarted(String s) { addLog("NSD buscando " + s); }
            public void onDiscoveryStopped(String s) {}
            public void onServiceFound(NsdServiceInfo i) { h.post(() -> { resolveQ.add(i); nextResolve(); }); }
            public void onServiceLost(NsdServiceInfo i) {
                if (i.getServiceType().contains("pairing")) h.post(() -> {
                    addLog("Servicio de vinculación cerrado");
                    if (!busy) { h.removeCallbacks(lostClose); h.postDelayed(lostClose, 1000); }
                });
            }
        };
        dls.add(dl);
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, dl); }
        catch (Exception e) { addLog("NSD no disponible: " + e); }
    }

    void nextResolve() {
        if (resolving || resolveQ.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo si = resolveQ.poll();
        nsd.resolveService(si, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo s, int e) {
                h.post(() -> { resolving = false; addLog("NSD resolve error " + e); nextResolve(); });
            }
            public void onServiceResolved(NsdServiceInfo s) {
                h.post(() -> { resolving = false; onResolved(s); nextResolve(); });
            }
        });
    }

    void onResolved(NsdServiceInfo s) {
        String ip = s.getHost() == null ? "?" : s.getHost().getHostAddress();
        boolean local = localIps.isEmpty() || localIps.contains(ip);
        boolean pair = s.getServiceType().contains("pairing");
        addLog("NSD " + (pair ? "vinculación" : "conexión") + " " + ip + ":" + s.getPort() + (local ? " (este equipo)" : " (otro)"));
        if (!local) return;
        if (pair) {
            h.removeCallbacks(lostClose);
            pairPort = s.getPort(); portF.setText(String.valueOf(pairPort));
            manualB.setVisibility(View.GONE); portF.setVisibility(View.GONE);
            codeF.setVisibility(View.VISIBLE); pairB.setVisibility(View.VISIBLE);
            setFocusableWin(true, getString(R.string.ov_enter_code));
        } else {
            connPort = s.getPort(); connF.setText(String.valueOf(connPort));
        }
    }

    void addLog(String s) {
        log.append(s).append('\n');
        android.util.Log.i("BrightWCA", s);
    }

    // ---------- ADB ----------
    void doPair(boolean pairFirst) {
        if (busy) return;
        final String code = codeF.getText().toString().trim();
        final int pp = parse(portF), cp0 = parse(connF);
        if (pairFirst && (pp <= 0 || code.length() != 6)) { st.setText(getString(R.string.ov_missing)); return; }
        h.removeCallbacks(lostClose); busy = true; st.setText(getString(R.string.ov_working));
        new Thread(() -> {
            String res;
            try {
                AdbMgr m = AdbMgr.get(this);
                if (pairFirst) {
                    boolean ok = m.pair("127.0.0.1", pp, code);
                    if (!ok) throw new PairFail();
                    ui(getString(R.string.ov_working));
                }
                int cp = cp0;
                for (int k = 0; k < 20 && cp <= 0; k++) { Thread.sleep(500); cp = connPort; }
                boolean conn;
                if (cp > 0) conn = m.connect("127.0.0.1", cp);
                else conn = m.autoConnect(this, 10000);
                if (!conn) throw new ConnFail();
                ui(getString(R.string.ov_working));
                StringBuilder out = new StringBuilder();
                out.append(shell(m, "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
                try { m.disconnect(); } catch (Exception ignored) {}
                boolean g = checkSelfPermission(MainActivity.WSS) == PackageManager.PERMISSION_GRANTED;
                res = g ? "OK"
                        : getString(R.string.ov_retry);
            } catch (Throwable e) {
                res = (e instanceof PairFail || pairFirst && !(e instanceof ConnFail))
                        ? getString(R.string.ov_badcode)
                        : getString(R.string.ov_retry);
                addLog("ERROR " + e);
                android.util.Log.e("BrightWCA", "fail", e);
            }
            final String r = res;
            final boolean okR = r.equals("OK");
            getSharedPreferences("s", 0).edit().putString("last", r).putString("lastFriendly", okR ? null : r).apply();
            h.post(() -> {
                busy = false;
                if (okR) {
                    st.setText(getString(R.string.ov_done));
                    setFocusableWin(false, null);
                    h.postDelayed(() -> {
                        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        stopSelf();
                    }, 1200);
                } else st.setText(r);
            });
        }).start();
    }

    String tail() { String s = log.toString(); return s.length() > 600 ? s.substring(s.length() - 600) : s; }
    void ui(String s) { h.post(() -> st.setText(s)); }
    static int parse(EditText e) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return -1; } }

    static String shell(AdbMgr m, String cmd) throws Exception {
        AdbStream s = m.openStream("shell:" + cmd);
        StringBuilder sb = new StringBuilder();
        try (InputStream in = s.openInputStream()) {
            byte[] b = new byte[1024]; int r;
            while ((r = in.read(b)) > 0) sb.append(new String(b, 0, r));
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    static class PairFail extends Exception {}
    static class ConnFail extends Exception {}

    @Override public void onDestroy() {
        running = false;
        if (homeRx != null) try { unregisterReceiver(homeRx); } catch (Exception ignored) {}
        if (nsd != null) for (NsdManager.DiscoveryListener d : dls) try { nsd.stopServiceDiscovery(d); } catch (Exception ignored) {}
        if (root != null) try { wm.removeView(root); } catch (Exception ignored) {}
        root = null; h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
