package com.dogood.handsfreeprofile.util;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

public class BluetoothUtils {
    private static final String TAG = "BluetoothUtils";

    public static String getSafeDeviceName(Context context, BluetoothDevice device) {
        if (device == null) return "Unknown Device";
        String address = device.getAddress();
        String nameToDisplay = address;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                String retrievedName = device.getName();
                if (retrievedName != null && !retrievedName.isEmpty()) {
                    nameToDisplay = retrievedName;
                }
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException getting device name for " + address + ": " + se.getMessage());
            }
        }
        return nameToDisplay;
    }

    public static boolean hasRequiredPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }
}