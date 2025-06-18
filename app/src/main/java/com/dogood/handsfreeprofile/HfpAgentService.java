package com.dogood.handsfreeprofile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.UUID;

public class HfpAgentService extends Service {

    private static final String CHANNEL_ID = "ForegroundServiceChannel";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothServerSocket serverSocket;
    private boolean useNativeHandler = false; // Toggle for NDK integration

    private String incomingPhoneNumber = null;
    private boolean isCallRinging = false;
    private BufferedWriter hfpWriter = null;
    private int currentHfpVolume = 5; // default volume level (0–15)

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        new Thread(this::startHfpServer).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Foreground Service")
                .setContentText("Service is running...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        startForeground(1, notification);
        // Do your background work here

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    public int getCurrentHfpVolume() {
        return currentHfpVolume;
    }

    public void setHfpVolume(int level) {
        if (level < 0) level = 0;
        if (level > 15) level = 15;
        currentHfpVolume = level;
        sendHfpVolumeCommand(level);
    }

    public void muteHfpVolume() {
        currentHfpVolume = 0;
        sendHfpVolumeCommand(0);
    }

    private void sendHfpVolumeCommand(int level) {
        if (hfpWriter != null) {
            try {
                hfpWriter.write("AT+VGS=" + level + "\r\n");
                hfpWriter.flush();
                Log.d("HFP_AGENT", "Sent AT+VGS=" + level);
            } catch (IOException e) {
                Log.e("HFP_AGENT", "Failed to send AT+VGS command", e);
            }
        }
    }

    private void startHfpServer() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            } else {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(
                        "HFP Agent",
                        UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb") // HFP UUID (Hands-Free Profile), from Bluetooth SIG: https://www.bluetooth.com/specifications/assigned-numbers/service-discovery
                );
            }
            while (true) {
                BluetoothSocket socket = serverSocket.accept();
                new Thread(() -> {
//                    if (useNativeHandler) {
//                        handleSocketWithNative(socket);
//                    } else
                    {
                        handleHfpConnection(socket);
                    }
                }).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

//    private void handleSocketWithNative(BluetoothSocket socket) {
//        try {
//            java.lang.reflect.Method getFdMethod = socket.getClass().getMethod("getFd");
//            getFdMethod.setAccessible(true);
//            int fd = (int) getFdMethod.invoke(socket);
//            handleNativeHfpConnection(fd);
//        } catch (Exception e) {
//            Log.e("HFP_AGENT", "Failed to use native handler", e);
//        }
//    }

    private void handleHfpConnection(BluetoothSocket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {

            this.hfpWriter = writer;
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d("HFP_AGENT", "Received: " + line);

                if (line.contains("AT+CLIP")) {
                    incomingPhoneNumber = extractPhoneNumber(line);
                    isCallRinging = true;
                    Log.d("HFP_AGENT", "Incoming number: " + incomingPhoneNumber);
                } else if (line.contains("ATA")) {
                    answerIncomingCall();
                } else if (line.contains("AT+CHUP")) {
                    declineIncomingCall();
                } else if (line.startsWith("AT+VGS=")) {
                    try {
                        int level = Integer.parseInt(line.replace("AT+VGS=", "").trim());
                        currentHfpVolume = Math.max(0, Math.min(15, level));
                        Log.d("HFP_AGENT", "Remote HFP volume set to: " + currentHfpVolume);
                    } catch (NumberFormatException e) {
                        Log.w("HFP_AGENT", "Invalid VGS volume: " + line);
                    }
                }

                writer.write("\r\nOK\r\n");
                writer.flush();
            }
        } catch (IOException e) {
            Log.e("HFP_AGENT", "Error handling HFP connection", e);
        }
    }

    public void sendAnswerCommand() {
        if (hfpWriter != null && isCallRinging) {
            try {
                hfpWriter.write("ATA\r\n");
                hfpWriter.flush();
                Log.d("HFP_AGENT", "Sent ATA command to answer call");
            } catch (IOException e) {
                Log.e("HFP_AGENT", "Failed to send ATA command", e);
            }
        }
    }

    public void sendHangupCommand() {
        if (hfpWriter != null && isCallRinging) {
            try {
                hfpWriter.write("AT+CHUP\r\n");
                hfpWriter.flush();
                Log.d("HFP_AGENT", "Sent AT+CHUP command to hang up call");
            } catch (IOException e) {
                Log.e("HFP_AGENT", "Failed to send AT+CHUP command", e);
            }
        }
    }

    private String extractPhoneNumber(String line) {
        int start = line.indexOf('"');
        int end = line.indexOf('"', start + 1);
        if (start != -1 && end != -1) {
            return line.substring(start + 1, end);
        } else {
            return "Private Number";
        }
    }

    private void answerIncomingCall() {
        if (isCallRinging) {
            Log.d("HFP_AGENT", "Answering call: " + incomingPhoneNumber);
            isCallRinging = false;
        }
    }

    private void declineIncomingCall() {
        if (isCallRinging) {
            Log.d("HFP_AGENT", "Declining call: " + incomingPhoneNumber);
            isCallRinging = false;
        }
    }

    private void ignoreIncomingCall() {
        if (isCallRinging) {
            Log.d("HFP_AGENT", "Ignoring call: " + incomingPhoneNumber);
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}