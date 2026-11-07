package dev.tvambient;

import android.content.SharedPreferences;

/**
 * Where each physical LED samples the picture, as a normalised rectangle (0..1) of the frame.
 * Logical order starts at the top-left corner and runs clockwise: top (left to right), right (top to
 * bottom), bottom (right to left, with an optional gap in the middle), left (bottom to top).
 * Counter-clockwise reverses it; the offset says which logical position physical LED 0 sits at.
 */
final class LedLayout {
    static final int EDGE_TOP = 0, EDGE_RIGHT = 1, EDGE_BOTTOM = 2, EDGE_LEFT = 3;

    final int count;
    final float[] x0, y0, x1, y1;
    final int[] edge;

    LedLayout(SharedPreferences sp) {
        int t = Prefs.get(sp, Prefs.TOP), r = Prefs.get(sp, Prefs.RIGHT), b = Prefs.get(sp, Prefs.BOTTOM),
                l = Prefs.get(sp, Prefs.LEFT), gap = Prefs.get(sp, Prefs.GAP);
        float dTb = Prefs.get(sp, Prefs.DEPTH_TB) / 100f, dLr = Prefs.get(sp, Prefs.DEPTH_LR) / 100f;
        boolean ccw = Prefs.get(sp, Prefs.DIRECTION) == 1;
        int offset = Prefs.get(sp, Prefs.OFFSET);

        count = t + r + b + l;
        float[] lx0 = new float[count], ly0 = new float[count], lx1 = new float[count], ly1 = new float[count];
        int[] le = new int[count];
        int n = 0;
        for (int i = 0; i < t; i++, n++) set(lx0, ly0, lx1, ly1, le, n, i / (float) t, 0, (i + 1) / (float) t, dTb, EDGE_TOP);
        for (int i = 0; i < r; i++, n++) set(lx0, ly0, lx1, ly1, le, n, 1 - dLr, i / (float) r, 1, (i + 1) / (float) r, EDGE_RIGHT);
        int slots = b + gap, gapStart = b / 2, s = 0;
        for (int i = 0; i < b; i++, n++, s++) {
            if (s == gapStart) s += gap; // skip the slots hidden behind a stand
            set(lx0, ly0, lx1, ly1, le, n, 1 - (s + 1) / (float) slots, 1 - dTb, 1 - s / (float) slots, 1, EDGE_BOTTOM);
        }
        for (int i = 0; i < l; i++, n++) set(lx0, ly0, lx1, ly1, le, n, 0, 1 - (i + 1) / (float) l, dLr, 1 - i / (float) l, EDGE_LEFT);

        x0 = new float[count]; y0 = new float[count]; x1 = new float[count]; y1 = new float[count]; edge = new int[count];
        for (int p = 0; p < count; p++) {
            int logical = (p + offset) % count;
            if (ccw) logical = count - 1 - logical; // from the top-left corner, down the left side first
            x0[p] = lx0[logical]; y0[p] = ly0[logical]; x1[p] = lx1[logical]; y1[p] = ly1[logical]; edge[p] = le[logical];
        }
    }

    private static void set(float[] x0, float[] y0, float[] x1, float[] y1, int[] e, int i,
                            float a, float b, float c, float d, int edge) {
        x0[i] = a; y0[i] = b; x1[i] = c; y1[i] = d; e[i] = edge;
    }
}
