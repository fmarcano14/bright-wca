package com.fer.wcabright;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static final String WSS = "android.permission.WRITE_SECURE_SETTINGS";
    static final String K_VALUE = "Anker_ProjectorSettings_Wall_Color_Value";
    static final String K_STATUS = "Anker_ProjectorSettings_Wall_Color_Status";
    static final String K_BRIGHT = "Anker_ProjectorSettings_BrightnessValue";
    static final String K_BEFORE = "Anker_ProjectorSettings_WallColorStatusBefore3D";
    static final String K_3D = "tv_picture_advance_video_3d_mode";
    static final String[] BACKUP_KEYS = {K_VALUE, K_STATUS, K_BRIGHT, K_BEFORE, K_3D};
    static final String NULL = "\u0000null";
    static final int GOLD = Color.parseColor("#FFC107");

    static final int[][] TONES = {{1023, 1023, 1023}, {1023, 900, 780}, {900, 960, 1023}, {1023, 520, 760}};

    enum Screen { SETUP, CHOOSE, MAIN, HOMEB, CALIB, NOCAL, NOCAL2, DONE, ADV }

    final Handler h = new Handler(Looper.getMainLooper());
    SharedPreferences sp;
    LinearLayout card;
    Screen screen;
    boolean applying;
    String queued;
    int tone = 0, toneLevel = 1;
    static final double[] TONE_F = {0.70, 0.85, 0.93, 1.0};
    /** Manual tone at level L (0=Low..3=Max): base * factor, clamped 50..1023. */
    static int[] toneVal(int t, int L) {
        int[] r = new int[3];
        for (int i = 0; i < 3; i++) r[i] = Math.max(50, Math.min(1023, (int) Math.round(TONES[t][i] * TONE_F[L])));
        return r;
    }
    /** Selection is tracked by id ("A:i" or "T:t:L"), valid only while the projector still holds what we wrote. */
    String sel() { String v = norm(current()); return v.equals(norm(sp.getString("lastWritten", ""))) ? sp.getString("sel", "") : ""; }
    static String norm(String v) { int[] c = parse(v); return c == null ? (v == null ? "" : v.trim()) : fmt(c); }
    /** Level of tone t that is on the projector now (by saved id, else by value), or -1. */
    int activeT(int t) {
        String cur = norm(current());
        String[] a = sp.getString("sel", "").split(":");
        if (a.length == 3 && a[0].equals("T") && a[1].equals("" + t)) {
            int L = Integer.parseInt(a[2]); if (fmt(toneVal(t, L)).equals(cur)) return L;
        }
        for (int L = 0; L < 4; L++) if (fmt(toneVal(t, L)).equals(cur)) return L;
        return -1;
    }
    int activeA(int[] c) {
        String cur = norm(current());
        String[] a = sp.getString("sel", "").split(":");
        if (a.length == 2 && a[0].equals("A")) { int L = Integer.parseInt(a[1]); if (fmt(aLevel(c, L)).equals(cur)) return L; }
        for (int L = 0; L < 4; L++) if (fmt(aLevel(c, L)).equals(cur)) return L;
        return -1;
    }
    boolean sameAsCurrent(String rgb) { int[] c = parse(current()); return !applying && c != null && rgb.equals(fmt(c)); }
    /** Nothing to write: mark it selected and say so briefly. */
    void alreadyActive(String id) {
        sp.edit().putString("sel", id).putString("lastWritten", current()).apply();
        refreshMarks();
        final TextView f = feedback;
        if (f == null) return;
        f.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideFeedback); h.postDelayed(hideFeedback, 2000);
    }
    TextView feedback;
    final Runnable hideFeedback = () -> { if (feedback != null) feedback.setVisibility(View.INVISIBLE); };
    void addFeedback() {
        feedback = text(getString(R.string.already_active), 13, GOLD, false);
        feedback.setVisibility(View.INVISIBLE);
    }
    void applySel(String id, String rgb) {
        if (sameAsCurrent(rgb)) { alreadyActive(id); return; }
        sp.edit().putString("sel", id).apply(); apply(rgb);
    }
    int[] adv = {1023, 1023, 1023};
    String calibPending;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("bright", 0);
        Window w = getWindow();
        w.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(0.35f);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.CENTER;
        w.setAttributes(lp);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(20);
        card.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#EE12181F"));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#55FFFFFF"));
        card.setBackground(bg);
        card.setMinimumWidth(dp(520));
        setContentView(card);
    }

    @Override protected void onResume() { super.onResume(); route(); }

    boolean granted() { return checkSelfPermission(WSS) == PackageManager.PERMISSION_GRANTED; }

    void route() {
        if (!granted()) { show(Screen.SETUP); return; }
        if (OverlayService.running) stopService(new Intent(this, OverlayService.class));
        ensureBackup();
        detectCalibration();
        if (calibPending != null) { show(Screen.CALIB); return; }
        if (screen == null || screen == Screen.SETUP) {
            if (home() == Screen.HOMEB && sp.getBoolean("last_nocal2", false) && sel().startsWith("T:")) {
                syncTone(); show(sp.getBoolean("in_done", false) ? Screen.DONE : Screen.NOCAL);
            }
            else show(home());
        }
        else show(screen);
    }

    /** Home depends on the first-run choice: "A" = colors ok/brightness low, "B" = colors wrong. */
    Screen home() {
        String m = sp.getString("mode", null);
        if (m == null) return Screen.CHOOSE;
        if (m.equals("A")) return Screen.MAIN;
        return Screen.HOMEB;
    }

    // ---------- data ----------
    ContentResolver cr() { return getContentResolver(); }
    String g(String k) { try { return Settings.Global.getString(cr(), k); } catch (Exception e) { return null; } }

    void ensureBackup() {
        if (sp.getBoolean("backup_saved", false)) return;
        SharedPreferences.Editor e = sp.edit();
        for (String k : BACKUP_KEYS) { String v = g(k); e.putString("o_" + k, v == null ? NULL : v); }
        e.putBoolean("backup_saved", true).apply();
    }

    static int[] parse(String s) {
        if (s == null) return null;
        String[] a = s.trim().split(",");
        if (a.length != 3) return null;
        int[] r = new int[3];
        try { for (int i = 0; i < 3; i++) { r[i] = Integer.parseInt(a[i].trim()); if (r[i] < 0 || r[i] > 1023) return null; } }
        catch (Exception x) { return null; }
        return r;
    }
    static String fmt(int[] c) { return c[0] + "," + c[1] + "," + c[2]; }
    static boolean isCal(int[] c) { return c != null && !(c[0] == 1023 && c[1] == 1023 && c[2] == 1023); }
    int[] calib() { return parse(sp.getString("calib", null)); }
    String current() { String v = g(K_VALUE); return v == null ? "" : v.trim(); }

    /** Only right after the user opened projector settings from our calibration screen (armed, <15 min). */
    void detectCalibration() {
        if (!sp.getBoolean("armed", false)) return;
        if (System.currentTimeMillis() - sp.getLong("armed_at", 0) > 15 * 60 * 1000L) { disarm(); return; }
        int[] c = parse(current());
        if (!isCal(c)) return;
        String norm = fmt(c);
        if (norm.equals(sp.getString("pre_value", ""))) return;
        calibPending = norm;
        disarm();
    }
    void disarm() { sp.edit().putBoolean("armed", false).remove("pre_value").apply(); }
    boolean isOurs(String v) {
        int[] c = parse(sp.getString("abase", null));
        for (int L = 0; L < 4 && c != null; L++) if (fmt(aLevel(c, L)).equals(v)) return true;
        for (int t = 0; t < TONES.length; t++) for (int L = 0; L < 4; L++) if (fmt(toneVal(t, L)).equals(v)) return true;
        return false;
    }

    /** 0 = as is; 1 = scale so max channel = 1023; 2/3 = then lift each channel 25%/50% toward 1023. */
    static int[] level(int[] c, int L) {
        int[] r = c.clone();
        if (L == 0) return r;
        int mx = Math.max(1, Math.max(r[0], Math.max(r[1], r[2])));
        double k = 1023.0 / mx, lift = L == 2 ? 0.25 : L == 3 ? 0.5 : 0;
        for (int i = 0; i < 3; i++) {
            double v = c[i] * k;
            v += lift * (1023 - v);
            r[i] = Math.max(50, Math.min(1023, (int) Math.round(v)));
        }
        return r;
    }

    /** Mode A, proportions kept. 0 Low = base; 1..3 scale toward k_max = 1023/max (33%/66%/100%);
     *  if base is already maxed, lift each channel 15%/30%/50% toward 1023 instead. */
    static final double[] A_SCALE = {0, 0.33, 0.66, 1.0}, A_LIFT = {0, 0.15, 0.30, 0.50};
    static int[] aLevel(int[] c, int L) {
        int[] r = c.clone();
        if (L == 0) return r;
        double kMax = 1023.0 / Math.max(1, Math.max(c[0], Math.max(c[1], c[2])));
        for (int i = 0; i < 3; i++) {
            double v = kMax > 1.01 ? c[i] * Math.min(kMax, 1 + (kMax - 1) * A_SCALE[L]) : c[i] + A_LIFT[L] * (1023 - c[i]);
            r[i] = Math.max(0, Math.min(1023, (int) Math.round(v)));
        }
        return r;
    }
    /** Base = what is on the projector now, unless it is one of our own presets from the stored base. */
    int[] modeABase() {
        String v = current();
        int[] cur = parse(v), base = parse(sp.getString("abase", null));
        if (cur == null) return base != null ? base : new int[]{1023, 1023, 1023};
        if (base != null && v.equals(sp.getString("lastWritten", ""))) {
            String n = fmt(cur);
            for (int L = 0; L < 4; L++) if (fmt(aLevel(base, L)).equals(n)) return base;
        }
        sp.edit().putString("abase", fmt(cur)).apply();
        return cur;
    }

    static boolean worn(int[] c) { return c != null && (c[2] < 200 || c[1] > 1.6 * Math.min(c[0], c[2])); }

    // ---------- apply (same keys/order/delays as Brillo WCA) ----------
    void apply(String rgb) {
        if (applying) { queued = rgb; return; }
        applying = true;
        try {
            Settings.Global.putString(cr(), K_VALUE, rgb);
            Settings.Global.putInt(cr(), K_STATUS, 1);
            Settings.Global.putInt(cr(), K_BRIGHT, 11);
            Settings.Global.putInt(cr(), K_BEFORE, 1);
            Settings.Global.putInt(cr(), K_3D, 1);
            sp.edit().putString("lastWritten", rgb).putBoolean("noteShown", true).apply();
            h.postDelayed(() -> {
                try { Settings.Global.putInt(cr(), K_3D, 0); } catch (Exception ignored) {}
                h.postDelayed(() -> { applying = false; afterApply(); }, 1500);
            }, 1400);
        } catch (SecurityException se) { applying = false; toast(R.string.no_perm); }
        catch (Exception e) { applying = false; toast(R.string.err); }
        refreshMarks();
    }
    void afterApply() {
        if (queued != null) { String q = queued; queued = null; apply(q); return; }
        refreshMarks();
        if (screen == Screen.DONE && focusTarget != null) { final View d = focusTarget; d.post(d::requestFocus); }
    }

    void restore() {
        if (applying) return;
        applying = true;
        try {
            for (String k : new String[]{K_VALUE, K_STATUS, K_BRIGHT}) put(k, sp.getString("o_" + k, NULL));
            Settings.Global.putInt(cr(), K_BEFORE, 1);
            Settings.Global.putInt(cr(), K_3D, 1);
            h.postDelayed(() -> {
                try { Settings.Global.putInt(cr(), K_3D, 0); } catch (Exception ignored) {}
                h.postDelayed(() -> {
                    put(K_BEFORE, sp.getString("o_" + K_BEFORE, NULL));
                    put(K_3D, sp.getString("o_" + K_3D, NULL));
                    String ov = sp.getString("o_" + K_VALUE, NULL);
                    sp.edit().putString("lastWritten", ov).remove("sel").apply();
                    applying = false; toast(R.string.restored); refreshMarks();
                }, 1500);
            }, 1400);
        } catch (Exception e) { applying = false; toast(R.string.no_perm); }
    }
    void put(String k, String v) {
        try {
            String cur = g(k);
            String want = NULL.equals(v) ? null : v;
            if (want == null ? cur == null : want.equals(cur)) return;
            Settings.Global.putString(cr(), k, want);
        } catch (Exception ignored) {}
    }

    // ---------- UI ----------
    View focusTarget;
    Runnable marks = () -> {};

    void show(Screen s) {
        screen = s;
        sp.edit().putBoolean("last_nocal2", s == Screen.NOCAL || s == Screen.NOCAL2 || s == Screen.DONE).apply();
        card.removeAllViews();
        focusTarget = null; marks = () -> {}; feedback = null; h.removeCallbacks(hideFeedback);
        switch (s) {
            case SETUP: buildSetup(); break;
            case CHOOSE: buildChoose(); break;
            case MAIN: buildMain(); break;
            case HOMEB: buildHomeB(); break;
            case CALIB: buildCalib(); break;
            case NOCAL: buildNoCal(); break;
            case NOCAL2: buildNoCal2(); break;
            case DONE: buildDone(); break;
            case ADV: buildAdv(); break;
        }
        marks.run();
        final View f = focusTarget;
        if (f != null) card.post(f::requestFocus);
    }
    void refreshMarks() { marks.run(); }

    void buildSetup() {
        title(getString(R.string.app_name));
        boolean dev = Settings.Global.getInt(cr(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        if (!dev) {
            text(getString(R.string.dev_title), 18, Color.WHITE, true);
            text(getString(R.string.dev_hint), 15, Color.rgb(200, 210, 225), false);
            LinearLayout r = row();
            focusTarget = btn(r, getString(R.string.dev_btn), false, v -> openAbout());
            btn(r, getString(R.string.dev_done), false, v -> show(Screen.SETUP));
        } else if (!Settings.canDrawOverlays(this)) {
            text(getString(R.string.ov_step), 18, Color.WHITE, true);
            text(getString(R.string.ov_step_hint), 15, Color.rgb(200, 210, 225), false);
            focusTarget = btn(row(), getString(R.string.ov_step_btn), false, v -> requestOverlay());
        } else {
            text(getString(R.string.grant_title), 18, Color.WHITE, true);
            text(getString(R.string.grant_hint), 15, Color.rgb(200, 210, 225), false);
            focusTarget = btn(row(), getString(R.string.grant_btn), false, v -> startGrant());
            String last = getSharedPreferences("s", 0).getString("lastFriendly", null);
            if (last != null) text(last, 14, Color.rgb(255, 200, 120), false);
        }
    }

    void buildMain() {
        title(getString(R.string.mine));
        final int[] c = modeABase();
        LinearLayout r = row();
        int[] labels = {R.string.lv_orig, R.string.lv_normal, R.string.lv_high, R.string.lv_max};
        final Button[] bs = new Button[4];
        String cur = current();
        for (int i = 0; i < 4; i++) {
            final String v = fmt(aLevel(c, i));
            final String id = "A:" + i;
            bs[i] = btn(r, getString(labels[i]), true, x -> applySel(id, v));
            bs[i].setTag(id);
        }
        int a0 = activeA(c);
        focusTarget = bs[a0 >= 0 ? a0 : 1];
        marks = () -> { int al = activeA(c); for (int i = 0; i < 4; i++) mark(bs[i], i == al); };
        addFeedback();
        if (!sp.getBoolean("noteShown", false)) text(getString(R.string.remote_note), 13, Color.rgb(255, 200, 120), false);
        startOverLink();
    }

    void buildChoose() {
        title(getString(R.string.choose_title));
        focusTarget = big(getString(R.string.choose_a), v -> { sp.edit().putString("mode", "A").apply(); show(home()); });
        big(getString(R.string.choose_b), v -> { sp.edit().putString("mode", "B").apply(); show(home()); });
    }

    void buildHomeB() {
        title(getString(R.string.homeb_title));
        focusTarget = big(getString(R.string.calib_paper), getString(R.string.calib_paper_sub), v -> show(Screen.CALIB));
        big(getString(R.string.manual), getString(R.string.manual_sub), v -> show(Screen.NOCAL));
        startOverLink();
    }

    /** Back to the chooser; projector values are left as they are. */
    void startOver() { sp.edit().remove("mode").apply(); show(Screen.CHOOSE); }

    /** Small text-style link, alone at the panel's bottom-right; gold border only when focused. */
    void startOverLink() {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.END);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(18);
        r.setLayoutParams(rl);
        card.addView(r);
        Button b = new Button(this);
        b.setText("↺  " + getString(R.string.start_over)); b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setFocusable(true); b.setFocusableInTouchMode(true);
        b.setOnClickListener(v -> startOver());
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.TRANSPARENT); on.setStroke(dp(3), GOLD); on.setCornerRadius(dp(10));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, new GradientDrawable());
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, Color.rgb(150, 160, 175)}));
        b.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        r.addView(b);
    }

    Button big(String s, String sub, View.OnClickListener l) {
        Button b = big(s, l);
        android.text.SpannableString t = new android.text.SpannableString(s + "\n" + sub);
        int st = s.length() + 1;
        t.setSpan(new android.text.style.RelativeSizeSpan(0.72f), st, t.length(), 0);
        t.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), st, t.length(), 0);
        b.setText(t);
        return b;
    }
    Button big(String s, View.OnClickListener l) {
        Button b = btn(row(), s, false, l);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.width = dp(500); lp.height = -2;
        b.setMinHeight(dp(64)); b.setPadding(dp(20), dp(12), dp(20), dp(12));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        return b;
    }

    void buildCalib() {
        title(getString(R.string.cal_title));
        if (calibPending != null) {
            text(getString(R.string.cal_new), 17, Color.WHITE, true);
            LinearLayout r = row();
            focusTarget = btn(r, getString(R.string.use), false, v -> {
                sp.edit().putString("calib", calibPending).putString("abase", calibPending).apply();
                calibPending = null;
                show(Screen.MAIN);
            });
            btn(r, getString(R.string.back), false, v -> {
                calibPending = null;
                show(home());
            });
            if (worn(parse(calibPending)))
                text(getString(R.string.hw_warn), 13, Color.rgb(255, 200, 120), false);
            return;
        }
        text(getString(R.string.cal_1), 15, Color.rgb(220, 228, 238), false);
        text(getString(R.string.cal_2), 15, Color.rgb(220, 228, 238), false);
        text(getString(R.string.cal_3), 15, Color.rgb(220, 228, 238), false);
        text(getString(R.string.cal_4), 15, Color.rgb(220, 228, 238), false);
        LinearLayout r = row();
        focusTarget = btn(r, getString(R.string.cal_open), false, v -> openWca());
        if (home() != Screen.CALIB) btn(r, getString(R.string.back), false, v -> show(home()));
        if (home() == Screen.CALIB) startOverLink();
    }

    /** Step 1: pick tone. Step 2 (NOCAL2): pick brightness, applied live. */
    void buildNoCal() {
        title(getString(R.string.pick_tone));
        text(getString(R.string.step1), 13, Color.rgb(170, 178, 190), false);
        syncTone();
        LinearLayout r = row();
        int[] tl = {R.string.neutral, R.string.warm, R.string.cool, R.string.less_green};
        final Button[] ts = new Button[TONES.length];
        for (int i = 0; i < ts.length; i++) {
            final int k = i;
            ts[i] = btn(r, getString(tl[i]), true, v -> { tone = k; show(Screen.NOCAL2); });
            ((LinearLayout.LayoutParams) ts[i].getLayoutParams()).width = dp(130);
        }
        focusTarget = ts[tone];
        marks = () -> { String cs = sel(); for (int i = 0; i < ts.length; i++) mark(ts[i], cs.startsWith("T:" + i + ":")); };
        LinearLayout r3 = row();
        small(r3, getString(R.string.back), v -> show(home()));
    }

    void buildNoCal2() {
        title(getString(R.string.pick_bright));
        int[] tl = {R.string.neutral, R.string.warm, R.string.cool, R.string.less_green};
        text(getString(R.string.step2) + " " + getString(tl[tone]), 13, Color.rgb(170, 178, 190), false);
        LinearLayout r = row();
        int[] ll = {R.string.lv_orig, R.string.lv_normal, R.string.lv_high, R.string.lv_max};
        final Button[] ls = new Button[4];
        for (int i = 0; i < 4; i++) {
            final int k = i;
            ls[i] = btn(r, getString(ll[i]), true, v -> { toneLevel = k; String rgb = fmt(toneVal(tone, k)); if (sameAsCurrent(rgb)) { alreadyActive("T:" + tone + ":" + k); return; } sp.edit().putBoolean("in_done", true).putString("sel", "T:" + tone + ":" + k).apply(); show(Screen.DONE); apply(fmt(toneVal(tone, k))); });
        }
        int a0 = activeT(tone);
        focusTarget = ls[a0 >= 0 ? a0 : 1];
        marks = () -> { int al = activeT(tone); for (int i = 0; i < 4; i++) mark(ls[i], i == al); };
        addFeedback();
        LinearLayout r3 = row();
        small(r3, getString(R.string.back), v -> show(Screen.NOCAL));
    }

    /** Confirmation; shown immediately on level press, buttons inert until the apply/refresh finishes. */
    void buildDone() {
        title(getString(R.string.bright_applied));
        int[] tl = {R.string.neutral, R.string.warm, R.string.cool, R.string.less_green};
        int[] ll = {R.string.lv_orig, R.string.lv_normal, R.string.lv_high, R.string.lv_max};
        final String info = getString(tl[tone]) + " · " + getString(ll[toneLevel]);
        final TextView sub = text(info, 14, Color.rgb(170, 178, 190), false);
        LinearLayout r = row();
        final Button done = btn(r, getString(R.string.done), false, v -> { if (!applying) finishDone(); });
        final Button chg = btn(r, getString(R.string.change_tone), false, v -> {
            if (applying) return;
            sp.edit().putBoolean("in_done", false).apply(); show(Screen.NOCAL);
        });
        for (Button x : new Button[]{done, chg}) ((LinearLayout.LayoutParams) x.getLayoutParams()).width = dp(200);
        focusTarget = done;
        marks = () -> {
            sub.setText(applying ? getString(R.string.applying) : info);
            done.setAlpha(applying ? 0.6f : 1f); chg.setAlpha(applying ? 0.6f : 1f);
        };
    }
    void finishDone() { sp.edit().putBoolean("in_done", false).apply(); finish(); }

    void syncTone() {
        String[] a = sel().split(":");
        if (a.length == 3 && a[0].equals("T")) { tone = Integer.parseInt(a[1]); toneLevel = Integer.parseInt(a[2]); }
    }

    void buildAdv() {
        title(getString(R.string.advanced));
        int[] c = parse(current());
        if (c != null) adv = c;
        final String[] n = {"R", "G", "B"};
        final Button[] st = new Button[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Button b = btn(row(), "", false, null);
            st[i] = b;
            b.setOnKeyListener((v, code, ev) -> {
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                    adv[k] = Math.max(0, Math.min(1023, adv[k] + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 25 : -25)));
                    b.setText("◀  " + n[k] + "  " + adv[k] + "  ▶");
                    h.removeCallbacks(live); h.postDelayed(live, 600);
                }
                return true;
            });
            b.setText("◀  " + n[k] + "  " + adv[k] + "  ▶");
        }
        focusTarget = st[0];
        text(getString(R.string.adv_hint), 13, Color.rgb(170, 178, 190), false);
        LinearLayout r = row();
        small(r, getString(R.string.maximize), v -> {
            adv = level(adv, 1);
            for (int i = 0; i < 3; i++) st[i].setText("◀  " + n[i] + "  " + adv[i] + "  ▶");
            apply(fmt(adv));
        });
        small(r, getString(R.string.back), v -> show(home()));
        small(r, getString(R.string.restore), v -> restore());
    }
    final Runnable live = () -> apply(fmt(adv));

    // ---------- intents ----------
    void openAbout() {
        try { startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
    }

    void requestOverlay() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (Exception ignored) {} }
    }

    void startGrant() {
        if (!Settings.canDrawOverlays(this)) { show(Screen.SETUP); return; }
        getSharedPreferences("s", 0).edit().remove("lastFriendly").apply();
        startForegroundService(new Intent(this, OverlayService.class));
        OverlayService.openWirelessDebugging(this);
    }

    static final String TV_SETTINGS = "com.android.tv.settings";
    static final String[] BAD = {"dolby", "debug", "test", "factory", "engineer"};

    /** Only TV Settings main screen (WCA is an overlay inside it), else system settings. No activity search. */
    void openWca() {
        sp.edit().putBoolean("paper", true).putBoolean("armed", true)
                .putLong("armed_at", System.currentTimeMillis()).putString("pre_value", current()).apply();
        try {
            Intent si = new Intent("android.settings.SLICE_SETTINGS")
                    .setComponent(new ComponentName(TV_SETTINGS, TV_SETTINGS + ".slice.SliceActivity"))
                    .putExtra("slice_uri", "content://com.zhixin.projector.settings.advanced.provider/advanced_settings");
            if (safe(si)) { startActivity(si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
        } catch (Exception ignored) {}
        try {
            Intent pi = new Intent("android.anker.projectorsettings.action.PROJECTOR");
            ComponentName rc = pi.resolveActivity(getPackageManager());
            if (rc != null) {
                pi.setComponent(rc);
                if (safe(pi)) { startActivity(pi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            }
        } catch (Exception ignored) {}
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(TV_SETTINGS, TV_SETTINGS + ".MainSettings"));
            if (safe(i)) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
        } catch (Exception ignored) {}
        try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    static boolean safe(Intent i) {
        ComponentName cn = i.getComponent();
        String n = cn == null ? "" : cn.getClassName().toLowerCase();
        for (String b : BAD) if (n.contains(b)) return false;
        return true;
    }

    // ---------- widgets ----------
    void title(String s) { text(s, 20, Color.WHITE, true); }
    TextView text(String s, int sp_, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp_);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMaxWidth(dp(640));
        t.setPadding(0, dp(4), 0, dp(4));
        card.addView(t);
        return t;
    }
    LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        r.setLayoutParams(lp);
        card.addView(r);
        return r;
    }
    Button btn(LinearLayout r, String s, boolean preset, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setFocusable(true); b.setFocusableInTouchMode(true);
        if (l != null) b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(preset ? dp(112) : -2, dp(54));
        lp.setMargins(dp(5), 0, dp(5), 0);
        b.setLayoutParams(lp);
        b.setPadding(dp(16), 0, dp(16), 0);
        mark(b, false);
        r.addView(b);
        return b;
    }
    void small(LinearLayout r, String s, View.OnClickListener l) {
        Button b = btn(r, s, false, l);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ((LinearLayout.LayoutParams) b.getLayoutParams()).height = dp(42);
    }
    /** Focus = gold 4dp border; active (not focused) = thin gold border + gold text. */
    void mark(Button b, boolean active) {
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.rgb(30, 90, 168)); on.setStroke(dp(4), GOLD); on.setCornerRadius(dp(12));
        GradientDrawable off = new GradientDrawable();
        off.setColor(active ? Color.rgb(46, 52, 34) : Color.rgb(42, 53, 68));
        off.setStroke(dp(active ? 2 : 1), active ? GOLD : Color.rgb(68, 85, 102)); off.setCornerRadius(dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{android.R.attr.state_pressed}, on);
        sl.addState(new int[]{}, off);
        b.setBackground(sl);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Color.WHITE, active ? GOLD : Color.rgb(200, 208, 216)}));
        b.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }
    /** Used by the pairing overlay. */
    static Button style(Button b) {
        float d = b.getResources().getDisplayMetrics().density;
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.rgb(30, 90, 168)); on.setStroke((int) (4 * d), GOLD); on.setCornerRadius(10 * d);
        GradientDrawable off = new GradientDrawable();
        off.setColor(Color.rgb(42, 53, 68)); off.setStroke((int) d, Color.rgb(68, 85, 102)); off.setCornerRadius(10 * d);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, off);
        b.setBackground(sl); b.setAllCaps(false); b.setFocusable(true);
        b.setTextColor(Color.WHITE);
        return b;
    }
    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    void toast(int r) { Toast.makeText(this, r, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (screen == Screen.DONE) { if (!applying) finishDone(); return; }
        if (screen == Screen.NOCAL2) { show(Screen.NOCAL); return; }
        calibPending = null;
        Screen hm = granted() ? home() : null;
        if (hm != null && screen != hm && screen != Screen.CHOOSE && screen != Screen.SETUP) show(hm);
        else finish();
    }
}
