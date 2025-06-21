package com.dogood.handsfreeprofile.service;

import android.util.Log;

import com.dogood.handsfreeprofile.connection.ConnectedClientThread;
import com.dogood.handsfreeprofile.model.CarKitUiState;
import com.dogood.handsfreeprofile.repository.HfpStateRepository;
import com.dogood.handsfreeprofile.util.BluetoothUtils;

public class AtCommandProcessor {

    private static final String TAG = "AtCommandProcessor";
    private final HfpStateRepository hfpStateRepository;
    private final NotificationHelper notificationHelper;
    private final HfpAgentService hfpAgentService;
    private ConnectedClientThread connectedClientThread;

    public AtCommandProcessor(HfpStateRepository hfpStateRepository, NotificationHelper notificationHelper, HfpAgentService hfpAgentService) {
        this.hfpStateRepository = hfpStateRepository;
        this.notificationHelper = notificationHelper;
        this.hfpAgentService = hfpAgentService;
    }

    public void processAtCommand(String receivedCommand) {
        if (receivedCommand.startsWith("AT+CLIP=")) {
            String number = extractClipNumber(receivedCommand);
            hfpStateRepository.setIncomingCallNumber(number);
            notificationHelper.updateNotification("Incoming call: " + number);
            sendAtCommand("OK\r\n");
        } else if (receivedCommand.equals("ATA")) {
            if (hfpStateRepository.getCurrentUiStateValue() == CarKitUiState.CALL_INCOMING) {
                Log.i(TAG, "AG answered call (ATA received). HF should now manage call audio.");
                hfpStateRepository.updateUiState(CarKitUiState.CALL_IN_PROGRESS);
                if (connectedClientThread != null && connectedClientThread.mmSocket != null) {
                    notificationHelper.updateNotification("Call in progress with " + BluetoothUtils.getSafeDeviceName(hfpAgentService, connectedClientThread.mmSocket.getRemoteDevice()));
                } else {
                    notificationHelper.updateNotification("Call in progress");
                }
                hfpStateRepository.clearIncomingCallNumber();
                sendAtCommand("OK\r\n");
            } else {
                sendAtCommand("ERROR\r\n");
            }
        } else if (receivedCommand.equals("AT+CHUP")) {
            Log.i(TAG, "AG hung up call (AT+CHUP received).");
            hfpStateRepository.updateUiState(CarKitUiState.PHONE_CONNECTED);
            if (connectedClientThread != null && connectedClientThread.mmSocket != null) {
                notificationHelper.updateNotification("Connected to " + BluetoothUtils.getSafeDeviceName(hfpAgentService, connectedClientThread.mmSocket.getRemoteDevice()));
            } else {
                notificationHelper.updateNotification("Connected to device");
            }
            hfpStateRepository.clearIncomingCallNumber();
            sendAtCommand("OK\r\n");
        } else if (receivedCommand.startsWith("AT+VGS=")) {
            try {
                int level = Integer.parseInt(receivedCommand.substring("AT+VGS=".length()).trim());
                hfpAgentService.commandSetSpeakerVolume(level);
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

    public void setConnectedClientThread(ConnectedClientThread connectedClientThread) {
        this.connectedClientThread = connectedClientThread;
    }

    public void clearConnectedClientThread() {
        this.connectedClientThread = null;
    }
}