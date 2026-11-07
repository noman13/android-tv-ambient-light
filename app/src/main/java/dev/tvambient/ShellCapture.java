package dev.tvambient;

import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * Runs as the shell user, not inside the app:
 *   CLASSPATH=$(pm path dev.tvambient | cut -d: -f2) app_process / dev.tvambient.ShellCapture 128 72 10
 *
 * Takes one-shot screenshots of the main display, scaled to w x h by SurfaceFlinger, at fps per second, and
 * writes each as a frame to stdout: magic "TVAF", width, height (ints), then w*h*3 RGB bytes.
 *
 * Unlike a MediaProjection mirror, nothing is composed between screenshots: on MStar TVs the vendor display
 * service copies the video plane once per screenshot instead of once per video frame.
 */
public final class ShellCapture {
    public static void main(String[] args) throws Exception {
        int w = args.length > 0 ? Integer.parseInt(args[0]) : 128;
        int h = args.length > 1 ? Integer.parseInt(args[1]) : 72;
        int fps = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        boolean bench = args.length > 3 && args[3].equals("bench"); // stats on stderr, frames discarded
        PrintStream err = System.err;

        IBinder token = displayToken();
        Class<?> sc = Class.forName("android.window.ScreenCapture");
        Class<?> builderCls = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
        Object builder = builderCls.getConstructor(IBinder.class).newInstance(token);
        builderCls.getMethod("setSize", int.class, int.class).invoke(builder, w, h);
        Object captureArgs = builderCls.getMethod("build").invoke(builder);
        Method capture = sc.getMethod("captureDisplay", Class.forName("android.window.ScreenCapture$DisplayCaptureArgs"));
        Method asBitmap = Class.forName("android.window.ScreenCapture$ScreenshotHardwareBuffer").getMethod("asBitmap");

        // Raw fd 1, not System.out: a PrintStream swallows write errors, and a failed write (the app closed the
        // stream) is how this process learns to exit.
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), w * h * 3 + 16));
        int[] px = new int[w * h];
        byte[] rgb = new byte[w * h * 3];
        long period = 1000 / Math.max(1, fps);
        long statStart = SystemClock.uptimeMillis(), spent = 0;
        int frames = 0;
        while (true) {
            long t0 = SystemClock.uptimeMillis();
            Object shot = capture.invoke(null, captureArgs);
            if (shot != null) {
                Bitmap hw = (Bitmap) asBitmap.invoke(shot);
                Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
                int bw = Math.min(w, sw.getWidth()), bh = Math.min(h, sw.getHeight());
                sw.getPixels(px, 0, w, 0, 0, bw, bh);
                sw.recycle();
                if (!bench) {
                    for (int i = 0, o = 0; i < px.length; i++) {
                        int c = px[i];
                        rgb[o++] = (byte) (c >> 16); rgb[o++] = (byte) (c >> 8); rgb[o++] = (byte) c;
                    }
                    out.writeInt(0x54564146); // "TVAF"
                    out.writeInt(w);
                    out.writeInt(h);
                    out.write(rgb);
                    out.flush();
                }
                frames++;
            }
            long t1 = SystemClock.uptimeMillis();
            spent += t1 - t0;
            if (t1 - statStart >= 5000) {
                err.printf("shellcapture: %.1f frames/s, %.1f ms per capture%n",
                        frames * 1000f / (t1 - statStart), frames == 0 ? 0f : spent / (float) frames);
                frames = 0; spent = 0; statStart = t1;
            }
            long sleep = period - (SystemClock.uptimeMillis() - t0);
            if (sleep > 0) SystemClock.sleep(sleep);
        }
    }

    /** The main display's token. Android 14 moved the lookup to DisplayControl in services.jar. */
    private static IBinder displayToken() throws Exception {
        try {
            Class<?> s = Class.forName("android.view.SurfaceControl");
            Method m = s.getMethod("getInternalDisplayToken");
            return (IBinder) m.invoke(null);
        } catch (ReflectiveOperationException ignored) {
            // Android 14+
        }
        ClassLoader cl = (ClassLoader) Class.forName("com.android.internal.os.ClassLoaderFactory")
                .getMethod("createClassLoader", String.class, String.class, String.class, ClassLoader.class,
                        int.class, boolean.class, String.class)
                .invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(),
                        0, true, null);
        Class<?> dc = cl.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        load.invoke(Runtime.getRuntime(), dc, "android_servers");
        long[] ids = (long[]) dc.getMethod("getPhysicalDisplayIds").invoke(null);
        return (IBinder) dc.getMethod("getPhysicalDisplayToken", long.class).invoke(null, ids[0]);
    }
}
