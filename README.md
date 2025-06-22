# CarKit Agent for Android

## Project Goals Overview
This project simulates a Bluetooth **Hands-Free Profile (HFP) Audio Gateway** (AG) service on an Android device, allowing it to behave like a car kit. It also includes partial support for **PBAP (Phone Book Access Profile)** for accessing contacts over Bluetooth.
The goal is to provide a way to test and reverse engineer HFP interactions, especially for Android Auto compatibility for non-rooted devices.

## Key Features
- **HFP Server**: Accepts connections from HFP clients (e.g., phones).
- **Phone Number Parsing**: Emulates phone number parsing and ringing status.
- **Answer/Reject Calls**: Simulates call answering and rejection.
- **Get Contact List**: Implements a basic PBAP client to retrieve contacts.

## Backlog
- Accepts HFP connections from another phone via RFCOMM.
- Ability to recieve Parses and responds to common AT commands.
- Parses AT commands (e.g., CLIP, ATA, CHUP) from the connected HFP client.
- Offers volume control (`AT+VGS`) commands.
- Native (NDK) support for low-level Bluetooth control.
- Starts a PBAP listener to simulate contact sharing.

### 🔄 Android Auto Behavior
Android Auto works because it:
- Is a **system app**, signed by the platform key.
- Uses fully registered **HFP + PBAP + MAP** profiles.
- Can set `enable_phone_policy` to `false` to control calls manually.
- Has access to trusted Bluetooth stack privileges and full SDP.

### 🛠 Attempts on Non-Rooted Devices
- **Tried pairing with HFP UUID** — connects but does not trigger HFP flow.
- **Used correct UUID (`0000111f-0000-1000-8000-00805f9b34fb`)** — not trigger HFP flow.

## Link to AOSP Android Automotive Reference
https://source.android.com/docs/automotive/ivi_connectivity


## Limitations and Known Issues

### ✅ Successes:
- RFCOMM HFP server established.

### ❌ Limitations (Android Constraints):
1. **`enable_phone_policy` Flag usage**:
    - **Prevents Android from treating the agent as a car kit.**
    - Hidden inside `BluetoothHeadsetService` and can't be toggled on non-rooted devices.
    - Android will not send call state updates or audio routing control to an app that doesn’t match a full SDP car kit profile.

2. **Cannot change Bluetooth Class of Device (CoD)**:
    - Android apps cannot spoof CoD to make themselves appear as car kits unless rooted.

3. **SDP Record Incomplete**:
    - Java Bluetooth API generates a basic SDP record — not sufficient to fully pass HFP car kit validation.

4. **Contact Access (PBAP)**:
    - Android does **not allow PBAP client access** unless you're a system app or the phone explicitly trusts the agent.
    - PBAP support in this repo is limited to socket-level interaction (no formal OBEX path headers).

5. **No Audio Routing**:
    - Audio remains on the client and is not transferred to the agent.
    - Agent cannot initiate real call audio or take full control.

---

**Status:** Experimental. Use for learning, reverse engineering, or internal testing only.

---

For Bluetooth HFP specification: [Bluetooth SIG HFP 1.7](https://www.bluetooth.org/docman/handlers/downloaddoc.ashx?doc_id=245447)
