# Started by name with app_process as the shell user; nothing in the app references it.
-keep class dev.tvambient.ShellCapture { public static void main(java.lang.String[]); }

# usb-serial-for-android finds its drivers by reflection (getSupportedDevices).
-keep class com.hoho.android.usbserial.driver.** { *; }
