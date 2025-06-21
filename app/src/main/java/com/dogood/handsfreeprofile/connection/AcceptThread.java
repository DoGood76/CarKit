package com.dogood.handsfreeprofile.connection;

import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import com.dogood.handsfreeprofile.util.BluetoothUtils;

import java.io.IOException;

public class AcceptThread implements Runnable {
    private static final String TAG = "AcceptThread";
    private final BluetoothServerSocket mmServerSocket;
    private volatile boolean isRunning = true;
    private final AcceptThreadCallback callback;
    private final Context context;

    public interface AcceptThreadCallback {
        void onConnectionAccepted(BluetoothSocket socket);

        void onAcceptThreadStopped();
    }

    public AcceptThread(Context context, BluetoothServerSocket serverSocket, AcceptThreadCallback callback) {
        this.context = context;
        this.mmServerSocket = serverSocket;
        this.callback = callback;
        Log.d(TAG, "AcceptThread created.");
    }

    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void run() {
        Log.i(TAG, "AcceptThread started, waiting for incoming connections...");
        BluetoothSocket socket;
        while (isRunning && !Thread.currentThread().isInterrupted() && mmServerSocket != null) {
            try {
                socket = mmServerSocket.accept();
                Log.d(TAG, "AcceptThread: accept() returned a socket: " + (socket != null ? socket.getRemoteDevice().getAddress() : "null"));
                if (socket != null && isRunning) {
                    Log.i(TAG, "Connection accepted from " + BluetoothUtils.getSafeDeviceName(context, socket.getRemoteDevice()));
                    callback.onConnectionAccepted(socket);
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "accept() failed or interrupted.", e);
                }
                break;
            }
        }
        callback.onAcceptThreadStopped();
        Log.i(TAG, "AcceptThread finished.");
    }

    public void cancel() {
        isRunning = false;
        try {
            if (mmServerSocket != null) {
                mmServerSocket.close();
            }
            Log.d(TAG, "ServerSocket closed.");
        } catch (IOException e) {
            Log.e(TAG, "Could not close server socket.", e);
        }
    }
}