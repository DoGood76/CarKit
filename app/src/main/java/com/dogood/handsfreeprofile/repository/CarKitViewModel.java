package com.dogood.handsfreeprofile.repository; // Or your ViewModel's package

import android.app.Application;
import android.util.Log; // Standard Android Log

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.dogood.handsfreeprofile.service.HfpAgentService;
import com.dogood.handsfreeprofile.model.CarKitUiState;

import org.jetbrains.annotations.NotNull;
// Assuming HfpStateRepository is in this package, adjust if not
// import com.dogood.handsfreeprofile.repository.HfpStateRepository;


public class CarKitViewModel extends AndroidViewModel {
    private static final String TAG = "CarKitViewModel";

    private HfpStateRepository hfpStateRepository;

    // LiveData exposed to the UI, mirroring the Repository
    public final LiveData<CarKitUiState> uiState;
    public final LiveData<String> connectedDeviceName;
    public final LiveData<String> connectedDeviceAddress; // Added
    public final LiveData<String> incomingCallNumber;    // Added
    public final LiveData<String> serviceErrorMessage;   // Added
    public final LiveData<Boolean> isBluetoothEnabled;    // Added
    public final LiveData<Boolean> hasBluetoothPermissions; // Added


    // Reference to the service for sending commands (obtained via Activity binding)
    private HfpAgentService hfpAgentService;

    public CarKitViewModel(@NonNull Application application) {
        super(application);
        hfpStateRepository = HfpStateRepository.getInstance();

        // The ViewModel's LiveData simply mirrors the Repository's LiveData
        uiState = hfpStateRepository.currentUiState;
        connectedDeviceName = hfpStateRepository.connectedDeviceName;
        connectedDeviceAddress = hfpStateRepository.connectedDeviceAddress; // Assuming this exists in repo
        incomingCallNumber = hfpStateRepository.incomingCallNumber;         // Assuming this exists in repo
        serviceErrorMessage = hfpStateRepository.serviceErrorMessage;       // Assuming this exists in repo
        isBluetoothEnabled = hfpStateRepository.isBluetoothEnabled;         // Assuming this exists in repo
        hasBluetoothPermissions = hfpStateRepository.hasBluetoothPermissions; // Assuming this exists in repo

        Log.d(TAG, "CarKitViewModel initialized.");
    }

    public void setBoundService(HfpAgentService service) {
        this.hfpAgentService = service;
        if (hfpAgentService != null) {
            Log.d(TAG, "HfpAgentService bound to ViewModel.");
            // Ask the service to push its most current state to the repository.
            // This ensures the UI reflects the true state immediately after binding.
            hfpAgentService.publishCurrentStateToRepository();
        } else {
            Log.d(TAG, "HfpAgentService unbound from ViewModel (service is null).");
        }
    }

    // --- Methods to command the HfpAgentService ---

    /**
     * Requests the HFP service to start or restart listening for incoming connections.
     * This is useful if Bluetooth was off or permissions were missing and are now resolved.
     */
    public void requestStartListeningForConnections() {
        if (hfpAgentService != null) {
            Log.d(TAG, "Requesting service to start listening.");
            hfpAgentService.requestStartListening();
        } else {
            Log.w(TAG, "Service not available to start listening. UI should reflect this via repository state.");
            // Optionally, update repository with a specific error/state if immediate feedback is needed
            // hfpStateRepository.reportServiceError("Cannot start listening: Service not connected.");
        }
    }

    /**
     * Commands the HFP service to answer an incoming call.
     */
    public void answerCall() {
        if (hfpAgentService != null && uiState.getValue() == CarKitUiState.CALL_INCOMING) {
            Log.d(TAG, "Commanding service to answer call.");
            hfpAgentService.commandAnswerCall();
        } else {
            if (hfpAgentService == null) {
                Log.w(TAG, "Service not available to answer call.");
            } else {
                Log.w(TAG, "Cannot answer call: Not in CALL_INCOMING state. Current state: " + uiState.getValue());
            }
        }
    }

    /**
     * Commands the HFP service to hang up the current call (active or incoming).
     */
    public void hangUpCall() {
        if (hfpAgentService != null &&
                (uiState.getValue() == CarKitUiState.CALL_INCOMING || uiState.getValue() == CarKitUiState.CALL_IN_PROGRESS)) {
            Log.d(TAG, "Commanding service to hang up/reject call.");
            hfpAgentService.commandHangupCall();
        } else {
            if (hfpAgentService == null) {
                Log.w(TAG, "Service not available to hang up call.");
            } else {
                Log.w(TAG, "Cannot hang up: No call incoming or in progress. Current state: " + uiState.getValue());
            }
        }
    }

    /**
     * Commands the HFP service to set the speaker volume (as perceived by the AG).
     * @param volumeLevel The desired volume level (0-15).
     */
    public void setSpeakerVolume(int volumeLevel) {
        if (hfpAgentService != null) {
            Log.d(TAG, "Commanding service to set speaker volume to: " + volumeLevel);
            hfpAgentService.commandSetSpeakerVolume(volumeLevel);
        } else {
            Log.w(TAG, "Service not available to set speaker volume.");
        }
    }

    /**
     * Gets the current HFP speaker volume known by the service.
     * Note: This is a direct call, not LiveData. If you need this to be observable,
     * the service should expose it via the repository.
     * @return current volume level (0-15), or a default/error if service not bound.
     */
    public int getCurrentSpeakerVolume() {
        if (hfpAgentService != null) {
            return hfpAgentService.getCurrentHfpSpeakerVolume();
        } else {
            Log.w(TAG, "Service not available to get speaker volume. Returning default -1.");
            return -1; // Or some other default indicator
        }
    }



    /**
     * Clears any displayed service error message from the repository.
     * Useful if the UI has a dismiss button for errors.
     */
    public void clearServiceError() {
        Log.d(TAG, "Clearing service error message from repository.");
        hfpStateRepository.clearServiceError(); // Assuming HfpStateRepository has this method
    }

    // You might not need a 'requestDiscoverable' if your HFP device (HF role)
    // primarily just listens. Discoverability is more for the AG (phone) or if your
    // device also supports other profiles where it needs to be found.
    // If HfpAgentService truly has 'makeDeviceDiscoverableForHfp()', keep it.
    // Otherwise, it can be removed or adapted. For now, I'll comment it out,
    // assuming 'requestStartListeningForConnections' is the primary way to initiate.

    /*
    public void requestDiscoverable() {
        if (hfpAgentService != null) {
            // Assuming hfpAgentService has this method
            // hfpAgentService.makeDeviceDiscoverableForHfp();
            Log.d(TAG, "Requesting service to become discoverable (if applicable).");
        } else {
            Log.w(TAG, "Service not available to make discoverable");
        }
    }
    */

    @Override
    protected void onCleared() {
        super.onCleared();
        Log.d(TAG, "CarKitViewModel onCleared. Service binding should be handled by Activity/Fragment lifecycle.");
        // No need to directly interact with service here, as the Activity/Fragment
        // that binds to the service is responsible for unbinding.
    }

    public void reportServiceErrorInRepository(@NotNull String errorMessage) {
        hfpStateRepository.reportServiceError(errorMessage);
    }

    /**
     * Updates the UI state directly in the HfpStateRepository.
     * This method is typically called by the HfpAgentService to reflect changes
     * in the HFP connection or call status. The UI then observes this state
     * via the LiveData exposed by this ViewModel (which mirrors the repository).
     *
     * @param newState The new CarKitUiState to set in the repository.
     */
    public void updateUiStateInRepository(@NotNull CarKitUiState newState) {
        hfpStateRepository.updateUiState(newState);
    }
}