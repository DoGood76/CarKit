package com.dogood.handsfreeprofile.model; // Or a more suitable package like com.dogood.handsfreeprofile.model

public enum CarKitUiState {
    /**
     * The ViewModel is initializing or waiting for connection to the HfpAgentService
     * or for the initial state from the repository.
     */
    LOADING,

    /**
     * Bluetooth adapter on the device is turned off.
     */
    BLUETOOTH_OFF,

    /**
     * Bluetooth is on, but the required Bluetooth permissions (CONNECT, ADVERTISE)
     * are not granted by the user (Android 12+).
     */
    NO_BLUETOOTH_PERMISSION,

    /**
     * HFP Service is running, Bluetooth is on, permissions are granted,
     * and the service is listening for incoming connections from phones.
     * The device might not be actively discoverable in this state unless explicitly triggered.
     */
    LISTENING_FOR_CONNECTIONS,

    /**
     * The device has been explicitly made discoverable and is awaiting connections.
     */
    DISCOVERABLE_ACTIVE,

    /**
     * A phone (Audio Gateway) is currently attempting to establish an HFP connection.
     */
    PHONE_CONNECTING,

    /**
     * An HFP connection has been successfully established with a phone.
     */
    PHONE_CONNECTED,

    /**
     * A phone call is currently active through the HFP connection.
     */
    CALL_IN_PROGRESS,

    /**
     * An incoming call is ringing on the connected phone.
     */
    CALL_INCOMING,

    /**
     * The HFP service encountered an error or is in an otherwise undefined error state.
     */
    SERVICE_ERROR,
    /**
     * The HfpAgentService has been explicitly stopped or has shut down.
     * This could be due to a user action, a system event, or an unrecoverable error
     * that led to the service stopping itself.
     */
    SERVICE_STOPPED,
    CALL_IN_COMING;
}