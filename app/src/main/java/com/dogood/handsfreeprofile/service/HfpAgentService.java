package com.dogood.handsfreeprofile.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

// Assuming these are in the correct package, adjust if necessary
import com.dogood.handsfreeprofile.MainActivity;
import com.dogood.handsfreeprofile.R;
import com.dogood.handsfreeprofile.model.CarKitUiState;
import com.dogood.handsfreeprofile.repository.HfpStateRepository;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HfpAgentService is a foreground service that manages Bluetooth Hands-Free Profile (HFP)
 * connections. It acts as a Hands-Free (HF) device, allowing a remote Audio Gateway (AG),
 * typically a mobile phone, to connect and manage calls.
 *
 * <p>Key responsibilities include:</p>
 * <ul>
 *     <li>Running as a foreground service with a persistent notification.</li>
 *     <li>Managing Bluetooth adapter state (on/off, permissions).</li>
 *     <li>Listening for incoming Bluetooth connections from HFP AG devices.</li>
 *     <li>Handling a single active HFP connection at a time.</li>
 *     <li>Processing AT commands received from the connected AG (e.g., incoming call notifications,
 *         volume changes, call termination).</li>
 *     <li>Sending AT commands to the AG (e.g., answer call, hang up call, set speaker volume).</li>
 *     <li>Maintaining and updating the HFP connection state via {@link HfpStateRepository}.</li>
 *     <li>Providing a binder interface for UI components (e.g., Activity, ViewModel) to interact
 *         with the service (e.g., to initiate call actions, query state).</li>
 * </ul>
 *
 * <p>The service uses an {@link ExecutorService} to manage threads for listening for connections
 * ({@link AcceptThread}) and handling communication with a connected client ({@link ConnectedClientThread}).</p>
 *
 * <p>State changes, such as Bluetooth status, connection status, and call status, are reported
 * to {@link HfpStateRepository}, which in turn updates LiveData that UI components can observe.</p>
 *
 * <p><b>Foreground Service Notification:</b><br>
 * A notification is displayed while the service is running, indicating its current status (e.g.,
 * "Listening for phone connections", "Connected to [DeviceName]", "Incoming call: [Number]").
 * This notification is updated dynamically based on the service's state.
 * </p>
 *
 * <p><b>Permissions:</b><br>
 * Requires Bluetooth permissions (BLUETOOTH, BLUETOOTH_ADMIN for older Android versions;
 * BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, BLUETOOTH_SCAN for Android S and above).
 */
public class HfpAgentService extends Service {

    private static final String TAG = "HfpAgentService";
    private static final String CHANNEL_ID = "HfpAgentForegroundChannel";
    private static final int NOTIFICATION_ID = 123; // Unique ID for the foreground notification
    private static final String HFP_SERVICE_NAME = "AndroidCarKitHFP"; // Standard HFP service name
    // Standard HFP UUID for Hands-Free (HF) role
    private static final UUID HFP_HF_UUID = UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb");

    private final IBinder localBinder = new LocalBinder();
    private HfpStateRepository hfpStateRepository;
    private BluetoothAdapter bluetoothAdapter;

    // Thread management
    private ExecutorService executorService; // For managing connection threads
    private AcceptThread acceptThread;
    private ConnectedClientThread connectedClientThread; // Manages a single connected client's socket and I/O

    private int currentHfpVolume = 8; // Default volume (0-15)

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

// Inside HfpAgentService.java

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");
        hfpStateRepository = HfpStateRepository.getInstance();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        executorService = Executors.newCachedThreadPool();

        createNotificationChannel();
        registerBluetoothStateReceiver();

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device.");
            // MODIFICATION START
            hfpStateRepository.reportServiceError("Bluetooth not supported on this device.");
            hfpStateRepository.reportBluetoothEnabled(false);
            hfpStateRepository.reportBluetoothPermissionsGranted(false); // Permissions irrelevant but set a state
            // MODIFICATION END
            stopSelf();
            return;
        }

        // MODIFICATION START: Report initial BT and Permission state
        // This will be used by updateInitialState
        boolean btEnabled = bluetoothAdapter.isEnabled();
        hfpStateRepository.reportBluetoothEnabled(btEnabled);

        boolean permsGranted = hasRequiredPermissions(); // hasRequiredPermissions will now also update the repo
        // hfpStateRepository.reportBluetoothPermissionsGranted(permsGranted); // Call now made within hasRequiredPermissions

        updateInitialState(); // updateInitialState will now read these from the repository
        // MODIFICATION END
        Log.i(TAG, "HfpAgentService created. Initial state: " + hfpStateRepository.getCurrentUiStateValue());
    }

// Inside HfpAgentService.java

    private void updateInitialState() {
        // MODIFICATION START: Read from repository
        boolean btEnabled = Boolean.TRUE.equals(hfpStateRepository.getIsBluetoothEnabledValue());
        boolean permsGranted = Boolean.TRUE.equals(hfpStateRepository.getHasBluetoothPermissionsValue());

        if (!btEnabled) {
            hfpStateRepository.reportBluetoothOff(); // This method in repo also updates UI state etc.
        } else if (!permsGranted) {
            hfpStateRepository.reportNoBluetoothPermission(); // This method in repo also updates UI state etc.
        } else {
            // MODIFICATION END
            hfpStateRepository.updateUiState(CarKitUiState.LOADING);
            startHfpServerListening();
        }
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        startForeground(NOTIFICATION_ID, createServiceNotification("Initializing..."));
        return START_STICKY;
    }

    private Notification createServiceNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class); // Replace MainActivity
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("HFP Car Kit Active")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_stat_bluetooth_hfp) // Ensure this drawable exists
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createServiceNotification(contentText));
        }
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "HFP Agent Foreground Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Notification channel for HFP Car Kit service.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created.");
            }
        }
    }

// Inside HfpAgentService.java

    private boolean hasRequiredPermissions() {
        boolean granted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        } else {
            // For older versions, BLUETOOTH and BLUETOOTH_ADMIN are granted at install time.
            // Assuming classic Bluetooth server operations primarily need these.
            granted = true;
        }
        // MODIFICATION START
        hfpStateRepository.reportBluetoothPermissionsGranted(granted);
        // MODIFICATION END
        return granted;
    }

    // --- START: Helper method to safely get device name for notifications ---
    private String getSafeDeviceNameForNotification(BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "getSafeDeviceNameForNotification called with null device.");
            return "Unknown Device"; // Or any other placeholder you prefer
        }
        String address = device.getAddress(); // Address is always available without special permission
        String nameToDisplay = address;    // Default to address

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            try {
                String retrievedName = device.getName();
                if (retrievedName != null && !retrievedName.isEmpty()) {
                    nameToDisplay = retrievedName;
                }
            } catch (SecurityException se) {
                // This catch is a fallback, though checkSelfPermission should prevent it.
                Log.w(TAG, "SecurityException getting device name for " + address + ": " + se.getMessage());
                // nameToDisplay remains the address
            }
        } else {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for " + address + ". Using address for notification.");
            // nameToDisplay is already the address
        }
        return nameToDisplay;
    }
    // --- END: Helper method ---

    // Inside HfpAgentService.java

    private void startHfpServerListening() {
        // MODIFICATION START: Check repository's view of state
        if (!Boolean.TRUE.equals(hfpStateRepository.getIsBluetoothEnabledValue())) {
            // The repository's reportBluetoothOff() method (called if value is false)
            // should handle setting the UI state to BLUETOOTH_OFF.
            // We just ensure the notification reflects this if we try to start listening.
            Log.w(TAG, "Bluetooth is not enabled (checked via repository). Cannot start HFP server.");
            updateNotification("Bluetooth is OFF");
            // No need to call hfpStateRepository.reportBluetoothOff() here again,
            // as it should have been set when reportBluetoothEnabled(false) was called.
            return;
        }
        if (!Boolean.TRUE.equals(hfpStateRepository.getHasBluetoothPermissionsValue())) {
            Log.w(TAG, "Required Bluetooth permissions not granted (checked via repository).");
            updateNotification("Bluetooth permissions needed");
            // Similar to above, reportNoBluetoothPermission() in repo should handle UI state.
            return;
        }
        // MODIFICATION END

        stopListeningAndConnectionThreads();

        try {
            // This explicit check is a safeguard, hasRequiredPermissions() should have already been called.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission missing for server socket (listenUsingRfcommWithServiceRecord).");
                hfpStateRepository.reportNoBluetoothPermission(); // Ensure state is correct
                updateNotification("Bluetooth permissions needed");
                return;
            }

            BluetoothServerSocket tempServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(HFP_SERVICE_NAME, HFP_HF_UUID);
            acceptThread = new AcceptThread(tempServerSocket);
            executorService.submit(acceptThread);
            hfpStateRepository.updateUiState(CarKitUiState.LISTENING_FOR_CONNECTIONS);
            updateNotification("Listening for phone connections");
            Log.i(TAG, "HFP Server started, listening for connections on new AcceptThread.");
        } catch (IOException e) {
            Log.e(TAG, "IOException during listenUsingRfcommWithServiceRecord: " + e.getMessage(), e);
            // MODIFICATION START
            hfpStateRepository.reportServiceError("Failed to start HFP server: " + e.getMessage());
            // MODIFICATION END
            updateNotification("Error starting service");
            // MODIFICATION START: Catch SecurityException explicitly
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException during listenUsingRfcommWithServiceRecord: " + se.getMessage(), se);
            hfpStateRepository.reportNoBluetoothPermission(); // This is a permission issue
            hfpStateRepository.reportServiceError("Permission issue starting HFP server: " + se.getMessage());
            updateNotification("Bluetooth permissions needed");
        }
        // MODIFICATION END
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
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTING);

        // Use the helper method here for the repository as well,
        // so the repository stores the best available name.
        String deviceName = getSafeDeviceNameForNotification(socket.getRemoteDevice());
        String deviceAddress = socket.getRemoteDevice().getAddress();
        hfpStateRepository.setConnectedDevice(deviceName, deviceAddress); // This is correct


        connectedClientThread = new ConnectedClientThread(socket);
        executorService.submit(connectedClientThread);
    }


    // --- AcceptThread for listening for incoming connections ---
    private class AcceptThread implements Runnable {
        private final BluetoothServerSocket mmServerSocket;
        private volatile boolean isRunning = true;

        AcceptThread(BluetoothServerSocket serverSocket) {
            this.mmServerSocket = serverSocket;
            Log.d(TAG, "AcceptThread created.");
        }

        @Override
        public void run() {
            Log.i(TAG, "AcceptThread started, waiting for incoming connections...");
            BluetoothSocket socket = null;
            while (isRunning && !Thread.currentThread().isInterrupted() && mmServerSocket != null) {
                try {
                    socket = mmServerSocket.accept();
                    if (socket != null && isRunning) {
                        Log.i(TAG, "Connection accepted from " + getSafeDeviceNameForNotification(socket.getRemoteDevice()));
                        handleNewConnection(socket);
                    }
                } catch (IOException e) {
                    if (isRunning) {
                        Log.e(TAG, "AcceptThread: accept() failed or interrupted.", e);
                    }
                    break;
                }
            }
            Log.i(TAG, "AcceptThread finished.");
        }

        public void cancel() {
            isRunning = false;
            try {
                if (mmServerSocket != null) {
                    mmServerSocket.close();
                }
                Log.d(TAG, "AcceptThread: ServerSocket closed.");
            } catch (IOException e) {
                Log.e(TAG, "AcceptThread: Could not close server socket.", e);
            }
        }
    }

    // --- ConnectedClientThread for handling communication with a connected device ---
    private class ConnectedClientThread implements Runnable {
        private final BluetoothSocket mmSocket;
        private BufferedReader mmReader;
        private BufferedWriter mmWriter;
        private volatile boolean isRunning = true;

        ConnectedClientThread(BluetoothSocket socket) {
            this.mmSocket = socket;
            Log.d(TAG, "ConnectedClientThread created for " + getSafeDeviceNameForNotification(socket.getRemoteDevice()));
            try {
                mmReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                mmWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTED);

                // Use helper method for notification
                updateNotification("Connected to " + getSafeDeviceNameForNotification(socket.getRemoteDevice()));

                Log.i(TAG, "I/O streams opened successfully for " + getSafeDeviceNameForNotification(socket.getRemoteDevice()));
            } catch (IOException e) {
                Log.e(TAG, "ConnectedClientThread: Failed to create I/O streams for " + getSafeDeviceNameForNotification(socket.getRemoteDevice()), e);
                // MODIFICATION START
                hfpStateRepository.reportServiceError("Failed to establish I/O with phone: " + e.getMessage());
                // MODIFICATION END
                updateNotification("Connection error with " + getSafeDeviceNameForNotification(socket.getRemoteDevice()));
                // MODIFICATION START: cleanupConnection will call clearConnectedDevice
                // hfpStateRepository.clearConnectedDevice(); // Moved to cleanupConnection
                // MODIFICATION END
                cancel(); // Calls cleanupConnection
            }
        }

        @Override
        public void run() {
            if (mmReader == null || mmWriter == null) {
                Log.e(TAG, "ConnectedClientThread: I/O streams not available. Thread cannot run.");
                isRunning = false;
            }

            Log.i(TAG, "ConnectedClientThread started for " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()));
            String line;
            try {
                while (isRunning && !Thread.currentThread().isInterrupted() && (line = mmReader.readLine()) != null) {
                    String receivedCommand = line.trim();
                    Log.d(TAG, "AT RX: [" + receivedCommand + "]");

                    if (receivedCommand.isEmpty()) continue;

                    if (receivedCommand.startsWith("AT+CLIP=")) {
                        String number = extractClipNumber(receivedCommand);
                        hfpStateRepository.setIncomingCallNumber(number);
                        updateNotification("Incoming call: " + number);
                        sendAtCommand("OK\r\n");
                    } else if (receivedCommand.equals("ATA")) {
                        if (hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_INCOMING) {
                            Log.i(TAG, "AG answered call (ATA received). HF should now manage call audio.");
                            hfpStateRepository.updateUiState(CarKitUiState.CALL_IN_PROGRESS);
                            updateNotification("Call in progress with " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()));
                            hfpStateRepository.clearIncomingCallNumber();
                            sendAtCommand("OK\r\n");
                        } else {
                            sendAtCommand("ERROR\r\n");
                        }
                    } else if (receivedCommand.equals("AT+CHUP")) {
                        Log.i(TAG, "AG hung up call (AT+CHUP received).");
                        hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTED);
                        // Use helper method for notification
                        updateNotification("Connected to " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()));
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
                    }
                    else {
                        Log.w(TAG, "Unhandled AT command: " + receivedCommand + ". Sending OK as default.");
                        sendAtCommand("OK\r\n");
                    }
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "ConnectedClientThread: readLine() failed or connection lost for " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()), e);
                    // MODIFICATION START
                    hfpStateRepository.reportServiceError("Connection lost with " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()) + ": " + e.getMessage());
                    // MODIFICATION END
                }
            } finally {
                Log.i(TAG, "ConnectedClientThread for " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()) + " finished.");
                cleanupConnection();
            }
        }

        private void sendAtCommand(String command) {
            if (mmWriter != null && isRunning) {
                try {
                    mmWriter.write(command);
                    mmWriter.flush();
                    Log.d(TAG, "AT TX: [" + command.trim() + "]");
                } catch (IOException e) {
                    Log.e(TAG, "ConnectedClientThread: Failed to send AT command: " + command.trim() + " to " + getSafeDeviceNameForNotification(mmSocket.getRemoteDevice()), e);
                    cleanupConnection();
                }
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

        private void cleanupConnection() {
            isRunning = false;
            String deviceIdentifier = (mmSocket != null && mmSocket.getRemoteDevice() != null)
                    ? getSafeDeviceNameForNotification(mmSocket.getRemoteDevice())
                    : "disconnected socket";

            // Only perform cleanup if this thread instance is the active one
            if (HfpAgentService.this.connectedClientThread == this) {
                Log.i(TAG, "Cleaning up active connection for " + deviceIdentifier);
                // MODIFICATION START: Call clearConnectedDevice from the repository
                hfpStateRepository.clearConnectedDevice();
                // MODIFICATION END
                hfpStateRepository.clearIncomingCallNumber();

                // MODIFICATION START: Determine next state based on current BT and Perms status from repository
                boolean btStillEnabled = Boolean.TRUE.equals(hfpStateRepository.getIsBluetoothEnabledValue());
                boolean permsStillGranted = Boolean.TRUE.equals(hfpStateRepository.getHasBluetoothPermissionsValue());

                if (btStillEnabled && permsStillGranted) {
                    hfpStateRepository.updateUiState(CarKitUiState.LISTENING_FOR_CONNECTIONS);
                    updateNotification("Listening for phone connections");
                    // Optionally, try to restart AcceptThread if it's not running
                    if (acceptThread == null || !acceptThread.isRunning) { // Simplified check
                        Log.i(TAG, "Cleanup: Restarting HFP server listening.");
                        startHfpServerListening();
                    }
                } else if (!btStillEnabled) {
                    // reportBluetoothOff already handles UI state and notification
                    hfpStateRepository.reportBluetoothOff();
                    // updateNotification("Bluetooth is OFF"); // Handled by reportBluetoothOff effect
                } else { // Bluetooth on, but permissions missing
                    // reportNoBluetoothPermission already handles UI state and notification
                    hfpStateRepository.reportNoBluetoothPermission();
                    // updateNotification("Bluetooth permissions needed"); // Handled
                }
                // MODIFICATION END
                HfpAgentService.this.connectedClientThread = null; // Clear the service's reference to this thread
            } else {
                Log.d(TAG, "cleanupConnection called for an old or non-active thread instance for " + deviceIdentifier);
            }

            try {
                if (mmSocket != null) mmSocket.close();
                if (mmReader != null) mmReader.close();
                if (mmWriter != null) mmWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "ConnectedClientThread: Error closing resources for " + deviceIdentifier, e);
            }
            Log.d(TAG, "Connection resources cleaned up for " + deviceIdentifier);
        }

        public void cancel() {
            String deviceIdentifier = (mmSocket != null && mmSocket.getRemoteDevice() != null)
                    ? getSafeDeviceNameForNotification(mmSocket.getRemoteDevice())
                    : "being cancelled socket";
            Log.d(TAG, "ConnectedClientThread.cancel() called for " + deviceIdentifier);
            isRunning = false;
            cleanupConnection();
        }
    }


    // --- BroadcastReceiver for Bluetooth Adapter State Changes ---
    // Inside HfpAgentService.java

    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.d(TAG, "Bluetooth state receiver: Received action: " + action);

            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        Log.i(TAG, "Bluetooth turned OFF via broadcast.");
                        // MODIFICATION START
                        hfpStateRepository.reportBluetoothEnabled(false);
                        // The reportBluetoothOff() in the repository (triggered by reportBluetoothEnabled(false))
                        // should handle UI state and clearing connections.
                        // MODIFICATION END
                        updateNotification("Bluetooth is OFF");
                        stopListeningAndConnectionThreads();
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.d(TAG, "Bluetooth turning OFF...");
                        // Optionally update state to something like 'DISCONNECTING' or 'LOADING'
                        hfpStateRepository.updateUiState(CarKitUiState.LOADING); // Or a specific turning_off state
                        break;
                    case BluetoothAdapter.STATE_ON:
                        Log.i(TAG, "Bluetooth turned ON via broadcast.");
                        // MODIFICATION START
                        hfpStateRepository.reportBluetoothEnabled(true);
                        // Now that BT is on, re-check permissions and update repository
                        boolean permsGranted = hasRequiredPermissions(); // This will call reportBluetoothPermissionsGranted in repo
                        // If permsGranted is true, then start listening.
                        // If false, reportNoBluetoothPermission (which is done by hasRequiredPermissions) will set the correct UI state.
                        if (permsGranted) {
                            startHfpServerListening(); // This will set LISTENING_FOR_CONNECTIONS
                        } else {
                            // reportNoBluetoothPermission() was called by hasRequiredPermissions if not granted
                            updateNotification("Bluetooth permissions needed");
                        }
                        // MODIFICATION END
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.d(TAG, "Bluetooth turning ON...");
                        hfpStateRepository.updateUiState(CarKitUiState.LOADING);
                        updateNotification("Bluetooth starting...");
                        break;
                    default:
                        Log.d(TAG, "Unhandled Bluetooth state: " + state);
                        break;
                }
            }
        }
    };

    private void registerBluetoothStateReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(bluetoothStateReceiver, filter);
            }
            Log.d(TAG, "Bluetooth state receiver registered.");
        } catch (Exception e) {
            Log.e(TAG, "Error registering Bluetooth state receiver: " + e.getMessage(), e);
        }
    }

    private void unregisterBluetoothStateReceiver() {
        try {
            unregisterReceiver(bluetoothStateReceiver);
            Log.d(TAG, "Bluetooth state receiver unregistered.");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Bluetooth state receiver was not registered or already unregistered: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "HfpAgentService onDestroy called.");
        stopListeningAndConnectionThreads();
        unregisterBluetoothStateReceiver();
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
                updateNotification("Call in progress with " + getSafeDeviceNameForNotification(connectedClientThread.mmSocket.getRemoteDevice()));
            } else {
                updateNotification("Call in progress");
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
                // Use helper method for notification
                updateNotification("Connected to " + getSafeDeviceNameForNotification(connectedClientThread.mmSocket.getRemoteDevice()));
            } else {
                updateNotification("Connected to device"); // Fallback if somehow socket is null
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
        String currentDeviceName = hfpStateRepository.getConnectedDeviceNameValue(); // Assuming getter in repo
        // You might need to adjust how you get address if it's stored separately or part of a device object in repo
        String currentDeviceAddress = ""; // Placeholder, adjust based on your HfpStateRepository

        hfpStateRepository.updateUiState(currentState);
        if (currentDeviceName != null) {
            hfpStateRepository.setConnectedDevice(currentDeviceName, currentDeviceAddress);
        } else {
            hfpStateRepository.clearConnectedDevice();
        }

        String currentIncomingCall = hfpStateRepository.getIncomingCallNumberValue(); // Assuming getter in repo
        if (currentIncomingCall != null) {
            hfpStateRepository.setIncomingCallNumber(currentIncomingCall);
        } else {
            hfpStateRepository.clearIncomingCallNumber();
        }
        Log.d(TAG, "Published current state to repository: " + currentState);
    }
}