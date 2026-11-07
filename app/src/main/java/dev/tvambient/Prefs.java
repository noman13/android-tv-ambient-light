package dev.tvambient;

import android.content.Context;
import android.content.SharedPreferences;

/** Every user setting, its range and default. Stored as ints in one SharedPreferences file. */
final class Prefs {
    static final String FILE = "settings";

    /** Bumped when settings are renumbered; older stored values are dropped so the defaults below apply. */
    private static final int SCHEMA = 2;
    private static final String SCHEMA_KEY = "schema";

    /** A setting shown as one row in MainActivity. Enum settings use {@link #names}. */
    static final class Def {
        final String key, label;
        final int min, max, step, def;
        final String[] names;
        final String unit;

        Def(String key, String label, int min, int max, int step, int def, String unit, String... names) {
            this.key = key; this.label = label; this.min = min; this.max = max; this.step = step;
            this.def = def; this.unit = unit; this.names = names.length == 0 ? null : names;
        }

        String format(int v) {
            if (names != null) return names[Math.max(0, Math.min(names.length - 1, v))];
            if (key.equals(GAMMA)) return String.format(java.util.Locale.US, "%.1f", v / 10f);
            return v + unit;
        }
    }

    static final int MODE_CAPTURE = 0, MODE_COLOR = 1, MODE_EFFECT = 2;
    static final int[] RATE_FPS = {10, 15, 20, 30};
    static final int METHOD_SHELL = 0, METHOD_PROJECTION = 1;
    static final int OFF_BLANK = 0, OFF_KEEP = 1, OFF_DIM = 2;
    static final String[] ORDERS = {"RGB", "RBG", "GRB", "GBR", "BRG", "BGR"};
    static final int[] BAUDS = {115200, 230400, 460800, 500000, 921600, 1000000, 2000000};

    static final String MODE = "mode", EFFECT = "effect", HUE = "hue", COLOR_SAT = "color_sat",
            RATE = "rate", METHOD = "method", BRIGHT = "bright", SAT = "sat", GAMMA = "gamma",
            BAL_R = "bal_r", BAL_G = "bal_g", BAL_B = "bal_b", SMOOTH = "smooth", BLACK = "black",
            BORDER = "border", DEPTH_TB = "depth_tb", DEPTH_LR = "depth_lr",
            TOP = "top", RIGHT = "right", BOTTOM = "bottom", LEFT = "left", GAP = "gap",
            DIRECTION = "direction", OFFSET = "offset", ORDER = "order", BAUD = "baud",
            BOOT = "boot", SCREEN_OFF = "screen_off", OUT_FPS = "out_fps";

    // Defaults are the calibration of the reference setup: 43" TV, 184-LED WS2812B strip, D1 mini at 230400 baud.
    static final Def[] ALL = {
            new Def(MODE, "Mode", 0, 2, 1, MODE_CAPTURE, "", "Screen capture", "Single colour", "Effect"),
            new Def(METHOD, "Capture method", 0, 1, 1, METHOD_SHELL, "", "Shell screenshots (smooth video)", "Screen recording (no ADB needed)"),
            new Def(RATE, "Capture rate", 0, RATE_FPS.length - 1, 1, 2, "", "10 fps", "15 fps", "20 fps", "30 fps"),
            new Def(SMOOTH, "Smoothing", 1, 10, 1, 6, ""),
            new Def(BRIGHT, "Brightness", 5, 100, 5, 35, "%"),
            new Def(SAT, "Saturation", 0, 200, 10, 150, "%"),
            new Def(GAMMA, "Gamma", 5, 30, 1, 19, ""),
            new Def(BAL_R, "Red balance", 0, 100, 5, 100, "%"),
            new Def(BAL_G, "Green balance", 0, 100, 5, 100, "%"),
            new Def(BAL_B, "Blue balance", 0, 100, 5, 100, "%"),
            new Def(BLACK, "Black threshold", 0, 40, 1, 5, ""),
            new Def(BORDER, "Black-border detection", 0, 1, 1, 1, "", "Off", "On"),
            new Def(DEPTH_TB, "Top/bottom capture depth", 2, 30, 1, 10, "%"),
            new Def(DEPTH_LR, "Left/right capture depth", 2, 30, 1, 8, "%"),
            new Def(EFFECT, "Effect", 0, 4, 1, 1, "", "Rainbow swirl CW", "Rainbow swirl CCW", "Breathing", "Running light", "Slow colours"),
            new Def(HUE, "Colour hue", 0, 355, 5, 20, "°"),
            new Def(COLOR_SAT, "Colour saturation", 0, 100, 5, 80, "%"),
            new Def(TOP, "LEDs top", 0, 300, 1, 56, ""),
            new Def(RIGHT, "LEDs right", 0, 300, 1, 36, ""),
            new Def(BOTTOM, "LEDs bottom", 0, 300, 1, 56, ""),
            new Def(LEFT, "LEDs left", 0, 300, 1, 36, ""),
            new Def(GAP, "Bottom gap (LEDs)", 0, 100, 1, 0, ""),
            new Def(DIRECTION, "Strip direction", 0, 1, 1, 0, "", "Clockwise", "Counter-clockwise"),
            new Def(OFFSET, "First LED offset", 0, 600, 1, 0, ""),
            new Def(ORDER, "RGB byte order", 0, ORDERS.length - 1, 1, 0, "", ORDERS),
            new Def(BAUD, "Serial baud rate", 0, BAUDS.length - 1, 1, 1, "", "115200", "230400", "460800", "500000", "921600", "1000000", "2000000"),
            new Def(OUT_FPS, "LED update rate", 20, 40, 5, 30, " fps"),
            new Def(SCREEN_OFF, "When the screen turns off", 0, 2, 1, OFF_BLANK, "", "LEDs off", "Keep last colours", "Dim colour"),
            new Def(BOOT, "Start on boot", 0, 1, 1, 1, "", "Off", "On"),
    };

    static Def def(String key) {
        for (Def d : ALL) if (d.key.equals(key)) return d;
        throw new IllegalArgumentException(key);
    }

    static SharedPreferences sp(Context c) {
        SharedPreferences sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (sp.getInt(SCHEMA_KEY, 0) < SCHEMA) sp.edit().clear().putInt(SCHEMA_KEY, SCHEMA).commit();
        return sp;
    }

    static int get(SharedPreferences sp, String key) {
        Def d = def(key);
        return Math.max(d.min, Math.min(d.max, sp.getInt(key, d.def)));
    }

    private Prefs() {}
}
