package dev.tvambient;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** D-pad friendly settings screen: Up/Down to move, Left/Right to change a value, OK on an action row. */
public final class MainActivity extends Activity {
    private SharedPreferences sp;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.sp(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E1216);
        root.setPadding(dp(48), dp(24), dp(48), dp(12));

        TextView title = text("TV Ambient", 30, 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        status = text("", 15, 0xFF8FA3B5);
        status.setPadding(0, dp(4), 0, dp(12));
        root.addView(status);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(action("▶  Start", this::start));
        list.addView(action("■  Stop", this::stop));
        list.addView(action("Layout test (top red, right green, bottom blue, left white, LED 0 yellow)", this::test));
        for (Prefs.Def d : Prefs.ALL) list.addView(settingRow(d));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        list.getChildAt(0).requestFocus();
        if (b == null) applyExtras(getIntent());
    }

    /** For testing over ADB: am start -n dev.tvambient/.MainActivity --ei method 1 --ei rate 2 */
    private void applyExtras(Intent i) {
        if (i == null || i.getExtras() == null) return;
        SharedPreferences.Editor e = sp.edit();
        boolean any = false;
        for (Prefs.Def d : Prefs.ALL) {
            if (i.hasExtra(d.key)) {
                e.putInt(d.key, Math.max(d.min, Math.min(d.max, i.getIntExtra(d.key, d.def))));
                any = true;
            }
        }
        if (any) { e.apply(); recreate(); }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); applyExtras(i); }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(refresh); }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText((AmbiService.sRunning ? "Running — " : "") + AmbiService.sStatus);
            ui.postDelayed(this, 1000);
        }
    };

    private void start() {
        AmbiService.start(this);
    }

    private void stop() {
        startService(new Intent(this, AmbiService.class).setAction(AmbiService.ACTION_STOP));
    }

    private void test() {
        if (!AmbiService.sRunning) {
            startForegroundService(new Intent(this, AmbiService.class).setAction(AmbiService.ACTION_START_LIGHT));
        }
        ui.postDelayed(() -> startService(new Intent(this, AmbiService.class).setAction(AmbiService.ACTION_TEST)), 300);
    }

    private View action(String label, Runnable r) {
        LinearLayout row = row();
        TextView t = text(label, 18, 0xFFFFFFFF);
        row.addView(t);
        row.setOnClickListener(v -> r.run());
        return row;
    }

    private View settingRow(Prefs.Def d) {
        LinearLayout row = row();
        TextView label = text(d.label, 18, 0xFFE6EDF3);
        TextView value = text("", 18, 0xFF5EC8FF);
        value.setGravity(Gravity.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(value);
        Runnable show = () -> value.setText("‹  " + d.format(Prefs.get(sp, d.key)) + "  ›");
        show.run();
        row.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int dir = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (dir == 0) return false;
            int step = d.step * (ev.getRepeatCount() > 8 ? 10 : 1);
            int v0 = Prefs.get(sp, d.key), v1;
            if (d.names != null) { // enums wrap around
                int span = d.max - d.min + 1;
                v1 = d.min + ((v0 - d.min + dir) % span + span) % span;
            } else {
                v1 = Math.max(d.min, Math.min(d.max, v0 + dir * step));
            }
            if (v1 != v0) {
                sp.edit().putInt(d.key, v1).apply();
                show.run();
                if (d.key.equals(Prefs.MODE) && AmbiService.sRunning) {
                    stop();
                    ui.postDelayed(this::start, 600);
                }
            }
            return true;
        });
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFF24303C);
        focused.setCornerRadius(dp(10));
        focused.setStroke(dp(2), 0xFF5EC8FF);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, new GradientDrawable());
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
