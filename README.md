# CarKit Agent for Android

## Project Goals Overview
This project simulates a Bluetooth **Hands-Free Profile (HFP) Audio Gateway** (AG) service on an Android device, allowing it to behave like a car kit. It also includes partial support for **PBAP (Phone Book Access Profile)** for accessing contacts over Bluetooth. The goal is to provide a way to test and reverse engineer HFP interactions, especially for Android Auto compatibility and interactions with non-rooted devices.

## Required Features
- **HFP Server**: Accepts connections from HFP clients (e.g., phones).
- **Phone Number Parsing**: Emulates phone number parsing and ringing status.
- **Answer/Reject Calls**: Simulates call answering and rejection.
- **Get Contact List**: Implements a basic PBAP client to retrieve contacts.

## Glossary

- **HFP (Hands-Free Profile)**: Bluetooth profile enabling hands-free calling features between devices like phones and car kits.
- **PBAP (Phone Book Access Profile)**: Bluetooth profile allowing access to a device’s contacts and call history.
- **RFCOMM**: Bluetooth protocol that emulates serial ports for profile communication, used by HFP and other profiles.
- **SDP (Service Discovery Protocol)**: Bluetooth protocol for discovering available services and their parameters on remote devices.
- **SCO (Synchronous Connection-Oriented) Audio Channel**: Bluetooth link type for real-time, mono audio transmission, typically used for voice calls.
- **CoD (Class of Device)**: Bluetooth parameter indicating the type and capabilities of a device (e.g., phone, car kit).
- **AT Commands**: Text-based commands used for controlling modems and Bluetooth profiles like HFP.
- **NDK (Native Development Kit)**: Android toolkit for implementing parts of an app using native code (C/C++).

## Android Auto Behavior
Android Auto works because it:
- Is a **system app**, signed by the platform key.
- Uses fully registered **HFP + PBAP + MAP** profiles.
- Can set `enable_phone_policy = false` to control calls manually.
- Has access to Bluetooth stack internals and full privileges.

## Attempts on Non-Rooted Devices
- **Paired with correct HFP UUID (`0000111f-0000-1000-8000-00805f9b34fb`)** – pairing succeeds but HFP not advertised.

---

## Limitations and Known Issues

### ❌ Limitations (Android Constraints)
1. **`enable_phone_policy` Flag**:
   - Required for system to treat device as a car kit.
   - Hidden inside `BluetoothHeadsetService`, cannot be modified on non-rooted phones.

2. **Bluetooth Class of Device (CoD)**:
   - Cannot be changed without root or OEM customization.
   - Agent may not be seen as a car kit due to default CoD.

3. **SDP Limitations**:
   - Java API only registers basic SDP info.
   - Full SDP record for HFP is not possible without native support.

4. **PBAP Access**:
   - Client role restricted unless app is a system app.
   - Only socket-level testing possible.

5. **SCO Audio Channel**:
   - Audio routing not available to user-space apps.
   - Calls answered via AT commands will still use client audio hardware.

6. **Permissions Required**:
   - `BLUETOOTH_PRIVILEGED` and `MODIFY_PHONE_STATE` required for deeper integration.
   - Cannot be granted via ADB without system signature.

---
## Approaches to HFP Implementation
These are possible approaches for continuing work and experimentation:

### 1. Reflection-Based HFP Client (Hidden API)
- **Description**: Uses reflection to access `BluetoothHeadsetClient`.
- **Advantages**: Leverages built-in Android APIs; mirrors Android Automotive behavior.
- **Disadvantages**:
    - Fails if `profile_supported_hfpclient=false`.
    - Requires hidden permissions (`BLUETOOTH_PRIVILEGED`, `MODIFY_PHONE_STATE`).
    - Not usable unless app is a system app or has Knox/MDM privileges.

### 2. Emulated Car Kit Server with AT Commands
- **Description**: Uses `BluetoothAdapter.listenUsingRfcommWithServiceRecord()` to simulate car kit behavior.
    - Listens for incoming HFP connections and handles AT command parsing manually (e.g., `AT+BRSF`, `AT+CLIP`, `ATA`).
- **Advantages**: Full control over protocol parsing; useful for learning/testing.
- **Disadvantages**:
    - Does not work reliably between two **non-rooted phones**.
    - Android client phone often ignores incoming RFCOMM requests without valid SDP((Service Discovery Protocol) and CoD(class of device).
    - No SCO audio channel.
    - SDP record generated via Java API is not sufficient for full HFP negotiation.

### 3. Samsung Knox or OEM Customization
- **Description**: Uses Knox’s `BluetoothPolicy` API (with enterprise key).
- **Advantages**:
    - Can restrict profile UUIDs and simulate HFP-only pairing scenarios.
    - May allow usage of advanced call control permissions.
- **Disadvantages**:
    - Requires Samsung Knox license and enterprise key.
    - Limited to Samsung devices; may not be portable.

### 4. Using Samsung's "Call & Text on Other Devices"
- **Description**: Proprietary solution from Samsung for secondary device to receive calls.
- **Advantages**:
    - Achieves goal via Samsung account; no HFP needed.
- **Disadvantages**:
    - Works only with Samsung ecosystem.
    - Not based on standard Bluetooth HFP; not compatible with non-Samsung phones.

### 5. Native Code / Custom Stack Approach
- **Description**: Implements AT command parsing and SDP handling via NDK.
- **Advantages**:
    - Full control over protocol behavior.
    - Allows low-level experimentation with socket-based Bluetooth.
- **Disadvantages**:
    - Cannot gain SCO audio access without root/system support.
    - Requires advanced development and debugging.
    - HCI access to Bluetooth chip not possible without elevated privileges.

---

## AOSP and Reference Documentation
- Android Automotive Bluetooth Connectivity: https://source.android.com/docs/automotive/ivi_connectivity
- Samsung Knox BluetoothPolicy: https://docs.samsungknox.com
- HFP 1.7 Specification: [Bluetooth SIG](https://www.bluetooth.org/docman/handlers/downloaddoc.ashx?doc_id=245447)

## Status
**Experimental** – for reverse engineering, learning, and internal testing. Not intended for production use.

---
