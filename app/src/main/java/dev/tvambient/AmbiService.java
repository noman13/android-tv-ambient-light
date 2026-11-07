package dev.tvambient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Captures the screen (or renders a colour/effect) and streams it to the LED strip.
 *
 * Two capture paths:
 * <ul>
 *   <li><b>Shell screenshots</b> (default): the app connects to the TV's own adbd on 127.0.0.1:5555 and runs
 *   {@link ShellCapture} as the shell user, which takes one-shot screenshots at a fixed rate. Nothing is
 *   composed between screenshots, so the video keeps its frame rate even on TVs whose display service copies
 *   the video plane on the CPU for every capture.</li>
 *   <li><b>Screen recording</b>: a MediaProjection mirror, like other ambient-light apps. Needs no ADB, but
 *   Android composes the mirror on every frame, which makes 1080p video drop frames on such TVs.</li>
 * </ul>
 * Either way the LEDs are refreshed at a steady rate with smoothing between captured frames.
 */
public final class AmbiService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String TAG = "TvAmbient";
    static final String ACTION_START_CAPTURE = "dev.tvambient.START_CAPTURE";
    static final String ACTION_START_SHELL = "dev.tvambient.START_SHELL";
    static final String ACTION_START_LIGHT = "dev.tvambient.START_LIGHT";
    static final String ACTION_STOP = "dev.tvambient.STOP";
    static final String ACTION_TEST = "dev.tvambient.TEST";
    static final String EXTRA_CODE = "code", EXTRA_DATA = "data";
    private static final String ACTION_USB_PERMISSION = "dev.tvambient.USB_PERMISSION";
    private static final String CHANNEL = "ambient";
    private static final int CAP_W = 128, CAP_H = 72;
    private static final int ADB_PORT = 5555;
    private static final int FRAME_MAGIC = 0x54564146; // "TVAF"

    // Read by MainActivity for its status line.
    static volatile boolean sRunning;
    static volatile String sStatus = "Stopped";

    private SharedPreferences sp;
    private volatile LedLayout layout;
    private volatile ColorProcessor proc;
    private final FrameAnalyzer analyzer = new FrameAnalyzer();
    private final AdalightPort port = new AdalightPort();

    // screen recording (MediaProjection)
    private HandlerThread captureThread;
    private Handler capHandler;
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private long lastProcessed;

    // shell screenshots
    private volatile boolean shellRunning;
    private volatile int shellGen;
    private Thread shellThread;
    private volatile dadb.AdbStream shellStream;

    // output
    private Thread outThread;
    private volatile boolean outRunning;
    private final Object lock = new Object();
    private int[] target = new int[0];        // processed capture colours, guarded by lock
    private int[] capRaw = new int[0];         // capture thread scratch
    private volatile boolean haveFrame;
    private volatile boolean screenOff;
    private volatile long testUntil;
    private volatile boolean permissionAsked;

    // black-frame guard and statistics
    private int lastAvg;
    private boolean blackPending;
    private int statCaptured, statSent, statDroppedBlack;
    private volatile int curFps;
    private long statStart;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screenOff = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            Log.i(TAG, "screen " + (screenOff ? "off" : "on"));
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) port.close();
            else permissionAsked = false; // permission answered, or a device attached: try again
        }
    };

    /** Starts whatever the settings ask for: shell screenshots, screen recording, or a colour/effect. */
    static void start(Context c) {
        SharedPreferences sp = Prefs.sp(c);
        if (Prefs.get(sp, Prefs.MODE) != Prefs.MODE_CAPTURE) {
            c.startForegroundService(new Intent(c, AmbiService.class).setAction(ACTION_START_LIGHT));
        } else if (Prefs.get(sp, Prefs.METHOD) == Prefs.METHOD_SHELL) {
            c.startForegroundService(new Intent(c, AmbiService.class).setAction(ACTION_START_SHELL));
        } else {
            c.startActivity(new Intent(c, ProjectionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        sp = Prefs.sp(this);
        sp.registerOnSharedPreferenceChangeListener(this);
        rebuild();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Ambient light", NotificationManager.IMPORTANCE_LOW));
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, sf);
        IntentFilter uf = new IntentFilter(ACTION_USB_PERMISSION);
        uf.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        uf.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, uf, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, uf);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START_LIGHT : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TEST.equals(action)) {
            testUntil = SystemClock.elapsedRealtime() + 8000;
            return START_NOT_STICKY;
        }
        if (ACTION_START_CAPTURE.equals(action)) {
            startForeground(1, notification("Screen capture (screen recording)"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            releaseCapture();
            Intent data = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(EXTRA_DATA, Intent.class) : intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = data == null ? null : mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data);
            if (projection == null) sStatus = "Screen recording was not allowed";
            else startProjection();
        } else if (ACTION_START_SHELL.equals(action)) {
            startForeground(1, notification("Screen capture (shell screenshots)"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            releaseCapture();
            startShellCapture();
        } else {
            startForeground(1, notification("Colour / effect"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            releaseCapture();
        }
        sRunning = true;
        startOutput();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        sStatus = "Stopped";
        outRunning = false;
        if (outThread != null) {
            try { outThread.join(500); } catch (InterruptedException ignored) {}
        }
        releaseCapture();
        port.close();
        sp.unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(screenReceiver);
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences s, String key) {
        rebuild();
        if (Prefs.BAUD.equals(key)) port.close(); // reopened at the new speed by the output loop
    }

    private void rebuild() {
        LedLayout l = new LedLayout(sp);
        proc = new ColorProcessor(sp);
        synchronized (lock) {
            if (target.length != l.count * 3) { target = new int[l.count * 3]; haveFrame = false; }
        }
        capRaw = new int[l.count * 3];
        layout = l;
    }

    private Notification notification(String what) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("TV Ambient")
                .setContentText(what)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void releaseCapture() {
        stopShellCapture();
        if (capHandler != null) capHandler.removeCallbacksAndMessages(null);
        if (vd != null) { vd.release(); vd = null; }
        if (projection != null) { MediaProjection p = projection; projection = null; p.stop(); }
        if (reader != null) { reader.close(); reader = null; }
        if (captureThread != null) { captureThread.quitSafely(); captureThread = null; }
        capHandler = null;
        haveFrame = false;
    }

    // ---------------------------------------------------------------- shell screenshots

    private void startShellCapture() {
        shellRunning = true;
        int gen = ++shellGen;
        statStart = SystemClock.elapsedRealtime();
        shellThread = new Thread(() -> shellLoop(gen), "tvambient-shell");
        shellThread.start();
    }

    private void stopShellCapture() {
        shellRunning = false;
        shellGen++;
        closeShellStream();
        if (shellThread != null) { shellThread.interrupt(); shellThread = null; }
    }

    private void closeShellStream() {
        dadb.AdbStream s = shellStream;
        shellStream = null;
        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
    }

    /**
     * Connects to this TV's own adbd with the app's key and runs ShellCapture as the shell user, then reads its
     * frames. The first connection shows "Allow USB debugging?" on the TV for this app's key.
     */
    private void shellLoop(int gen) {
        File priv = new File(getFilesDir(), "adbkey"), pub = new File(getFilesDir(), "adbkey.pub");
        byte[] frame = new byte[0];
        while (shellRunning && gen == shellGen) {
            if (screenOff) { SystemClock.sleep(500); continue; }
            dadb.Dadb conn = null;
            try {
                if (!priv.exists() || !pub.exists()) dadb.AdbKeyPair.Companion.generate(priv, pub);
                dadb.AdbKeyPair kp = dadb.AdbKeyPair.Companion.read(priv, pub);
                sStatus = "Connecting to the TV's ADB (accept \"Allow USB debugging?\" on the TV the first time)";
                conn = dadb.Dadb.Companion.create("127.0.0.1", ADB_PORT, kp);
                int rate = Prefs.get(sp, Prefs.RATE);
                int fps = Prefs.RATE_FPS[rate];
                curFps = fps;
                String cmd = "exec:CLASSPATH=" + getApplicationInfo().sourceDir
                        + " app_process / " + ShellCapture.class.getName() + " " + CAP_W + " " + CAP_H + " " + fps + " 2>/dev/null";
                dadb.AdbStream s = conn.open(cmd);
                shellStream = s;
                okio.BufferedSource src = s.getSource();
                Log.i(TAG, "shell capture started at " + fps + " fps");
                while (shellRunning && gen == shellGen && !screenOff) {
                    // Native libraries may print text to stdout first: skip to the next frame marker.
                    int magic = src.readInt(), skipped = 0;
                    while (magic != FRAME_MAGIC) {
                        magic = (magic << 8) | (src.readByte() & 0xff);
                        if (++skipped > 1 << 20) throw new IOException("no frame marker in the helper's output");
                    }
                    int w = src.readInt(), h = src.readInt();
                    if (w <= 0 || h <= 0 || w * h > 640 * 360) throw new IOException("bad frame size " + w + "x" + h);
                    if (frame.length != w * h * 3) frame = new byte[w * h * 3];
                    src.readFully(frame);
                    if (rate != Prefs.get(sp, Prefs.RATE)) break; // restart the helper at the new rate
                    LedLayout l = layout;
                    int[] raw = capRaw;
                    if (raw.length != l.count * 3) continue;
                    analyzer.analyzeRgb(frame, w, h, l, Prefs.get(sp, Prefs.BORDER) == 1, raw);
                    long now = SystemClock.elapsedRealtime();
                    finishFrame(l, raw, now);
                    logStats(now);
                }
            } catch (Throwable t) {
                if (!shellRunning || gen != shellGen) break;
                Log.w(TAG, "shell capture: " + t);
                sStatus = "Shell capture unavailable: " + t.getMessage()
                        + " (is USB debugging on, and \"Allow USB debugging\" accepted for TV Ambient?)";
                SystemClock.sleep(3000);
            } finally {
                closeShellStream();
                if (conn != null) { try { conn.close(); } catch (Exception ignored) {} }
            }
        }
    }

    // ---------------------------------------------------------------- screen recording (MediaProjection)

    private void startProjection() {
        captureThread = new HandlerThread("tvambient-capture", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        captureThread.start();
        capHandler = new Handler(captureThread.getLooper());
        reader = ImageReader.newInstance(CAP_W, CAP_H, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, capHandler);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                if (projection == null) return; // we stopped it ourselves
                Log.i(TAG, "screen recording stopped by the system");
                sStatus = "Screen recording stopped by Android";
                Handler h = capHandler;
                if (h != null) h.post(AmbiService.this::releaseCapture);
            }
        }, capHandler);
        vd = projection.createVirtualDisplay("TvAmbient", CAP_W, CAP_H, 120,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHandler);
        statStart = SystemClock.elapsedRealtime();
    }

    private void onImage(ImageReader r) {
        Image img;
        try { img = r.acquireLatestImage(); } catch (IllegalStateException e) { return; }
        if (img == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            curFps = Prefs.RATE_FPS[Prefs.get(sp, Prefs.RATE)];
            if (now - lastProcessed < 1000 / curFps) return;
            LedLayout l = layout;
            int[] raw = capRaw;
            if (raw.length != l.count * 3) return;
            analyzer.analyze(img, l, Prefs.get(sp, Prefs.BORDER) == 1, raw);
            finishFrame(l, raw, now);
            lastProcessed = now;
            logStats(now);
        } finally {
            img.close();
        }
    }

    // ---------------------------------------------------------------- shared frame handling

    /** Black-frame guard, colour processing and hand-over to the output thread. */
    private void finishFrame(LedLayout l, int[] raw, long now) {
        // A sudden all-black frame after a lit one is only accepted when the next capture agrees: real cuts to
        // black last longer than one capture, one-frame glitches do not.
        int sum = 0;
        for (int v : raw) sum += v;
        int avg = raw.length == 0 ? 0 : sum / raw.length;
        if (avg < 4 && lastAvg > 12 && !blackPending) {
            blackPending = true;
            statDroppedBlack++;
            return;
        }
        blackPending = false;
        lastAvg = avg;
        proc.apply(raw, l.count);
        synchronized (lock) {
            if (target.length == raw.length) System.arraycopy(raw, 0, target, 0, raw.length);
        }
        haveFrame = true;
        statCaptured++;
    }

    private void logStats(long now) {
        long dt = now - statStart;
        if (dt < 5000) return;
        String s = String.format(Locale.US, "capture %.1f/s (target %d, %s), black glitches dropped %d, LEDs %.1f/s, USB %s",
                statCaptured * 1000f / dt, curFps, Prefs.def(Prefs.METHOD).format(Prefs.get(sp, Prefs.METHOD)),
                statDroppedBlack, statSent * 1000f / dt, port.isOpen() ? "connected" : "not connected");
        Log.i(TAG, s);
        sStatus = s;
        statCaptured = 0; statSent = 0; statDroppedBlack = 0; statStart = now;
    }

    // ---------------------------------------------------------------- output

    private void startOutput() {
        if (outRunning) return;
        outRunning = true;
        if (statStart == 0) statStart = SystemClock.elapsedRealtime();
        outThread = new Thread(this::outputLoop, "tvambient-output");
        outThread.start();
    }

    private void outputLoop() {
        UsbManager um = getSystemService(UsbManager.class);
        float[] cur = new float[0];
        int[] out = new int[0];
        int[] frame = new int[0];
        long lastConnectTry = 0, lastSend = 0;
        long t0 = SystemClock.elapsedRealtime();
        while (outRunning) {
            long now = SystemClock.elapsedRealtime();
            if (!port.isOpen() && now - lastConnectTry > 2000) {
                lastConnectTry = now;
                connect(um);
            }
            LedLayout l = layout;
            int n = l.count;
            if (cur.length != n * 3) { cur = new float[n * 3]; out = new int[n * 3]; frame = new int[n * 3]; }
            int mode = Prefs.get(sp, Prefs.MODE);
            float t = (now - t0) / 1000f;
            boolean smooth = false;
            int offAction = Prefs.get(sp, Prefs.SCREEN_OFF);

            if (now < testUntil) {
                layoutTest(l, frame, t);
            } else if (screenOff && offAction == Prefs.OFF_BLANK) {
                Arrays.fill(frame, 0);
            } else if (screenOff && offAction == Prefs.OFF_DIM) {
                solid(frame, n, Prefs.get(sp, Prefs.HUE), Prefs.get(sp, Prefs.COLOR_SAT) / 100f, 0.12f);
                proc.applyOutputOnly(frame, n);
            } else if (mode == Prefs.MODE_CAPTURE && !(screenOff && offAction == Prefs.OFF_KEEP)) {
                if (haveFrame) {
                    synchronized (lock) { if (target.length == frame.length) System.arraycopy(target, 0, frame, 0, frame.length); }
                    smooth = true;
                }
            } else if (mode == Prefs.MODE_COLOR) {
                solid(frame, n, Prefs.get(sp, Prefs.HUE), Prefs.get(sp, Prefs.COLOR_SAT) / 100f, 1f);
                proc.applyOutputOnly(frame, n);
            } else if (mode == Prefs.MODE_EFFECT) {
                Effects.render(Prefs.get(sp, Prefs.EFFECT), frame, n, t, Prefs.get(sp, Prefs.HUE), Prefs.get(sp, Prefs.COLOR_SAT) / 100f);
                proc.applyOutputOnly(frame, n);
            }

            // Exponential smoothing for captured colours; 1 = instant, 10 = very soft.
            float alpha = smooth ? 1f - (Prefs.get(sp, Prefs.SMOOTH) - 1) * 0.095f : 1f;
            boolean changed = false;
            for (int i = 0; i < frame.length; i++) {
                cur[i] += (frame[i] - cur[i]) * alpha;
                int v = Math.round(cur[i]);
                if (v != out[i]) { out[i] = v; changed = true; }
            }
            if (port.isOpen() && (changed || now - lastSend > 500)) {
                try {
                    port.send(out, n, Prefs.get(sp, Prefs.ORDER));
                    lastSend = now;
                    statSent++;
                } catch (Exception e) {
                    Log.w(TAG, "serial write failed: " + e);
                    port.close();
                }
            }
            if (vd == null && !shellRunning) logStats(now); // colour/effect modes have no capture loop to log from
            long sleep = 1000 / Prefs.get(sp, Prefs.OUT_FPS) - (SystemClock.elapsedRealtime() - now);
            if (sleep > 0) SystemClock.sleep(sleep);
        }
        // Leave the strip dark when stopping.
        if (port.isOpen() && out.length > 0) {
            Arrays.fill(out, 0);
            try { port.send(out, out.length / 3, 0); } catch (Exception ignored) {}
        }
    }

    private void connect(UsbManager um) {
        UsbSerialDriver d = AdalightPort.findDriver(um);
        if (d == null) { sStatus = "No USB LED controller found"; return; }
        UsbDevice dev = d.getDevice();
        if (!um.hasPermission(dev)) {
            if (!permissionAsked) {
                permissionAsked = true;
                Intent i = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
                um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_MUTABLE));
            }
            sStatus = "Waiting for USB permission";
            return;
        }
        try {
            int baud = Prefs.BAUDS[Prefs.get(sp, Prefs.BAUD)];
            port.open(um, d, baud);
            Log.i(TAG, "USB opened: " + dev.getProductName() + " @ " + baud);
            SystemClock.sleep(300); // boards that reset on open need a moment
        } catch (Exception e) {
            Log.w(TAG, "USB open failed: " + e);
            sStatus = "USB open failed: " + e.getMessage();
            port.close();
        }
    }

    private static void solid(int[] frame, int n, int hue, float sat, float val) {
        int c = Effects.hsv(hue, sat, val);
        for (int i = 0, o = 0; i < n; i++, o += 3) {
            frame[o] = (c >> 16) & 0xff; frame[o + 1] = (c >> 8) & 0xff; frame[o + 2] = c & 0xff;
        }
    }

    /** Top red, right green, bottom blue, left white; the first LEDs blink yellow. Shows the layout is right. */
    private static void layoutTest(LedLayout l, int[] frame, float t) {
        for (int i = 0, o = 0; i < l.count; i++, o += 3) {
            int r = 0, g = 0, b = 0;
            switch (l.edge[i]) {
                case LedLayout.EDGE_TOP: r = 255; break;
                case LedLayout.EDGE_RIGHT: g = 255; break;
                case LedLayout.EDGE_BOTTOM: b = 255; break;
                default: r = g = b = 160;
            }
            if (i < 3 && ((int) (t * 3)) % 2 == 0) { r = 255; g = 200; b = 0; }
            frame[o] = r; frame[o + 1] = g; frame[o + 2] = b;
        }
    }
}
