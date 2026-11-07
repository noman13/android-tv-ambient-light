package dev.tvambient;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

/** One USB serial port speaking the Adalight protocol: "Ada", count-1 (hi, lo), checksum, then RGB triplets. */
final class AdalightPort {
    private UsbSerialPort port;
    private byte[] packet = new byte[0];

    /** The first USB serial device found, or null. */
    static UsbSerialDriver findDriver(UsbManager um) {
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um);
        return drivers.isEmpty() ? null : drivers.get(0);
    }

    boolean isOpen() {
        return port != null;
    }

    void open(UsbManager um, UsbSerialDriver driver, int baud) throws IOException {
        UsbDeviceConnection conn = um.openDevice(driver.getDevice());
        if (conn == null) throw new IOException("openDevice returned null (no permission?)");
        UsbSerialPort p = driver.getPorts().get(0);
        p.open(conn);
        p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        // DTR and RTS equal: on ESP8266 boards with auto-reset that keeps the chip running normally.
        try { p.setDTR(false); p.setRTS(false); } catch (IOException | UnsupportedOperationException ignored) {}
        port = p;
    }

    void close() {
        if (port != null) {
            try { port.close(); } catch (IOException ignored) {}
            port = null;
        }
    }

    /** rgb holds 3 ints per LED, already in 0..255. order indexes Prefs.ORDERS. */
    void send(int[] rgb, int count, int order) throws IOException {
        if (port == null || count <= 0) return;
        int len = 6 + count * 3;
        if (packet.length != len) packet = new byte[len];
        int hi = ((count - 1) >> 8) & 0xff, lo = (count - 1) & 0xff;
        packet[0] = 'A'; packet[1] = 'd'; packet[2] = 'a';
        packet[3] = (byte) hi; packet[4] = (byte) lo; packet[5] = (byte) (hi ^ lo ^ 0x55);
        int a, b, c;
        switch (order) {
            case 1: a = 0; b = 2; c = 1; break; // RBG
            case 2: a = 1; b = 0; c = 2; break; // GRB
            case 3: a = 1; b = 2; c = 0; break; // GBR
            case 4: a = 2; b = 0; c = 1; break; // BRG
            case 5: a = 2; b = 1; c = 0; break; // BGR
            default: a = 0; b = 1; c = 2;       // RGB
        }
        for (int i = 0, o = 0, p = 6; i < count; i++, o += 3) {
            packet[p++] = (byte) rgb[o + a];
            packet[p++] = (byte) rgb[o + b];
            packet[p++] = (byte) rgb[o + c];
        }
        port.write(packet, 200);
    }
}
