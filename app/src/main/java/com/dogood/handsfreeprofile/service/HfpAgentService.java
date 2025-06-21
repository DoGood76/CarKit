package com.dogood.handsfreeprofile.service;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
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
            if (notificationHelper != null) { // Good practice to check, though it should be initialized
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
        bluetoothAclReceiver = new BluetoothAclReceiver();


        bluetoothStateReceiver.registerReceiver(this);
        bluetoothAclReceiver.registerReceiver(this);

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.");
            hfpStateRepository.reportServiceError("Bluetooth not supported on this device.");
            hfpStateRepository.reportBluetoothEnabled(false);
            hfpStateRepository.reportBluetoothPermissionsGranted(false);
            stopSelf();
            return;
        }

        boolean btEnabled = bluetoothAdapter.isEnabled();
        hfpStateRepository.reportBluetoothEnabled(btEnabled);

        boolean permsGranted = BluetoothUtils.hasRequiredPermissions(this);

        if (btEnabled && permsGranted) {
            //Fix for the permission check
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
        updateInitialState();
        Log.i(TAG, "HfpAgentService created. Initial state: " + hfpStateRepository.getCurrentUiStateValue());
    }

    private void updateInitialState() {
        boolean btEnabled = Boolean.TRUE.equals(hfpStateRepository.getIsBluetoothEnabledValue());
        boolean permsGranted = Boolean.TRUE.equals(hfpStateRepository.getHasBluetoothPermissionsValue());

        if (!btEnabled) {
            hfpStateRepository.reportBluetoothOff();
        } else if (!permsGranted) {
            hfpStateRepository.reportNoBluetoothPermission();
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
        processAtCommand(command);
    }

    private void processAtCommand(String receivedCommand) {
        if (receivedCommand.startsWith("AT+CLIP=")) {
            String number = extractClipNumber(receivedCommand);
            hfpStateRepository.setIncomingCallNumber(number);
            notificationHelper.updateNotification("Incoming call: " + number);
            sendAtCommand("OK\r\n");
        } else if (receivedCommand.equals("ATA")) {
            if (hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_INCOMING) {
                Log.i(TAG, "AG answered call (ATA received). HF should now manage call audio.");
                hfpStateRepository.updateUiState(CarKitUiState.CALL_IN_PROGRESS);
                notificationHelper.updateNotification("Call in progress with " + BluetoothUtils.getSafeDeviceName(this, connectedClientThread.mmSocket.getRemoteDevice()));
                hfpStateRepository.clearIncomingCallNumber();
                sendAtCommand("OK\r\n");
            } else {
                sendAtCommand("ERROR\r\n");
            }
        } else if (receivedCommand.equals("AT+CHUP")) {
            Log.i(TAG, "AG hung up call (AT+CHUP received).");
            hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTED);
            notificationHelper.updateNotification("Connected to " + BluetoothUtils.getSafeDeviceName(this, connectedClientThread.mmSocket.getRemoteDevice()));
            hfpStateRepository.clearIncomingCallNumber();
            sendAtCommand("OK\r\n");
        } else if (receivedCommand.startsWith("AT+VGS=")) {
            try {
                int level = Integer.parseInt(receivedCommand.substring("AT+VGS=".length()).trim());
                currentHfpVolume = Math.max(0, Math.min(15, level));
                Log.d(TAG, "Remote AG set HF speaker volume to: " + currentHfpVolume);
                sendAtCommand("OK\r\n");
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid VGS volume: " + receivedCommand);
                sendAtCommand("ERROR\r\n");
            }
        } else {
            Log.w(TAG, "Unhandled AT command: " + receivedCommand + ". Sending OK as default.");
            sendAtCommand("OK\r\n");
        }
    }

    private void sendAtCommand(String command) {
        if (connectedClientThread != null) {
            connectedClientThread.sendAtCommand(command);
        }
    }

    private String extractClipNumber(String clipCommand) {
        try {
            int firstQuote = clipCommand.indexOf("\"");
            if (firstQuote != -1) {
                int secondQuote = clipCommand.indexOf("\"", firstQuote + 1);
                if (secondQuote != -1) {
                    return clipCommand.substring(firstQuote + 1, secondQuote);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting CLIP number from: " + clipCommand, e);
        }
        return "Unknown Number";
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

    // --- Public methods callable via Binder from ViewModel/Activity ---

    public void requestStartListening() {
        Log.d(TAG, "requestStartListening called via Binder.");
        if (bluetoothAdapter == null) {
            hfpStateRepository.reportServiceError("Bluetooth not supported.");
            return;
        }
        updateInitialState();
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
                        hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_INCOMING)) {
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