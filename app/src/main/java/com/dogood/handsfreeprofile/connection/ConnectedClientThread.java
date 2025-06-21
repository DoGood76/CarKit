package com.dogood.handsfreeprofile.connection;

import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.dogood.handsfreeprofile.util.BluetoothUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

public class ConnectedClientThread implements Runnable {
    private static final String TAG = "ConnectedClientThread";
    public final BluetoothSocket mmSocket; // Changed to public
    private BufferedReader mmReader;
    private BufferedWriter mmWriter;
    private volatile boolean isRunning = true;
    private final ConnectedClientCallback callback;
    private final Context context;

    public interface ConnectedClientCallback {
        void onAtCommandReceived(String command);

        void onConnectionClosed();
    }

    public ConnectedClientThread(Context context, BluetoothSocket socket, ConnectedClientCallback callback) {
        this.context = context;
        this.mmSocket = socket;
        this.callback = callback;
        Log.d(TAG, "ConnectedClientThread created for " + BluetoothUtils.getSafeDeviceName(context, socket.getRemoteDevice()));
        try {
            mmReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            mmWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            Log.i(TAG, "I/O streams opened successfully for " + BluetoothUtils.getSafeDeviceName(context, socket.getRemoteDevice()));
        } catch (IOException e) {
            Log.e(TAG, "Failed to create I/O streams for " + BluetoothUtils.getSafeDeviceName(context, socket.getRemoteDevice()), e);
            cancel();
        }
    }

    @Override
    public void run() {
        if (mmReader == null || mmWriter == null) {
            Log.e(TAG, "I/O streams not available. Thread cannot run.");
            isRunning = false;
        }

        Log.i(TAG, "ConnectedClientThread started for " + BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice()));
        String line;
        try {
            while (isRunning && !Thread.currentThread().isInterrupted() && (line = mmReader.readLine()) != null) {
                String receivedCommand = line.trim();
                Log.d(TAG, "AT RX: [" + receivedCommand + "]");
                if (!receivedCommand.isEmpty()) {
                    callback.onAtCommandReceived(receivedCommand);
                }
            }
        } catch (IOException e) {
            if (isRunning) {
                Log.e(TAG, "readLine() failed or connection lost for " + BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice()), e);
            }
        } finally {
            Log.i(TAG, "ConnectedClientThread for " + BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice()) + " finished.");
            cleanupConnection();
        }
    }

    public void sendAtCommand(String command) {
        if (mmWriter != null && isRunning) {
            try {
                mmWriter.write(command);
                mmWriter.flush();
                Log.d(TAG, "AT TX: [" + command.trim() + "]");
            } catch (IOException e) {
                Log.e(TAG, "Failed to send AT command: " + command.trim() + " to " + BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice()), e);
                cleanupConnection();
            }
        }
    }

    private void cleanupConnection() {
        isRunning = false;
        try {
            if (mmSocket != null) mmSocket.close();
            if (mmReader != null) mmReader.close();
            if (mmWriter != null) mmWriter.close();
            callback.onConnectionClosed();
        } catch (IOException e) {
            Log.e(TAG, "Error closing resources for " + BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice()), e);
        }
        Log.d(TAG, "Connection resources cleaned up for " + BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice()));
    }

    public void cancel() {
        String deviceIdentifier = (mmSocket != null && mmSocket.getRemoteDevice() != null)
                ? BluetoothUtils.getSafeDeviceName(context, mmSocket.getRemoteDevice())
                : "being cancelled socket";
        Log.d(TAG, "ConnectedClientThread.cancel() called for " + deviceIdentifier);
        isRunning = false;
        cleanupConnection();
    }

    public boolean isRunning() {
        return isRunning;
    }
}