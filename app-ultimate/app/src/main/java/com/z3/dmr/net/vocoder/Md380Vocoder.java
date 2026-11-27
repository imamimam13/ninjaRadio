package com.z3.dmr.net.vocoder;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * JNI wrapper for libmd380vocoder.so.
 * Must match the native JNI signature: Java_com_z3_dmr_net_vocoder_Md380Vocoder_*
 */
public final class Md380Vocoder {
    private static final String TAG = "Md380Vocoder";
    public static volatile boolean isLoaded = false;
    public static volatile boolean isInitialized = false;
    private static final Object lock = new Object();

    static {
        try {
            try {
                System.loadLibrary("atfork_shim");
            } catch (Throwable ignored) {}
            System.loadLibrary("md380vocoder");
            isLoaded = true;
            Log.i(TAG, "libmd380vocoder loaded successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load libmd380vocoder: " + t.getMessage());
            isLoaded = false;
        }
    }

    private native boolean nativeInit(String firmwarePath, String corePath, String emu32Path);
    private native short[] nativeDecode(byte[] bArr);
    private native short[] nativeDecodeDmrdPayload(byte[] bArr);
    private native byte[] nativeEncodeFec(short[] sArr);
    private native int nativeVocoderMode();

    public static boolean init(Context context) {
        if (!isLoaded) {
            Log.w(TAG, "Cannot init: library not loaded");
            return false;
        }
        if (isInitialized) {
            return true;
        }
        synchronized (lock) {
            if (isInitialized) {
                return true;
            }
            try {
                File filesDir = context.getFilesDir();
                File coreFile = new File(filesDir, "md380_core.img");
                File fwFile = new File(filesDir, "md380_firmware.img");

                // Ensure core.img exists
                if (!coreFile.exists() || coreFile.length() == 0) {
                    copyAsset(context, "md380_core.img", coreFile);
                }
                // Ensure firmware.img exists
                if (!fwFile.exists() || fwFile.length() == 0) {
                    copyAsset(context, "md380_firmware.img", fwFile);
                }

                String emu32Path = "";
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                if (nativeLibDir != null) {
                    File emuFile = new File(nativeLibDir, "libmd380emu32.so");
                    if (emuFile.exists()) {
                        emu32Path = emuFile.getAbsolutePath();
                    }
                }

                Md380Vocoder instance = new Md380Vocoder();
                boolean ok = instance.nativeInit(fwFile.getAbsolutePath(), coreFile.getAbsolutePath(), emu32Path);
                isInitialized = ok;
                Log.i(TAG, "nativeInit result: " + ok + " (fw=" + fwFile.length() + ", core=" + coreFile.length() + ", emu=" + emu32Path + ")");
                return ok;
            } catch (Throwable t) {
                Log.e(TAG, "Error initializing Md380Vocoder", t);
                return false;
            }
        }
    }

    private static void copyAsset(Context context, String assetName, File destFile) throws Exception {
        try (InputStream in = context.getAssets().open(assetName);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        Log.i(TAG, "Copied asset " + assetName + " (" + destFile.length() + " bytes) to " + destFile.getAbsolutePath());
    }

    public short[] decode(byte[] ambeFrame) {
        if (!isInitialized || ambeFrame == null) return null;
        try {
            return nativeDecode(ambeFrame);
        } catch (Throwable t) {
            Log.e(TAG, "nativeDecode error", t);
            return null;
        }
    }

    public short[] decodeDmrdPayload(byte[] dmrdPayload) {
        if (!isInitialized || dmrdPayload == null) return null;
        try {
            return nativeDecodeDmrdPayload(dmrdPayload);
        } catch (Throwable t) {
            Log.e(TAG, "nativeDecodeDmrdPayload error", t);
            return null;
        }
    }

    public byte[] encodeFec(short[] pcm) {
        if (!isInitialized || pcm == null) return null;
        try {
            return nativeEncodeFec(pcm);
        } catch (Throwable t) {
            Log.e(TAG, "nativeEncodeFec error", t);
            return null;
        }
    }
}
