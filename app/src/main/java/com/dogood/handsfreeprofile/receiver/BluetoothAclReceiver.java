package com.dogood.handsfreeprofile.receiver;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

public class BluetoothAclReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothAclReceiver";
    private boolean isRegistered = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.w(TAG, "Received null intent in bluetoothAclReceiver");
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            Log.w(TAG, "Received intent with null action in bluetoothAclReceiver");
            return;
        }
        BluetoothDevice device;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }

        if (device == null) {
            Log.w(TAG, "BluetoothDevice.EXTRA_DEVICE is null in intent: " + action);
            return;
        }

        switch (action) {
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                Log.i(TAG, "Bluetooth device connected: " + com.dogood.handsfreeprofile.util.BluetoothUtils.getSafeDeviceName(context, device));
                break;
            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                Log.i(TAG, "Bluetooth device disconnected: " + com.dogood.handsfreeprofile.util.BluetoothUtils.getSafeDeviceName(context, device));
                break;
            default:
                Log.d(TAG, "Received unhandled Bluetooth action: " + action);
                break;
        }
    }

    public void registerReceiver(Context context) {
        if (!isRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(this, filter);
                }
                isRegistered = true;
                Log.d(TAG, "Bluetooth ACL receiver registered.");
            } catch (Exception e) {
                Log.e(TAG, "Error registering Bluetooth ACL receiver: " + e.getMessage(), e);
            }
        }
    }

    public void unregisterReceiver(Context context) {
        if (isRegistered) {
            try {
                context.unregisterReceiver(this);
                isRegistered = false;
                Log.d(TAG, "Bluetooth ACL receiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bluetooth ACL receiver was not registered or already unregistered: " + e.getMessage());
            }
        }
    }
}