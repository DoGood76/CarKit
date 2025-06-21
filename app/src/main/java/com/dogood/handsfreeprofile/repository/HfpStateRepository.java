package com.dogood.handsfreeprofile.repository; // Or your chosen package

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import android.util.Log;

import com.dogood.handsfreeprofile.model.CarKitUiState; // Ensure this path is correct

/**
 * Repository for managing and exposing the state of the HFP Car Kit service.
 * This acts as a single source of truth for UI-related HFP state.
 * It's updated by HfpAgentService and observed by ViewModels.
 *
 * This class is a Singleton.
 */
public class HfpStateRepository {
    private static final String TAG = "HfpStateRepository";
    private static volatile HfpStateRepository INSTANCE;

    // --- LiveData for UI State ---
    private final MutableLiveData<CarKitUiState> _currentUiState = new MutableLiveData<>();
    public final LiveData<CarKitUiState> currentUiState = _currentUiState;

    // --- LiveData for Connected Device Info ---
    private final MutableLiveData<String> _connectedDeviceName = new MutableLiveData<>();
    public final LiveData<String> connectedDeviceName = _connectedDeviceName; // For ViewModel

    private final MutableLiveData<String> _connectedDeviceAddress = new MutableLiveData<>();
    public final LiveData<String> connectedDeviceAddress = _connectedDeviceAddress; // For ViewModel

    // Kept for backward compatibility or if you still want a combined display string somewhere
    private final MutableLiveData<String> _connectedDeviceDisplay = new MutableLiveData<>();
    public final LiveData<String> connectedDeviceDisplay = _connectedDeviceDisplay;


    // --- LiveData for Call State ---
    private final MutableLiveData<String> _incomingCallNumber = new MutableLiveData<>();
    public final LiveData<String> incomingCallNumber = _incomingCallNumber;

    // --- LiveData for Service Status & Errors ---
    private final MutableLiveData<String> _serviceErrorMessage = new MutableLiveData<>();
    public final LiveData<String> serviceErrorMessage = _serviceErrorMessage; // For ViewModel

    private final MutableLiveData<Boolean> _isBluetoothEnabled = new MutableLiveData<>();
    public final LiveData<Boolean> isBluetoothEnabled = _isBluetoothEnabled; // For ViewModel

    private final MutableLiveData<Boolean> _hasBluetoothPermissions = new MutableLiveData<>();
    public final LiveData<Boolean> hasBluetoothPermissions = _hasBluetoothPermissions; // For ViewModel


    // Private constructor for singleton pattern
    private HfpStateRepository() {
        Log.d(TAG, "HfpStateRepository instance created.");
        // Initialize with default states. The service should update these upon its own initialization.
        _currentUiState.setValue(CarKitUiState.LOADING);
        _isBluetoothEnabled.setValue(false); // Assume BT is off until service confirms
        _hasBluetoothPermissions.setValue(false); // Assume no perms until service confirms
        _connectedDeviceName.setValue(null);
        _connectedDeviceAddress.setValue(null);
        _connectedDeviceDisplay.setValue(null);
        _incomingCallNumber.setValue(null);
        _serviceErrorMessage.setValue(null);
    }

    public static HfpStateRepository getInstance() {
        if (INSTANCE == null) {
            synchronized (HfpStateRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new HfpStateRepository();
                }
            }
        }
        return INSTANCE;
    }

    // --- Methods called by HfpAgentService to update the state ---

    public void updateUiState(CarKitUiState newState) {
        if (_currentUiState.getValue() != newState) {
            Log.i(TAG, "Updating UI State from " + _currentUiState.getValue() + " to: " + newState);
            _currentUiState.postValue(newState);
        } else {
            Log.d(TAG, "UI State is already " + newState + ". No update.");
        }
    }

    public void setConnectedDevice(String deviceName, String deviceAddress) {
        String nameToPost = (deviceName != null && !deviceName.isEmpty()) ? deviceName : null;
        String addressToPost = (deviceAddress != null && !deviceAddress.isEmpty()) ? deviceAddress : null;

        _connectedDeviceName.postValue(nameToPost);
        _connectedDeviceAddress.postValue(addressToPost);

        // Update the combined display string
        String displayInfo;
        if (nameToPost != null) {
            displayInfo = nameToPost;
        } else if (addressToPost != null) {
            displayInfo = addressToPost;
        } else {
            displayInfo = "Unknown Device"; // Or null if you prefer no display when both are null
        }
        _connectedDeviceDisplay.postValue(displayInfo); // Keep this if still used

        Log.i(TAG, "Setting connected device. Name: " + nameToPost + ", Address: " + addressToPost + ", Display: " + displayInfo);
        // Typically, when a device connects, Bluetooth is enabled and permissions are granted
        _isBluetoothEnabled.postValue(true);
        _hasBluetoothPermissions.postValue(true);
        // If there was an error, connecting a device usually means the error is resolved
        clearServiceError();
    }

    public void clearConnectedDevice() {
        boolean changed = false;
        if (_connectedDeviceName.getValue() != null) {
            _connectedDeviceName.postValue(null);
            changed = true;
        }
        if (_connectedDeviceAddress.getValue() != null) {
            _connectedDeviceAddress.postValue(null);
            changed = true;
        }
        if (_connectedDeviceDisplay.getValue() != null) {
            _connectedDeviceDisplay.postValue(null); // Keep this if still used
            changed = true;
        }
        if (changed) {
            Log.i(TAG, "Clearing connected device information.");
        }
    }

    public void setIncomingCallNumber(String number) {
        String callInfo = (number != null && !number.isEmpty()) ? number : "Unknown Number";
        Log.i(TAG, "Setting incoming call number: " + callInfo);
        _incomingCallNumber.postValue(callInfo);
        updateUiState(CarKitUiState.CALL_INCOMING);
    }

    public void clearIncomingCallNumber() {
        if (_incomingCallNumber.getValue() != null) {
            Log.i(TAG, "Clearing incoming call number.");
            _incomingCallNumber.postValue(null);
        }
    }

    // --- Convenience methods for specific state changes often triggered by service events ---

    public void reportBluetoothEnabled(boolean isEnabled) {
        if (_isBluetoothEnabled.getValue() == null || _isBluetoothEnabled.getValue() != isEnabled) {
            Log.i(TAG, "Reporting Bluetooth Enabled: " + isEnabled);
            _isBluetoothEnabled.setValue(isEnabled);
            if (!isEnabled) {
                // If Bluetooth is turned off, update relevant states
                updateUiState(CarKitUiState.BLUETOOTH_OFF);
                clearConnectedDevice();
                clearIncomingCallNumber();
                // We don't necessarily lose permissions when BT is off,
                // but the service might not be functional.
            } else {
                // If BT is turned on, and we previously had a BT_OFF state,
                // we might go to LOADING or check permissions again.
                // The service will likely call updateUiState directly.
                if (_currentUiState.getValue() == CarKitUiState.BLUETOOTH_OFF){
                    // Let service decide the next state (LOADING, NO_PERMISSION, LISTENING)
                }
            }
        }
    }

    public void reportBluetoothPermissionsGranted(boolean granted) {
        if (_hasBluetoothPermissions.getValue() == null || _hasBluetoothPermissions.getValue() != granted) {
            Log.i(TAG, "Reporting Bluetooth Permissions Granted: " + granted);
            _hasBluetoothPermissions.setValue(granted);
            if (!granted) {
                updateUiState(CarKitUiState.NO_BLUETOOTH_PERMISSION);
                clearConnectedDevice();
                clearIncomingCallNumber();
            } else {
                // If permissions are granted, and we were in NO_BLUETOOTH_PERMISSION state,
                // the service will likely transition to LOADING or LISTENING.
                if (_currentUiState.getValue() == CarKitUiState.NO_BLUETOOTH_PERMISSION){
                    // Let service decide the next state
                }
            }
        }
    }

    // Overload or modify existing reportBluetoothOff & reportNoBluetoothPermission
    // to also update the boolean LiveData
    public void reportBluetoothOff() {
        Log.i(TAG, "Reporting Bluetooth OFF (convenience method).");
        reportBluetoothEnabled(false); // This will update _isBluetoothEnabled and other states
        // updateUiState(CarKitUiState.BLUETOOTH_OFF); // Already handled in reportBluetoothEnabled
        // clearConnectedDevice(); // Already handled in reportBluetoothEnabled
        // clearIncomingCallNumber(); // Already handled in reportBluetoothEnabled
    }

    public void reportNoBluetoothPermission() {
        Log.w(TAG, "Reporting No Bluetooth Permission (convenience method).");
        reportBluetoothPermissionsGranted(false); // This will update _hasBluetoothPermissions and other states
        // updateUiState(CarKitUiState.NO_BLUETOOTH_PERMISSION); // Handled in reportBluetoothPermissionsGranted
        // clearConnectedDevice(); // Handled in reportBluetoothPermissionsGranted
        // clearIncomingCallNumber(); // Handled in reportBluetoothPermissionsGranted
    }


    public void reportServiceError(String errorMessage) {
        Log.e(TAG, "Reporting Service Error: " + errorMessage);
        _serviceErrorMessage.postValue(errorMessage);
        updateUiState(CarKitUiState.SERVICE_ERROR);
        // Depending on the error, you might want to clear device/call info
        // clearConnectedDevice();
        // clearIncomingCallNumber();
    }

    public void clearServiceError() {
        if (_serviceErrorMessage.getValue() != null) {
            Log.i(TAG, "Clearing service error message.");
            _serviceErrorMessage.postValue(null);
            // If the error is cleared, the UI state might need to revert
            // to a previous non-error state. This depends on your app's logic.
            // For example, if the error was transient and things are now OK:
            // if (_currentUiState.getValue() == CarKitUiState.SERVICE_ERROR) {
            //    // Re-evaluate current actual state or go to a sensible default like LISTENING
            // }
        }
    }

    // --- Getters for current values (mostly for service internal checks, UI should observe LiveData) ---

    public CarKitUiState getCurrentUiStateValue() {
        return _currentUiState.getValue();
    }

    public String getConnectedDeviceNameValue() {
        return _connectedDeviceName.getValue();
    }

    public String getConnectedDeviceAddressValue() { // Added getter
        return _connectedDeviceAddress.getValue();
    }

    public String getConnectedDeviceDisplayValue() { // Getter for the combined display
        return _connectedDeviceDisplay.getValue();
    }

    public String getIncomingCallNumberValue() {
        return _incomingCallNumber.getValue();
    }

    public String getServiceErrorMessageValue() { // Added getter
        return _serviceErrorMessage.getValue();
    }

    public Boolean getIsBluetoothEnabledValue() { // Added getter
        return _isBluetoothEnabled.getValue();
    }

    public Boolean getHasBluetoothPermissionsValue() { // Added getter
        return _hasBluetoothPermissions.getValue();
    }
}