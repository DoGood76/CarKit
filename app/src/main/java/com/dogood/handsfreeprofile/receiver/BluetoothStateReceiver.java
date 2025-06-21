package com.dogood.handsfreeprofile.receiver;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

import com.dogood.handsfreeprofile.model.CarKitUiState;
import com.dogood.handsfreeprofile.repository.HfpStateRepository;
import com.dogood.handsfreeprofile.service.HfpAgentService;
import com.dogood.handsfreeprofile.service.NotificationHelper;

public class BluetoothStateReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothStateReceiver";
    private final HfpStateRepository hfpStateRepository;
    private final NotificationHelper notificationHelper;
    private final HfpAgentService callback;
    private boolean isRegistered = false;

    public BluetoothStateReceiver(HfpStateRepository hfpStateRepository, NotificationHelper notificationHelper, HfpAgentService callback) {
        this.hfpStateRepository = hfpStateRepository;
        this.notificationHelper = notificationHelper;
        this.callback = callback;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        Log.d(TAG, "Bluetooth state receiver: Received action: " + action);

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            switch (state) {
                case BluetoothAdapter.STATE_OFF:
                    Log.i(TAG, "Bluetooth turned OFF via broadcast.");
                    hfpStateRepository.reportBluetoothEnabled(false);
                    notificationHelper.updateNotification("Bluetooth is OFF");
                    callback.stopHfpServer();
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    Log.d(TAG, "Bluetooth turning OFF...");
                    hfpStateRepository.updateUiState(CarKitUiState.LOADING);
                    break;
                case BluetoothAdapter.STATE_ON:
                    Log.i(TAG, "Bluetooth turned ON via broadcast.");
                    hfpStateRepository.reportBluetoothEnabled(true);
                    boolean permsGranted = com.dogood.handsfreeprofile.util.BluetoothUtils.hasRequiredPermissions(context);
                    if (permsGranted) {
                        callback.startHfpServer();
                    } else {
                        notificationHelper.updateNotification("Bluetooth permissions needed");
                    }
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    Log.d(TAG, "Bluetooth turning ON...");
                    hfpStateRepository.updateUiState(CarKitUiState.LOADING);
                    notificationHelper.updateNotification("Bluetooth starting...");
                    break;
                default:
                    Log.d(TAG, "Unhandled Bluetooth state: " + state);
                    break;
            }
        }
    }

    public void registerReceiver(Context context) {
        if (!isRegistered) {
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(this, filter);
                }
                isRegistered = true;
                Log.d(TAG, "Bluetooth state receiver registered.");
            } catch (Exception e) {
                Log.e(TAG, "Error registering Bluetooth state receiver: " + e.getMessage(), e);
            }
        }
    }

    public void unregisterReceiver(Context context) {
        if (isRegistered) {
            try {
                context.unregisterReceiver(this);
                isRegistered = false;
                Log.d(TAG, "Bluetooth state receiver unregistered.");
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Bluetooth state receiver was not registered or already unregistered: " + e.getMessage());
            }
        }
    }
}