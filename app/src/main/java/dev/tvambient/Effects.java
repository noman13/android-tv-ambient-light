package dev.tvambient;

/** Animated patterns for Effect mode. Colours are written as 3 ints per LED, 0..255. */
final class Effects {
    static void render(int effect, int[] f, int n, float t, int hue, float sat) {
        if (n == 0) return;
        switch (effect) {
            case 0: // rainbow swirl clockwise
            case 1: // counter-clockwise
                float dir = effect == 0 ? -1 : 1;
                for (int i = 0; i < n; i++) put(f, i, hsv((int) (i * 360f / n + dir * t * 60f), 1f, 1f));
                break;
            case 2: { // breathing in the chosen colour
                float v = 0.12f + 0.88f * (0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 5));
                int c = hsv(hue, sat, v);
                for (int i = 0; i < n; i++) put(f, i, c);
                break;
            }
            case 3: { // a light running around the frame with a fading tail
                float pos = (t * 45f) % n;
                int base = hsv(hue, sat, 0.08f);
                for (int i = 0; i < n; i++) {
                    float d = (pos - i + n) % n; // distance behind the head
                    put(f, i, d < 18 ? hsv(hue, sat, 1f - d / 18f * 0.92f) : base);
                }
                break;
            }
            default: { // slow colours: the whole frame drifts through the hues
                int c = hsv((int) (t * 8f), 0.9f, 1f);
                for (int i = 0; i < n; i++) put(f, i, c);
            }
        }
    }

    private static void put(int[] f, int i, int c) {
        f[i * 3] = (c >> 16) & 0xff; f[i * 3 + 1] = (c >> 8) & 0xff; f[i * 3 + 2] = c & 0xff;
    }

    /** hue in degrees, sat and val 0..1, returns 0xRRGGBB. */
    static int hsv(int hue, float sat, float val) {
        float h = ((hue % 360) + 360) % 360 / 60f;
        int sector = (int) h;
        float f = h - sector, p = val * (1 - sat), q = val * (1 - sat * f), u = val * (1 - sat * (1 - f));
        float r, g, b;
        switch (sector) {
            case 0: r = val; g = u; b = p; break;
            case 1: r = q; g = val; b = p; break;
            case 2: r = p; g = val; b = u; break;
            case 3: r = p; g = q; b = val; break;
            case 4: r = u; g = p; b = val; break;
            default: r = val; g = p; b = q;
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    private Effects() {}
}
