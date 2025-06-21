package com.dogood.handsfreeprofile

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dogood.handsfreeprofile.model.CarKitUiState // Ensure this import is correct
import com.dogood.handsfreeprofile.repository.CarKitViewModel // Ensure this import is correct
import com.dogood.handsfreeprofile.service.HfpAgentService
import com.dogood.handsfreeprofile.ui.theme.HandsFreeProfileTheme

class MainActivity : ComponentActivity() {

    private val carKitViewModel: CarKitViewModel by viewModels()
    private var hfpAgentService: HfpAgentService? = null
    private var isBound = false

    // Consolidated permission launcher
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allGranted = true
            permissions.entries.forEach {
                Log.d("PermissionRequest_Activity", "${it.key} granted: ${it.value}")
                if (!it.value) allGranted = false
            }
            if (allGranted) {
                Log.d("PermissionRequest_Activity", "All required Bluetooth permissions granted.")
                // ViewModel will get updated via service's hasRequiredPermissions -> reportBluetoothPermissionsGranted
                // If service is running or needs to start, it will proceed.
                // We might want to explicitly tell the ViewModel/Service to re-check or proceed.
                carKitViewModel.requestStartListeningForConnections()
            } else {
                Log.w("PermissionRequest_Activity", "Not all Bluetooth permissions were granted.")
                // ViewModel's hasBluetoothPermissions LiveData will be updated by the service
                // The UI should react accordingly.
            }
        }

    private val requestBluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("BluetoothEnable_Activity", "Bluetooth successfully enabled by user.")
                // Service's BroadcastReceiver will handle STATE_ON and update repository
            } else {
                Log.d(
                    "BluetoothEnable_Activity",
                    "User did not enable Bluetooth or an error occurred."
                )
                // Repository's isBluetoothEnabled will remain false or be updated by BroadcastReceiver
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HfpAgentService.LocalBinder
            hfpAgentService = binder.getService()
            carKitViewModel.setBoundService(hfpAgentService)
            isBound = true
            Log.d("MainActivity_Service", "HfpAgentService connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            carKitViewModel.setBoundService(null)
            hfpAgentService = null
            isBound = false
            Log.d("MainActivity_Service", "HfpAgentService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HandsFreeProfileTheme {
                // Observe states from CarKitViewModel
                val uiState by carKitViewModel.uiState.observeAsState(CarKitUiState.LOADING)
                val isBluetoothEnabled by carKitViewModel.isBluetoothEnabled.observeAsState(false)
                val hasPermissions by carKitViewModel.hasBluetoothPermissions.observeAsState(false)
                val connectedDeviceName by carKitViewModel.connectedDeviceName.observeAsState()
                val incomingCallNumber by carKitViewModel.incomingCallNumber.observeAsState()
                val serviceError by carKitViewModel.serviceErrorMessage.observeAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CarKitStatusScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = uiState,
                        isBluetoothEnabled = isBluetoothEnabled,
                        hasBluetoothPermissions = hasPermissions,
                        connectedDeviceName = connectedDeviceName,
                        incomingCallNumber = incomingCallNumber,
                        serviceErrorMessage = serviceError,
                        onStartService = {
                            checkPermissionsAndStartService()
                        },
                        onStopService = {
                            stopHfpServiceInternal()
                        },
                        onRequestPermissions = {
                            requestBluetoothPermissions()
                        },
                        onRequestEnableBluetooth = {
                            enableBluetooth()
                        },
                        onAnswerCall = { carKitViewModel.answerCall() },
                        onHangUpCall = { carKitViewModel.hangUpCall() },
                        onClearError = { carKitViewModel.clearServiceError() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service when activity starts if permissions are granted.
        // If not, user will be prompted, and binding can happen after grant.
        // Start service explicitly if not already running and conditions met.
        // The service itself will manage its foreground state.
        checkPermissionsAndStartService(bindOnly = false) // Start and bind
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            carKitViewModel.setBoundService(null) // Important for ViewModel to know
            unbindService(serviceConnection)
            isBound = false
            Log.d("MainActivity_Service", "Unbound from HfpAgentService in onStop.")
        }
        // Decide if service should stop when activity is not visible.
        // For a persistent HFP agent, you might not want to stop it here.
        // If you want to stop it:
        // if (!isChangingConfigurations) { // Don't stop on config changes like rotation
        //     stopHfpServiceInternal()
        // }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableListOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN, // Needed for discovering devices, potentially by service
                Manifest.permission.BLUETOOTH_ADVERTISE // Needed if service advertises
            ).apply {
                // Add BLUETOOTH_ADVERTISE only if your service truly acts as a discoverable GATT server
                // For HFP RFCOMM, listenUsingRfcommWithServiceRecord doesn't strictly need ADVERTISE for the server role.
                // However, if your service needs to be discoverable for other reasons, include it.
                // For now, let's assume it might be needed for full functionality or future expansion.
                add(Manifest.permission.BLUETOOTH_ADVERTISE)

                // Android 12 (S) introduced a requirement for POST_NOTIFICATIONS for foreground services
                // targeting SDK 33+ (TIRAMISU)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    applicationInfo.targetSdkVersion >= Build.VERSION_CODES.TIRAMISU
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }

            }.toTypedArray()
        } else {
            // Pre-S permissions are granted at install time if declared in Manifest
            // but good practice to check if a specific operation might still fail.
            // For general service operation, these are the main ones.
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
                // ACCESS_FINE_LOCATION might be needed for Bluetooth scans on some older APIs
                // if (! (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
                //    add(Manifest.permission.ACCESS_FINE_LOCATION)
                // }
            )
        }
    }

    private fun checkPermissionsAndStartService(bindOnly: Boolean = false) {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            Log.d("MainActivity_Perms", "All Bluetooth permissions already granted.")
            // Service internally updates HfpStateRepository about permission status via BluetoothUtils.hasRequiredPermissions(this)
            // which in turn updates carKitViewModel.hasBluetoothPermissions
            // Here, we can directly inform the viewModel, though the service will also do it.
            // carKitViewModel.hfpStateRepository.reportBluetoothPermissionsGranted(true) // Redundant if service does it on start

            if (!bindOnly) {
                startHfpServiceInternal()
            }
            bindToHfpService()

        } else {
            Log.d("MainActivity_Perms", "Requesting Bluetooth permissions.")
            // Repository will be updated when service checks permissions, or after user grants them.
            // carKitViewModel.hfpStateRepository.reportBluetoothPermissionsGranted(false)
            requestBluetoothPermissions() // This will trigger the permission flow
        }
    }


    private fun requestBluetoothPermissions() {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            // Log which permissions are being requested
            permissionsToRequest.forEach { Log.d("PermissionRequest_Activity", "Requesting: $it") }

            // Show rationale if needed for any of the permissions
            var showRationale = false
            for (permission in permissionsToRequest) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (shouldShowRequestPermissionRationale(permission)) {
                        showRationale = true
                        Log.d("PermissionRequest_Activity", "Showing rationale for $permission")
                        // Here you would typically show a dialog explaining why the permissions are needed.
                        // For simplicity, we'll proceed directly to request.
                        break
                    }
                }
            }
            // Launch the permission request
            requestMultiplePermissionsLauncher.launch(permissionsToRequest)
        } else {
            Log.d(
                "PermissionRequest_Activity",
                "All necessary permissions already granted (requestBluetoothPermissions)."
            )
            // This case should ideally be caught by checkPermissionsAndStartService,
            // but as a fallback, ensure service starts/binds if all good.
            if (isBluetoothAdapterEnabled()) {
                startHfpServiceInternal()
                bindToHfpService()
            } else {
                enableBluetooth()
            }
        }
    }


    private fun isBluetoothAdapterEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val bluetoothAdapter = bluetoothManager?.adapter
        return bluetoothAdapter?.isEnabled == true
    }

    private fun enableBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e("BluetoothEnable_Activity", "Bluetooth not supported on this device.")
            // Update ViewModel/Repository: carKitViewModel.hfpStateRepository.reportServiceError("BT not supported")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            // Check for BLUETOOTH_CONNECT permission before attempting to enable Bluetooth on S+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    requestBluetoothEnableLauncher.launch(enableBtIntent)
                } else {
                    Log.w(
                        "BluetoothEnable_Activity",
                        "BLUETOOTH_CONNECT permission needed to request enabling Bluetooth."
                    )
                    // Request the permission first, then the user can try enabling Bluetooth again.
                    requestBluetoothPermissions()
                }
            } else {
                // No special permission needed for ACTION_REQUEST_ENABLE on pre-S
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                requestBluetoothEnableLauncher.launch(enableBtIntent)
            }
        } else {
            Log.d("BluetoothEnable_Activity", "Bluetooth is already enabled.")
            // If BT is on, and we intended to start service, ensure permissions and start.
            checkPermissionsAndStartService()
        }
    }

    private fun startHfpServiceInternal() {
        if (!isBluetoothAdapterEnabled()) {
            Log.w("HfpService_Activity", "Bluetooth not enabled. Cannot start service yet.")
            // UI should reflect this via isBluetoothEnabled LiveData.
            // User will be prompted to enable Bluetooth if they try an action that needs it.
            return
        }

        // Permissions are checked by checkPermissionsAndStartService before calling this
        // but as a safeguard or if called directly:
        val requiredPermissions = getRequiredBluetoothPermissions()
        val allPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allPermissionsGranted) {
            Log.w(
                "HfpService_Activity",
                "Required permissions not granted. Cannot start service yet."
            )
            requestBluetoothPermissions()
            return
        }

        Log.d("HfpService_Activity", "Attempting to start HFP Service.")
        val serviceIntent = Intent(this, HfpAgentService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i("HfpService_Activity", "HFP Service start command sent.")
            // Binding will happen via onStart or if checkPermissionsAndStartService calls bindToHfpService
            if (!isBound) { // Bind if not already trying to bind
                bindToHfpService()
            }
        } catch (e: Exception) {
            Log.e("HfpService_Activity", "Error starting HFP Service: ${e.message}", e)
            carKitViewModel.reportServiceErrorInRepository("Failed to start service: ${e.message}")
        }
    }

    private fun bindToHfpService() {
        if (!isBound) {
            Log.d("MainActivity_Service", "Attempting to bind to HfpAgentService.")
            val serviceIntent = Intent(this, HfpAgentService::class.java)
            // BIND_AUTO_CREATE will also start the service if it's not already running
            // and BluetoothUtils.hasRequiredPermissions(this) in service onCreate will report to ViewModel
            val success = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!success) {
                Log.e("MainActivity_Service", "Failed to bind to HfpAgentService.")
                // If binding fails, it might mean the service couldn't be started.
                // This could be due to manifest issues or other system problems.
                // The service's onCreate/onStartCommand should log errors if it fails to initialize.
                // We can also report a generic error here.
                carKitViewModel.reportServiceErrorInRepository("Failed to bind to the HFP service.")

            } else {
                Log.d("MainActivity_Service", "Successfully initiated binding to HfpAgentService.")
            }
        } else {
            Log.d("MainActivity_Service", "Already bound or binding in progress.")
        }
    }

    private fun stopHfpServiceInternal() {
        Log.d("HfpService_Activity", "Attempting to stop HFP Service.")
        if (isBound) {
            carKitViewModel.setBoundService(null) // Let ViewModel know service is going away
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w("HfpService_Activity", "Service not registered to unbind: ${e.message}")
            }
            isBound = false
        }
        val serviceIntent = Intent(this, HfpAgentService::class.java)
        stopService(serviceIntent)
        Log.i("HfpService_Activity", "HFP Service stop command sent.")
        // The service's onDestroy should clean up and update repository if necessary.
        // For example, setting state to SERVICE_STOPPED.
        // We can also directly update the ViewModel here for immediate UI feedback.
        carKitViewModel.updateUiStateInRepository(CarKitUiState.SERVICE_STOPPED)
    }
}

@Composable
fun CarKitStatusScreen(
    modifier: Modifier = Modifier,
    uiState: CarKitUiState,
    isBluetoothEnabled: Boolean,
    hasBluetoothPermissions: Boolean,
    connectedDeviceName: String?,
    incomingCallNumber: String?,
    serviceErrorMessage: String?,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestEnableBluetooth: () -> Unit,
    onAnswerCall: () -> Unit,
    onHangUpCall: () -> Unit,
    onClearError: () -> Unit
) {
    val context = LocalContext.current
    // Determine overall service status (simplified)
    val isServiceConsideredRunning = when (uiState) {
        CarKitUiState.LOADING,
        CarKitUiState.LISTENING_FOR_CONNECTIONS,
        CarKitUiState.PHONE_CONNECTING,
        CarKitUiState.PHONE_CONNECTED,
        CarKitUiState.CALL_INCOMING,
        CarKitUiState.CALL_IN_PROGRESS -> true

        else -> false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bluetooth Status Icon and Text
        val btIcon: ImageVector
        val btStatusText: String

        when {
            !isBluetoothEnabled -> {
                btIcon = Icons.Filled.BluetoothDisabled
                btStatusText = "Bluetooth is Disabled"
            }

            !hasBluetoothPermissions -> {
                btIcon = Icons.Filled.Warning // Or a specific permission icon
                btStatusText = "Bluetooth Permissions Needed"
            }

            uiState == CarKitUiState.PHONE_CONNECTED || uiState == CarKitUiState.CALL_INCOMING || uiState == CarKitUiState.CALL_IN_PROGRESS -> {
                btIcon = Icons.Filled.BluetoothConnected
                btStatusText = connectedDeviceName?.let { "Connected to $it" } ?: "Phone Connected"
            }

            uiState == CarKitUiState.LISTENING_FOR_CONNECTIONS -> {
                btIcon = Icons.Filled.Hearing // Or BluetoothSearching
                btStatusText = "Listening for connections"
            }

            uiState == CarKitUiState.PHONE_CONNECTING -> {
                btIcon = Icons.AutoMirrored.Filled.BluetoothSearching
                btStatusText = "Phone connecting..."
            }

            else -> { // Default: Bluetooth enabled, permissions granted, but not connected/listening (e.g., service stopped, loading)
                btIcon = Icons.Filled.Bluetooth
                btStatusText = "Bluetooth Enabled"
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = btIcon,
                contentDescription = "Bluetooth Status",
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(btStatusText, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons based on state
        if (!isBluetoothEnabled) {
            Button(onClick = onRequestEnableBluetooth) {
                Text("Enable Bluetooth")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (isBluetoothEnabled && !hasBluetoothPermissions) {
            Button(onClick = onRequestPermissions) {
                Text("Grant Bluetooth Permissions")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Service Control Buttons
        if (isBluetoothEnabled && hasBluetoothPermissions) {
            if (isServiceConsideredRunning) {
                Button(
                    onClick = onStopService,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop HFP Service")
                }
            } else {
                Button(onClick = onStartService) {
                    Text("Start HFP Service")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }


        // Call Status and Controls
        if (uiState == CarKitUiState.CALL_INCOMING) {
            Text(
                "Incoming Call: ${incomingCallNumber ?: "Unknown"}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = onAnswerCall,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.Call, contentDescription = "Answer")
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Answer")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onHangUpCall,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Reject")
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Reject")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (uiState == CarKitUiState.CALL_IN_PROGRESS) {
            Text(
                "Call in Progress with ${connectedDeviceName ?: "Device"}",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onHangUpCall,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "Hang Up")
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Hang Up")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Display Service Error Message
        serviceErrorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Service Error:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onClearError,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onError,
                            contentColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }

        // General UI State Text for debugging or information
        Spacer(modifier = Modifier.weight(1f)) // Push to bottom
        Text("Current Overall State: ${uiState.name}", style = MaterialTheme.typography.bodySmall)

    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun DefaultCarKitPreview() {
    HandsFreeProfileTheme {
        CarKitStatusScreen(
            uiState = CarKitUiState.LISTENING_FOR_CONNECTIONS,
            isBluetoothEnabled = true,
            hasBluetoothPermissions = true,
            connectedDeviceName = null,
            incomingCallNumber = null,
            serviceErrorMessage = null,
            onStartService = {},
            onStopService = {},
            onRequestPermissions = {},
            onRequestEnableBluetooth = {},
            onAnswerCall = {},
            onHangUpCall = {},
            onClearError = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun IncomingCallPreview() {
    HandsFreeProfileTheme {
        CarKitStatusScreen(
            uiState = CarKitUiState.CALL_INCOMING,
            isBluetoothEnabled = true,
            hasBluetoothPermissions = true,
            connectedDeviceName = "Pixel Phone",
            incomingCallNumber = "123-456-7890",
            serviceErrorMessage = null,
            onStartService = {},
            onStopService = {},
            onRequestPermissions = {},
            onRequestEnableBluetooth = {},
            onAnswerCall = {},
            onHangUpCall = {},
            onClearError = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun NoPermissionsPreview() {
    HandsFreeProfileTheme {
        CarKitStatusScreen(
            uiState = CarKitUiState.NO_BLUETOOTH_PERMISSION, // Or reflect via hasBluetoothPermissions = false
            isBluetoothEnabled = true,
            hasBluetoothPermissions = false,
            connectedDeviceName = null,
            incomingCallNumber = null,
            serviceErrorMessage = "Please grant permissions to use the car kit features.",
            onStartService = {},
            onStopService = {},
            onRequestPermissions = {},
            onRequestEnableBluetooth = {},
            onAnswerCall = {},
            onHangUpCall = {},
            onClearError = {}
        )
    }
}