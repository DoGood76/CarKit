package com.dogood.handsfreeprofile;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.util.List;

/**
 * ViewModel for managing Bluetooth state, specifically focusing on the Hands-Free Profile (HFP).
 *
 * This ViewModel handles:
 * - Accessing the device's Bluetooth adapter.
 * - Checking and reacting to Bluetooth adapter state changes (on/off).
 * - Checking and reacting to HFP connection state changes.
 * - Managing the {@link BluetoothHeadset} proxy for HFP.
 * - Requesting and handling the {@link Manifest.permission#BLUETOOTH_CONNECT} permission
 *   for Android S (API 31) and above.
 * - Exposing the overall Bluetooth UI state via {@link LiveData} through {@link #bluetoothUiState}.
 *
 * The possible UI states are defined in {@link BluetoothUiState}:
 * - {@link BluetoothUiState#NOT_SUPPORTED}: Bluetooth is not available on this device.
 * - {@link BluetoothUiState#NO_PERMISSION}: The required BLUETOOTH_CONNECT permission is not granted (Android S+).
 * - {@link BluetoothUiState#DISABLED}: Bluetooth is supported but currently turned off.
 * - {@link BluetoothUiState#ENABLED}: Bluetooth is on, permission is granted, but no HFP device is connected.
 * - {@link BluetoothUiState#CONNECTED_HFP}: Bluetooth is on, permission is granted, and an HFP device is connected.
 *
 * It registers broadcast receivers to listen for system Bluetooth events and updates the UI state
 * accordingly. It also handles the lifecycle of the HFP profile proxy, ensuring it's initialized
 * when Bluetooth is on and permission is granted, and closed when the ViewModel is cleared.
 */
public class BluetoothViewModel extends AndroidViewModel {

    private static final String TAG = "BluetoothViewModel_Java";

    private final MutableLiveData<BluetoothUiState> _bluetoothUiState = new MutableLiveData<>();
    public LiveData<BluetoothUiState> bluetoothUiState = _bluetoothUiState;

    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothHeadset bluetoothHeadset;

    private final BluetoothProfile.ServiceListener hfpProfileListener = new BluetoothProfile.ServiceListener() {
        /**
         * Called when the Bluetooth profile service is connected.
         * This method is invoked after {@link BluetoothAdapter#getProfileProxy(Context, BluetoothProfile.ServiceListener, int)}
         * successfully initiates a connection to the profile service.
         *
         * @param profile The Bluetooth profile that has been connected.
         *                For this listener, it will be {@link BluetoothProfile#HEADSET}.
         * @param proxy   A {@link BluetoothProfile} proxy object for the connected profile.
         *                This can be cast to {@link BluetoothHeadset} to interact with the
         *                Hands-Free Profile.
         */
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = (BluetoothHeadset) proxy;
                Log.d(TAG, "BluetoothHeadset proxy connected");
                updateState();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) {
                Log.d(TAG, "BluetoothHeadset proxy disconnected");
                bluetoothHeadset = null;
                updateState();
            }
        }
    };

    /**
     * BroadcastReceiver for Bluetooth state changes.
     * Listens for:
     * - {@link BluetoothAdapter#ACTION_STATE_CHANGED}: Triggered when the Bluetooth adapter's
     *   state (e.g., on/off) changes. If Bluetooth is turned on, it attempts to initialize
     *   the HFP proxy.
     * - {@link BluetoothHeadset#ACTION_CONNECTION_STATE_CHANGED}: Triggered when the connection
     *   state of a Bluetooth headset (HFP) device changes.
     * In both cases, it calls {@link #updateState()} to refresh the UI state.
     */
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            Log.d(TAG, "Received action: " + action);
            switch (action) {
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        initializeHfpProxy();
                    }
                    updateState();
                    break;
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    updateState();
                    break;
            }
        }
    };

    public BluetoothViewModel(@NonNull Application application) {
        super(application);
        bluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        } else {
            bluetoothAdapter = null; // Should ideally not happen on most devices
        }


        if (bluetoothAdapter == null) {
            _bluetoothUiState.setValue(BluetoothUiState.NOT_SUPPORTED);
        } else {
            registerReceivers();
            initializeHfpProxy();
            updateState();
        }
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        getApplication().registerReceiver(bluetoothStateReceiver, filter);
        Log.d(TAG, "Bluetooth receivers registered");
    }

    private void initializeHfpProxy() {
        if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
            if (hasBluetoothConnectPermission()) {
                // GetProfileProxy returns true if the connection initiation was successful.
                // The actual connection result comes through the ServiceListener.
                boolean success = bluetoothAdapter.getProfileProxy(getApplication(), hfpProfileListener, BluetoothProfile.HEADSET);
                if (!success) {
                    Log.w(TAG, "getProfileProxy for HFP failed to initiate.");
                } else {
                    Log.d(TAG, "getProfileProxy for HFP initiated.");
                }
            } else {
                Log.w(TAG, "BLUETOOTH_CONNECT not granted, cannot get HFP proxy.");
                // State will be updated to NO_PERMISSION by updateState()
            }
        } else {
            Log.d(TAG, "Bluetooth adapter not enabled or null, cannot initialize HFP proxy yet.");
        }
    }

    public void updateState() {
        if (bluetoothAdapter == null) {
            _bluetoothUiState.postValue(BluetoothUiState.NOT_SUPPORTED); // Use postValue if called from bg thread
            Log.d(TAG, "Adapter null, UI: NOT_SUPPORTED");
            return;
        }

        if (!hasBluetoothConnectPermission()) {
            _bluetoothUiState.postValue(BluetoothUiState.NO_PERMISSION);
            Log.d(TAG, "No BLUETOOTH_CONNECT permission, UI: NO_PERMISSION");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            _bluetoothUiState.postValue(BluetoothUiState.DISABLED);
            Log.d(TAG, "Adapter disabled, UI: DISABLED");
            return;
        }

        // Adapter enabled & permission granted, check HFP
        BluetoothHeadset hfp = bluetoothHeadset; // Local variable for thread safety
        if (hfp != null) {
            try {
                // Permission check is good practice, though hasBluetoothConnectPermission should cover it.
                if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    _bluetoothUiState.postValue(BluetoothUiState.NO_PERMISSION); // Should not happen
                    return;
                }
                List<BluetoothDevice> connectedDevices = hfp.getConnectedDevices();
                if (connectedDevices != null && !connectedDevices.isEmpty()) {
                    _bluetoothUiState.postValue(BluetoothUiState.CONNECTED_HFP);
                    Log.d(TAG, "Adapter enabled, HFP connected, UI: CONNECTED_HFP");
                } else {
                    _bluetoothUiState.postValue(BluetoothUiState.ENABLED);
                    Log.d(TAG, "Adapter enabled, HFP not connected, UI: ENABLED");
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException getting connected HFP devices: " + e.getMessage());
                _bluetoothUiState.postValue(BluetoothUiState.ENABLED); // Fallback
            }
        } else {
            _bluetoothUiState.postValue(BluetoothUiState.ENABLED);
            Log.d(TAG, "Adapter enabled, HFP proxy null, UI: ENABLED");
            // Attempt to get proxy if not already available (e.g., BT just turned on)
            // This might be redundant if ACTION_STATE_CHANGED already calls initializeHfpProxy()
            if (bluetoothAdapter.isEnabled() && hasBluetoothConnectPermission() && bluetoothHeadset == null) {
                // initializeHfpProxy(); // Called by ACTION_STATE_CHANGED if BT turns on
            }
        }
    }

    public boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                    getApplication(),
                    Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true; // Not needed for older versions
        }
    }

    public void onBluetoothConnectPermissionGranted() {
        Log.d(TAG, "BLUETOOTH_CONNECT granted by user, re-initializing HFP and updating state.");
        initializeHfpProxy(); // Try to get the proxy now that permission is granted
        updateState();        // Re-evaluate the overall state
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        try {
            getApplication().unregisterReceiver(bluetoothStateReceiver);
            Log.d(TAG, "Bluetooth receivers unregistered");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Receiver not registered or already unregistered: " + e.getMessage());
        }

        if (bluetoothAdapter != null && bluetoothHeadset != null) {
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset);
            bluetoothHeadset = null; // Clear the reference
            Log.d(TAG, "HFP proxy closed in onCleared");
        }
    }
}