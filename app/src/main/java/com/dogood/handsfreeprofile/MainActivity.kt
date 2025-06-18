package com.dogood.handsfreeprofile

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Warning // For NO_PERMISSION or NOT_SUPPORTED
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dogood.handsfreeprofile.ui.theme.HandsFreeProfileTheme

// Add these imports at the top of your MainActivity.kt
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Warning // This one is usually in core

// Assuming BluetoothUiState enum is defined (e.g., the Java one is accessible)
// If not, you'd need to define it here or import it. For example:
// import com.dogood.handsfreeprofile.BluetoothUiState // If it's a top-level enum in that package

class MainActivity : ComponentActivity() {

    private val bluetoothViewModel: BluetoothViewModel by viewModels() // Assumes BluetoothViewModel.java

    private val requestBluetoothConnectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("PermissionRequest_Activity", "BLUETOOTH_CONNECT permission granted by user")
                bluetoothViewModel.onBluetoothConnectPermissionGranted()
                if (serviceActionPending == ServiceAction.START) {
                    startHfpService()
                }
            } else {
                Log.d("PermissionRequest_Activity", "BLUETOOTH_CONNECT permission denied by user")
                bluetoothViewModel.updateState()
            }
            serviceActionPending = ServiceAction.NONE
        }

    private var isServiceRunning by mutableStateOf(false)
    private enum class ServiceAction { NONE, START, STOP }
    private var serviceActionPending by mutableStateOf(ServiceAction.NONE)

    private var currentBluetoothUiStateCompose by mutableStateOf(BluetoothUiState.DISABLED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothViewModel.bluetoothUiState.observe(this) { newState ->
            Log.d("MainActivity_LiveObserve", "Observed BT State from Java VM: $newState")
            if (newState != null) {
                currentBluetoothUiStateCompose = newState
            }
        }

        setContent {
            HandsFreeProfileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ServiceAndBluetoothStatusScreen(
                        modifier = Modifier.padding(innerPadding),
                        isServiceRunning = isServiceRunning,
                        bluetoothUiState = currentBluetoothUiStateCompose,
                        onToggleService = {
                            if (isServiceRunning) {
                                serviceActionPending = ServiceAction.STOP
                                stopHfpService()
                            } else {
                                serviceActionPending = ServiceAction.START
                                checkAndRequestBluetoothConnectPermission(forStartingService = true)
                            }
                        },
                        onRequestPermission = {
                            serviceActionPending = ServiceAction.NONE
                            checkAndRequestBluetoothConnectPermission(forStartingService = false)
                        },
                        onRequestEnableBluetooth = {
                            enableBluetooth()
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestBluetoothConnectPermission(forStartingService: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("PermissionCheck_Activity", "BLUETOOTH_CONNECT already granted.")
                    bluetoothViewModel.onBluetoothConnectPermissionGranted()
                    if (forStartingService && serviceActionPending == ServiceAction.START) {
                        startHfpService()
                    }
                }
                shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) -> {
                    Log.d("PermissionCheck_Activity", "Showing rationale for BLUETOOTH_CONNECT")
                    requestBluetoothConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                else -> {
                    Log.d("PermissionCheck_Activity", "Requesting BLUETOOTH_CONNECT permission")
                    requestBluetoothConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        } else {
            Log.d("PermissionCheck_Activity", "BLUETOOTH_CONNECT not required by OS version.")
            bluetoothViewModel.onBluetoothConnectPermissionGranted()
            if (forStartingService && serviceActionPending == ServiceAction.START) {
                startHfpService()
            }
        }
    }

    private fun enableBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivity(enableBtIntent)
            } else {
                Log.w("BluetoothEnable_Activity", "BLUETOOTH_CONNECT permission needed to request enabling Bluetooth.")
                serviceActionPending = ServiceAction.NONE
                checkAndRequestBluetoothConnectPermission(forStartingService = false)
            }
        } else {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableBtIntent)
        }
    }

    private fun startHfpService() {
        if (isServiceRunning) {
            Log.d("HfpService_Activity", "Service is already running.")
            return
        }
        if (!bluetoothViewModel.hasBluetoothConnectPermission()){
            Log.w("HfpService_Activity", "Cannot start service, BLUETOOTH_CONNECT permission missing.")
            serviceActionPending = ServiceAction.START
            checkAndRequestBluetoothConnectPermission(forStartingService = true)
            return
        }
        val serviceIntent = Intent(this, HfpAgentService::class.java) // Ensure HfpAgentService exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isServiceRunning = true
        serviceActionPending = ServiceAction.NONE
        Log.i("HfpService_Activity", "HFP Service started.")
    }

    private fun stopHfpService() {
        if (!isServiceRunning && serviceActionPending != ServiceAction.STOP) {
            if(isServiceRunning) isServiceRunning = false
            serviceActionPending = ServiceAction.NONE
            return
        }
        val serviceIntent = Intent(this, HfpAgentService::class.java) // Ensure HfpAgentService exists
        stopService(serviceIntent)
        isServiceRunning = false
        serviceActionPending = ServiceAction.NONE
        Log.i("HfpService_Activity", "HFP Service stopped.")
    }
}

// Ensure this Composable function is defined within the file or imported.
@Composable
fun ServiceAndBluetoothStatusScreen(
    modifier: Modifier = Modifier,
    isServiceRunning: Boolean,
    bluetoothUiState: BluetoothUiState, // This needs to be the correct enum type
    onToggleService: () -> Unit,
    onRequestPermission: () -> Unit,
    onRequestEnableBluetooth: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val btIcon: ImageVector
        val btStatusText: String

        when (bluetoothUiState) {
            BluetoothUiState.DISABLED -> {
                btIcon = Icons.Filled.BluetoothDisabled
                btStatusText = "Bluetooth is Disabled"
            }
            BluetoothUiState.ENABLED -> {
                btIcon = Icons.Filled.Bluetooth
                btStatusText = "Bluetooth is Enabled"
            }
            BluetoothUiState.CONNECTED_HFP -> {
                btIcon = Icons.Filled.BluetoothConnected
                btStatusText = "HFP Device Connected"
            }
            BluetoothUiState.NO_PERMISSION -> {
                btIcon = Icons.Filled.Warning
                btStatusText = "Bluetooth permission needed"
            }
            BluetoothUiState.NOT_SUPPORTED -> {
                btIcon = Icons.Filled.Warning
                btStatusText = "Bluetooth not supported"
            }
            // else -> { // Optional: handle any unexpected state, though enum should be exhaustive
            //     btIcon = Icons.Filled.Warning
            //     btStatusText = "Unknown Bluetooth State"
            // }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = btIcon,
                contentDescription = "Bluetooth Status",
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(btStatusText)
        }

        if (bluetoothUiState == BluetoothUiState.NO_PERMISSION) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Bluetooth Permission")
            }
        }

        if (bluetoothUiState == BluetoothUiState.DISABLED) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onRequestEnableBluetooth) {
                Text("Enable Bluetooth")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "HFP Service is ${if (isServiceRunning) "Running" else "Stopped"}"
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onToggleService) {
            Text(if (isServiceRunning) "Stop HFP Service" else "Start HFP Service")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    HandsFreeProfileTheme {
        var isRunning by rememberSaveable { mutableStateOf(false) }
        // Use remember for preview state, not rememberSaveable unless needed for specific preview interactions
        var currentBtState by remember { mutableStateOf(BluetoothUiState.ENABLED) }

        ServiceAndBluetoothStatusScreen(
            isServiceRunning = isRunning,
            bluetoothUiState = currentBtState,
            onToggleService = { isRunning = !isRunning },
            onRequestPermission = { currentBtState = BluetoothUiState.ENABLED }, // Simulate permission grant
            onRequestEnableBluetooth = { currentBtState = BluetoothUiState.ENABLED } // Simulate enabling
        )
    }
}