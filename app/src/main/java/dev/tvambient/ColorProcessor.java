package dev.tvambient;

import android.content.SharedPreferences;

/** Saturation, black threshold, then one lookup table per channel for gamma, balance and brightness. */
final class ColorProcessor {
    private final int[] lutR = new int[256], lutG = new int[256], lutB = new int[256];
    private final int sat256;
    private final int black;

    ColorProcessor(SharedPreferences sp) {
        float gamma = Prefs.get(sp, Prefs.GAMMA) / 10f;
        float bright = Prefs.get(sp, Prefs.BRIGHT) / 100f;
        fill(lutR, gamma, bright * Prefs.get(sp, Prefs.BAL_R) / 100f);
        fill(lutG, gamma, bright * Prefs.get(sp, Prefs.BAL_G) / 100f);
        fill(lutB, gamma, bright * Prefs.get(sp, Prefs.BAL_B) / 100f);
        sat256 = Prefs.get(sp, Prefs.SAT) * 256 / 100;
        black = Prefs.get(sp, Prefs.BLACK) * 2;
    }

    private static void fill(int[] lut, float gamma, float scale) {
        for (int i = 0; i < 256; i++) {
            lut[i] = Math.round((float) Math.pow(i / 255f, gamma) * 255f * scale);
        }
    }

    /** Processes rgb in place (three ints per LED). */
    void apply(int[] rgb, int count) {
        for (int i = 0, o = 0; i < count; i++, o += 3) {
            int r = rgb[o], g = rgb[o + 1], b = rgb[o + 2];
            if (Math.max(r, Math.max(g, b)) <= black) {
                rgb[o] = rgb[o + 1] = rgb[o + 2] = 0;
                continue;
            }
            int y = (r * 77 + g * 150 + b * 29) >> 8;
            r = clamp(y + (((r - y) * sat256) >> 8));
            g = clamp(y + (((g - y) * sat256) >> 8));
            b = clamp(y + (((b - y) * sat256) >> 8));
            rgb[o] = lutR[r]; rgb[o + 1] = lutG[g]; rgb[o + 2] = lutB[b];
        }
    }

    /** Brightness and balance only, for colours and effects that are already chosen by the user. */
    void applyOutputOnly(int[] rgb, int count) {
        for (int i = 0, o = 0; i < count; i++, o += 3) {
            rgb[o] = lutR[rgb[o]]; rgb[o + 1] = lutG[rgb[o + 1]]; rgb[o + 2] = lutB[rgb[o + 2]];
        }
    }

    static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
