package com.dogood.handsfreeprofile.service;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.dogood.handsfreeprofile.connection.AcceptThread;
import com.dogood.handsfreeprofile.connection.ConnectedClientThread;
import com.dogood.handsfreeprofile.model.CarKitUiState;
import com.dogood.handsfreeprofile.receiver.BluetoothAclReceiver;
import com.dogood.handsfreeprofile.receiver.BluetoothStateReceiver;
import com.dogood.handsfreeprofile.repository.HfpStateRepository;
import com.dogood.handsfreeprofile.util.BluetoothUtils;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * HfpAgentService is a foreground service that manages Bluetooth Hands-Free Profile (HFP)
 * connections. It acts as a Hands-Free (HF) unit, allowing a connected Audio Gateway (AG),
 * typically a mobile phone, to route audio and control calls through this service.
 *
 * <p>Key responsibilities include:
 * <ul>
 *   <li>Initializing Bluetooth and checking for necessary permissions.</li>
 *   <li>Starting a Bluetooth server socket to listen for incoming HFP connections from AG devices.</li>
 *   <li>Managing the lifecycle of a single HFP connection at a time using {@link AcceptThread} for listening and {@link ConnectedClientThread} for an active connection.</li>
 *   <li>Processing AT commands received from the connected AG via {@link AtCommandProcessor}.</li>
 *   <li>Sending AT commands to the AG to control call states (e.g., answer, hang up) and manage volume.</li>
 *   <li>Maintaining and updating the HFP connection state (e.g., listening, connecting, connected, in-call) in {@link HfpStateRepository}.</li>
 *   <li>Displaying a persistent notification to indicate the service's status and current HFP state.</li>
 *   <li>Responding to changes in Bluetooth adapter state (e.g., enabled/disabled) and ACL connection events via {@link BluetoothStateReceiver} and {@link BluetoothAclReceiver}.</li>
 *   <li>Providing an interface for UI components (e.g., an Activity) to bind to the service and interact with it (e.g., initiate call actions, request state updates) via {@link LocalBinder}.</li>
 * </ul>
 *
 * <p>The service uses an {@link ExecutorService} to manage background threads for Bluetooth communication.
 * It ensures that only one HFP connection is active at a time. If a new connection request is received while an
 * existing one is active, the old connection is dropped to establish the new one.
 *
 * <p>The service also handles cleanup of resources and threads upon being destroyed or when connections are closed.
 * It interacts with {@link NotificationHelper} to manage the foreground service notification.
 *
 * <p>Permissions Required:
 * <ul>
 *   <li>{@link Manifest.permission#BLUETOOTH_CONNECT} (for Android S and above) for establishing connections.</li>
 */
public class HfpAgentService extends Service implements AcceptThread.AcceptThreadCallback, ConnectedClientThread.ConnectedClientCallback {

    private static final String TAG = "HfpAgentService";
    private static final String CHANNEL_ID = "HfpAgentForegroundChannel";
    private static final int NOTIFICATION_ID = 123;
    private static final String HFP_SERVICE_NAME = "Handsfree";
    private static final UUID HFP_HF_UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb");
    private final IBinder localBinder = new LocalBinder();
    private HfpStateRepository hfpStateRepository;
    private BluetoothAdapter bluetoothAdapter;
    private NotificationHelper notificationHelper;
    private ExecutorService executorService;
    private AcceptThread acceptThread;
    private ConnectedClientThread connectedClientThread;
    private int currentHfpVolume = 8;
    private BluetoothStateReceiver bluetoothStateReceiver;
    private BluetoothAclReceiver bluetoothAclReceiver;
    private AtCommandProcessor atCommandProcessor;

    public class LocalBinder extends Binder {
        public HfpAgentService getService() {
            return HfpAgentService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called");
        return localBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");

        // Initialize NotificationHelper HERE, before using it
        // Pass the application context or 'this' (which is a Context)
        notificationHelper = new NotificationHelper(this);

        // Now it's safe to call methods on notificationHelper
        try {
            if (notificationHelper != null) {
                notificationHelper.updateNotification("Service is starting");
            } else {
                Log.e(TAG, "NotificationHelper is still null after attempted initialization!");
            }
        } catch (Exception e) {
            // Log any other unexpected exceptions during notification update
            Log.e(TAG, "Error updating notification in onCreate", e);
            //close the service if notificationHelper fails
            hfpStateRepository.reportServiceError("Failed to initialize notification system: " + e.getMessage());
            stopSelf();
        }

        hfpStateRepository = HfpStateRepository.getInstance();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        executorService = Executors.newCachedThreadPool();

        bluetoothStateReceiver = new BluetoothStateReceiver(hfpStateRepository, notificationHelper, this);
        bluetoothStateReceiver.registerReceiver(this);

        bluetoothAclReceiver = new BluetoothAclReceiver();
        bluetoothAclReceiver.registerReceiver(this);

        atCommandProcessor = new AtCommandProcessor(hfpStateRepository, notificationHelper, this);

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.");
            hfpStateRepository.reportServiceError("Bluetooth not supported on this device.");
            // Ensure repository reflects BT not enabled if adapter is null
            hfpStateRepository.reportBluetoothEnabled(false);
            hfpStateRepository.reportBluetoothPermissionsGranted(false);
            updateInitialState(false, BluetoothUtils.hasRequiredPermissions(this)); // Pass current states
            stopSelf();
            return;
        }

        // --- Get ACTUAL current states directly ---
        boolean currentBtEnabled = bluetoothAdapter.isEnabled();
        boolean currentPermsGranted = BluetoothUtils.hasRequiredPermissions(this);

        // --- Update repository with these ACTUAL current states ---
        hfpStateRepository.reportBluetoothEnabled(currentBtEnabled);
        hfpStateRepository.reportBluetoothPermissionsGranted(currentPermsGranted);

        // --- Now make decisions based on these ACTUAL current states ---
        updateInitialState(currentBtEnabled, currentPermsGranted);

        if (currentBtEnabled && currentPermsGranted) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
                for (BluetoothDevice device : bondedDevices) {
                    String deviceName = BluetoothUtils.getSafeDeviceName(this, device);
                    String deviceAddress = device.getAddress();
                    hfpStateRepository.setConnectedDevice(deviceName, deviceAddress);
                    break;
                }
            }
        }
        Log.i(TAG, "HfpAgentService created. Initial state: " + hfpStateRepository.getCurrentUiStateValue());
    }

    /**
     * Updates the initial state of the HFP service based on the current Bluetooth status and
     * permission status obtained from {@link HfpStateRepository}.
     *
     * <p>This method performs the following actions:
     * <ul>
     *   <li>If Bluetooth is reported as disabled in the repository, it calls
     *       {@link HfpStateRepository#reportBluetoothOff()} to update the UI and notification.</li>
     *   <li>Else if Bluetooth permissions are reported as not granted in the repository, it calls
     *       {@link HfpStateRepository#reportNoBluetoothPermission()} to update the UI and notification.</li>
     *   <li>Otherwise (Bluetooth is enabled and permissions are granted), it sets the UI state to
     *       {@link CarKitUiState#LOADING} via {@link HfpStateRepository#updateUiState(CarKitUiState)}
     *       and then calls {@link #startHfpServerListening()} to begin listening for HFP connections.</li>
     * </ul>
     * </p>
     * This method is typically called during service initialization or when a significant change
     * in Bluetooth state or permissions is detected, requiring a re-evaluation of the service's
     * operational readiness.
     */
    private void updateInitialState(boolean isBluetoothCurrentlyEnabled, boolean arePermissionsCurrentlyGranted) {
        if (!isBluetoothCurrentlyEnabled) {
            hfpStateRepository.reportBluetoothOff(); // Updates UI state and notification via repository
        } else if (!arePermissionsCurrentlyGranted) {
            hfpStateRepository.reportNoBluetoothPermission(); // Updates UI state and notification
        } else {
            hfpStateRepository.updateUiState(CarKitUiState.LOADING);
            startHfpServerListening();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        if (notificationHelper != null) {
            startForeground(NOTIFICATION_ID, notificationHelper.createNotification("Initializing..."));
        }
        return START_STICKY;
    }

    /**
     * Starts the HFP server socket to listen for incoming Bluetooth connections from AG devices.
     * <p>
     * This method performs the following checks before attempting to start listening:
     * <ul>
     *   <li>Verifies if Bluetooth is enabled using {@link HfpStateRepository}. If not, logs a warning and updates the notification.</li>
     *   <li>Verifies if the required Bluetooth permissions are granted using {@link HfpStateRepository}. If not, logs a warning and updates the notification.</li>
     * </ul>
     * </p>
     * <p>
     * If both checks pass, it proceeds to:
     * <ol>
     *   <li>Stop any existing listening ({@link AcceptThread}) or connection ({@link ConnectedClientThread}) threads.</li>
     *   <li>If running on Android S (API 31) or higher, explicitly checks for {@link Manifest.permission#BLUETOOTH_CONNECT}.
     *       If missing, logs an error, updates the {@link HfpStateRepository} with a permission error, and updates the notification.</li>
     *   <li>Attempts to create a {@link BluetoothServerSocket} using {@code listenUsingRfcommWithServiceRecord} with the HFP service name and UUID.</li>
     *   <li>If successful, creates a new {@link AcceptThread} with the server socket and submits it to the {@link ExecutorService} to start listening.</li>
     *   <li>Updates the UI state in {@link HfpStateRepository} to {@link CarKitUiState#LISTENING_FOR_CONNECTIONS}.</li>
     *   <li>Updates the persistent notification to indicate that the service is listening.</li>
     * </ol>
     * </p>
     * <p>
     * If an {@link IOException} occurs during server socket creation (e.g., Bluetooth turned off, port in use),
     * it logs the error, reports a service error to {@link HfpStateRepository}, and updates the notification.
     * If a {@link SecurityException} occurs (usually due to missing permissions), it logs the error,
     * reports a permission issue and a service error to {@link HfpStateRepository}, and updates the notification.
     * </p>
     */
    private void startHfpServerListening() {
        if (!Boolean.TRUE.equals(hfpStateRepository.getIsBluetoothEnabledValue())) {
            Log.w(TAG, "Bluetooth is not enabled (checked via repository). Cannot start HFP server.");
            notificationHelper.updateNotification("Bluetooth is OFF");
            return;
        }
        if (!Boolean.TRUE.equals(hfpStateRepository.getHasBluetoothPermissionsValue())) {
            Log.w(TAG, "Required Bluetooth permissions not granted (checked via repository).");
            notificationHelper.updateNotification("Bluetooth permissions needed");
            return;
        }

        stopListeningAndConnectionThreads();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing for server socket (listenUsingRfcommWithServiceRecord).");
                hfpStateRepository.reportNoBluetoothPermission();
                notificationHelper.updateNotification("Bluetooth permissions needed");
                return;
            }

            BluetoothServerSocket tempServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(HFP_SERVICE_NAME, HFP_HF_UUID);
            acceptThread = new AcceptThread(this, tempServerSocket, this);
            executorService.submit(acceptThread);
            hfpStateRepository.updateUiState(CarKitUiState.LISTENING_FOR_CONNECTIONS);
            notificationHelper.updateNotification("Listening for phone connections");
            Log.i(TAG, "HFP Server started, listening for connections on new AcceptThread.");
        } catch (IOException e) {
            Log.e(TAG, "IOException during listenUsingRfcommWithServiceRecord: " + e.getMessage(), e);
            hfpStateRepository.reportServiceError("Failed to start HFP server: " + e.getMessage());
            notificationHelper.updateNotification("Error starting service");
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException during listenUsingRfcommWithServiceRecord: " + se.getMessage(), se);
            hfpStateRepository.reportNoBluetoothPermission();
            hfpStateRepository.reportServiceError("Permission issue starting HFP server: " + se.getMessage());
            notificationHelper.updateNotification("Bluetooth permissions needed");
        }
    }

    /**
     * Stops and cancels the {@link AcceptThread} (if active) and the {@link ConnectedClientThread}
     * (if active). This is typically called before starting a new listening cycle or when the
     * service is being destroyed. It ensures that any existing Bluetooth server socket listening
     * for connections and any active client connection are properly terminated.
     */
    private void stopListeningAndConnectionThreads() {
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
            Log.d(TAG, "AcceptThread stopped.");
        }
        if (connectedClientThread != null) {
            connectedClientThread.cancel();
            connectedClientThread = null;
            Log.d(TAG, "ConnectedClientThread stopped.");
        }
    }

    private synchronized void handleNewConnection(BluetoothSocket socket) {
        Log.i(TAG, "New connection attempt from: " + socket.getRemoteDevice().getAddress());

        if (connectedClientThread != null) {
            Log.w(TAG, "Existing connection found. Closing it before accepting new one.");
            connectedClientThread.cancel();
            connectedClientThread = null;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
        hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTING);

        String deviceName = BluetoothUtils.getSafeDeviceName(this, socket.getRemoteDevice());
        String deviceAddress = socket.getRemoteDevice().getAddress();
        hfpStateRepository.setConnectedDevice(deviceName, deviceAddress);

        connectedClientThread = new ConnectedClientThread(this, socket, this);
        atCommandProcessor.setConnectedClientThread(connectedClientThread);
        executorService.submit(connectedClientThread);
    }

    @Override
    public void onConnectionAccepted(BluetoothSocket socket) {
        Log.i(TAG, "Connection accepted.");
        handleNewConnection(socket);
    }

    @Override
    public void onAcceptThreadStopped() {
        Log.i(TAG, "AcceptThread stopped.");
    }

    @Override
    public void onAtCommandReceived(String command) {
        Log.i(TAG, "AT command received: " + command);
        atCommandProcessor.processAtCommand(command);
    }

    @Override
    public void onConnectionClosed() {
        Log.i(TAG, "Client connection closed.");
        cleanupConnection();
    }

    private void cleanupConnection() {
        String deviceIdentifier = (connectedClientThread != null && connectedClientThread.mmSocket != null && connectedClientThread.mmSocket.getRemoteDevice() != null)
                ? BluetoothUtils.getSafeDeviceName(this, connectedClientThread.mmSocket.getRemoteDevice())
                : "disconnected socket";

        if (connectedClientThread != null && HfpAgentService.this.connectedClientThread == connectedClientThread) {
            Log.i(TAG, "Cleaning up active connection for " + deviceIdentifier);
            hfpStateRepository.clearConnectedDevice();
            hfpStateRepository.clearIncomingCallNumber();

            boolean btStillEnabled = Boolean.TRUE.equals(hfpStateRepository.getIsBluetoothEnabledValue());
            boolean permsStillGranted = Boolean.TRUE.equals(hfpStateRepository.getHasBluetoothPermissionsValue());

            if (btStillEnabled && permsStillGranted) {
                hfpStateRepository.updateUiState(CarKitUiState.LISTENING_FOR_CONNECTIONS);
                notificationHelper.updateNotification("Listening for phone connections");
                if (acceptThread == null || !acceptThread.isRunning()) {
                    Log.i(TAG, "Cleanup: Restarting HFP server listening.");
                    startHfpServerListening();
                }
            } else if (!btStillEnabled) {
                hfpStateRepository.reportBluetoothOff();
            } else {
                hfpStateRepository.reportNoBluetoothPermission();
            }
            HfpAgentService.this.connectedClientThread = null;
            atCommandProcessor.clearConnectedClientThread();
        } else {
            Log.d(TAG, "cleanupConnection called for an old or non-active thread instance for " + deviceIdentifier);
        }
    }

    public void startHfpServer() {
        startHfpServerListening();
    }

    public void stopHfpServer() {
        stopListeningAndConnectionThreads();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "HfpAgentService onDestroy called.");
        stopListeningAndConnectionThreads();
        bluetoothStateReceiver.unregisterReceiver(this);
        bluetoothAclReceiver.unregisterReceiver(this);
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        Log.i(TAG, "HfpAgentService destroyed.");
    }

//    public ConnectedClientThread getConnectedClientThread() {
//        return connectedClientThread;
//    }

    public void requestStartListening() {
        Log.d(TAG, "requestStartListening called via Binder.");
        if (bluetoothAdapter == null) {
            hfpStateRepository.reportServiceError("Bluetooth not supported.");
            return;
        }
        updateInitialState(bluetoothAdapter.isEnabled(),
                BluetoothUtils.hasRequiredPermissions(this));
    }

    public void commandAnswerCall() {
        Log.d(TAG, "commandAnswerCall called via Binder.");
        if (connectedClientThread != null && hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_INCOMING) {
            Log.i(TAG, "HF answering call (sending ATA).");
            connectedClientThread.sendAtCommand("ATA\r\n");
            hfpStateRepository.updateUiState(CarKitUiState.CALL_IN_PROGRESS);
            if (connectedClientThread.mmSocket != null && connectedClientThread.mmSocket.getRemoteDevice() != null) {
                notificationHelper.updateNotification("Call in progress with " + BluetoothUtils.getSafeDeviceName(this, connectedClientThread.mmSocket.getRemoteDevice()));
            } else {
                notificationHelper.updateNotification("Call in progress");
            }
            hfpStateRepository.clearIncomingCallNumber();
        } else {
            Log.w(TAG, "Cannot answer call: No connected client or no incoming call.");
        }
    }

    public void commandHangupCall() {
        Log.d(TAG, "commandHangupCall called via Binder.");
        if (connectedClientThread != null &&
                (hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_IN_PROGRESS ||
                        hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_IN_COMING)) {
            Log.i(TAG, "HF hanging up/rejecting call (sending AT+CHUP).");
            connectedClientThread.sendAtCommand("AT+CHUP\r\n");
            hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTED);
            if (connectedClientThread.mmSocket != null && connectedClientThread.mmSocket.getRemoteDevice() != null) {
                notificationHelper.updateNotification("Connected to " + BluetoothUtils.getSafeDeviceName(this, connectedClientThread.mmSocket.getRemoteDevice()));
            } else {
                notificationHelper.updateNotification("Connected to device");
            }
            hfpStateRepository.clearIncomingCallNumber();
        } else {
            Log.w(TAG, "Cannot hangup call: No connected client or no active/incoming call.");
        }
    }

    public void commandSetSpeakerVolume(int level) {
        currentHfpVolume = Math.max(0, Math.min(15, level));
        Log.d(TAG, "commandSetSpeakerVolume called via Binder. Level: " + currentHfpVolume);
        if (connectedClientThread != null && hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.PHONE_CONNECTED) {
            connectedClientThread.sendAtCommand("AT+VGS=" + currentHfpVolume + "\r\n");
        } else {
            Log.w(TAG, "Cannot send VGS: No connected client.");
        }
    }

    public int getCurrentHfpSpeakerVolume() {
        return currentHfpVolume;
    }

    public void publishCurrentStateToRepository() {
        CarKitUiState currentState = hfpStateRepository.getCurrentUiStateValue();
        String currentDeviceName = hfpStateRepository.getConnectedDeviceNameValue();
        String currentDeviceAddress = hfpStateRepository.getConnectedDeviceAddressValue();

        hfpStateRepository.updateUiState(currentState);
        if (currentDeviceName != null) {
            hfpStateRepository.setConnectedDevice(currentDeviceName, currentDeviceAddress);
        } else {
            hfpStateRepository.clearConnectedDevice();
        }

        String currentIncomingCall = hfpStateRepository.getIncomingCallNumberValue();
        if (currentIncomingCall != null) {
            hfpStateRepository.setIncomingCallNumber(currentIncomingCall);
        } else {
            hfpStateRepository.clearIncomingCallNumber();
        }
        Log.d(TAG, "Published current state to repository: " + currentState);
    }
}