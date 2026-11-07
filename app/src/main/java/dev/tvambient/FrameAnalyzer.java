package dev.tvambient;

import android.media.Image;

import java.nio.ByteBuffer;

/** Turns one small RGBA frame into an average colour per LED, cropping black bars when asked to. */
final class FrameAnalyzer {
    private static final int BAR_LUMA = 24;      // a row/column darker than this everywhere counts as a bar
    private static final int STABLE_FRAMES = 3;  // a new crop must be seen this often in a row

    private byte[] buf = new byte[0];
    private int cropX, cropY, candX = -1, candY = -1, candSeen;

    /** Averages an RGBA ImageReader frame into rgb (3 ints per LED). */
    boolean analyze(Image img, LedLayout layout, boolean borderDetect, int[] rgb) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer bb = plane.getBuffer();
        int need = bb.remaining();
        if (buf.length < need) buf = new byte[need];
        bb.get(buf, 0, need);
        return analyzeBuf(img.getWidth(), img.getHeight(), plane.getPixelStride(), plane.getRowStride(), layout, borderDetect, rgb);
    }

    /** Averages a packed RGB frame (3 bytes per pixel, as ShellCapture sends) into rgb. */
    boolean analyzeRgb(byte[] frame, int w, int h, LedLayout layout, boolean borderDetect, int[] rgb) {
        buf = frame;
        return analyzeBuf(w, h, 3, w * 3, layout, borderDetect, rgb);
    }

    private boolean analyzeBuf(int w, int h, int ps, int rs, LedLayout layout, boolean borderDetect, int[] rgb) {
        if (borderDetect) updateCrop(w, h, ps, rs);
        else { cropX = 0; cropY = 0; }

        int ax = cropX, ay = cropY, aw = w - 2 * cropX, ah = h - 2 * cropY;
        for (int i = 0, o = 0; i < layout.count; i++, o += 3) {
            int px0 = ax + (int) (layout.x0[i] * aw), px1 = ax + (int) Math.ceil(layout.x1[i] * aw);
            int py0 = ay + (int) (layout.y0[i] * ah), py1 = ay + (int) Math.ceil(layout.y1[i] * ah);
            if (px1 <= px0) px1 = px0 + 1;
            if (py1 <= py0) py1 = py0 + 1;
            px1 = Math.min(px1, w); py1 = Math.min(py1, h);
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int y = py0; y < py1; y++) {
                int row = y * rs;
                for (int x = px0; x < px1; x++) {
                    int p = row + x * ps;
                    sr += buf[p] & 0xff; sg += buf[p + 1] & 0xff; sb += buf[p + 2] & 0xff; n++;
                }
            }
            if (n == 0) n = 1;
            rgb[o] = sr / n; rgb[o + 1] = sg / n; rgb[o + 2] = sb / n;
        }
        return true;
    }

    private void updateCrop(int w, int h, int ps, int rs) {
        int maxY = h / 4, maxX = w / 4;
        int top = 0;
        while (top < maxY && darkRow(top, w, ps, rs)) top++;
        int bottom = 0;
        while (bottom < maxY && darkRow(h - 1 - bottom, w, ps, rs)) bottom++;
        int left = 0;
        while (left < maxX && darkCol(left, h, ps, rs)) left++;
        int right = 0;
        while (right < maxX && darkCol(w - 1 - right, h, ps, rs)) right++;
        if (top >= maxY && left >= maxX) return; // an all-black frame says nothing about bars: keep the crop

        int y = Math.min(top, bottom), x = Math.min(left, right);
        // Small differences are noise from logos and subtitles: only bars of 2+ rows/columns count.
        if (y < 2) y = 0;
        if (x < 2) x = 0;
        if (x == candX && y == candY) {
            if (++candSeen >= STABLE_FRAMES) { cropX = x; cropY = y; }
        } else {
            candX = x; candY = y; candSeen = 1;
        }
    }

    private boolean darkRow(int y, int w, int ps, int rs) {
        int row = y * rs;
        for (int x = 0; x < w; x += 2) if (luma(row + x * ps) > BAR_LUMA) return false;
        return true;
    }

    private boolean darkCol(int x, int h, int ps, int rs) {
        for (int y = 0; y < h; y += 2) if (luma(y * rs + x * ps) > BAR_LUMA) return false;
        return true;
    }

    private int luma(int p) {
        return ((buf[p] & 0xff) * 77 + (buf[p + 1] & 0xff) * 150 + (buf[p + 2] & 0xff) * 29) >> 8;
    }
}
