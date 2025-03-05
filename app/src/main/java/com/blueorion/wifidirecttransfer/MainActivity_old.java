package com.blueorion.wifidirecttransfer;

/*

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity_old extends AppCompatActivity implements WifiP2pManager.PeerListListener,
        WifiP2pManager.ConnectionInfoListener {

    private static final String TAG = "WifiDirectTransfer";
    private static final int PERMISSIONS_REQUEST_CODE = 101;
    private static final String PREFS_NAME = "WifiDirectPrefs";
    private static final String KEY_LAST_CONNECTED = "lastConnectedDevice";

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private List<WifiP2pDevice> peers = new ArrayList<>();
    private Map<String, WifiP2pDevice> deviceMap = new HashMap<>();
    private List<String> deviceNames = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private TextView connectionStatusText;
    private Button discoverButton;
    private Button syncButton;
    private ListView deviceList;

    private WifiP2pDevice connectedDevice;
    private boolean isHost = false;
    private String lastConnectedDeviceAddress;
    private FileTransferService transferService;

    // Handler for rediscovery timeouts
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable discoverPeersRunnable;
    private boolean isDiscovering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "onCreate started");

        // Initialize UI components
        connectionStatusText = findViewById(R.id.connection_status);
        discoverButton = findViewById(R.id.discover_button);
        syncButton = findViewById(R.id.sync_button);
        deviceList = findViewById(R.id.device_list);

        // Initialize adapter
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceNames);
        deviceList.setAdapter(adapter);

        // Request required permissions
        requestPermissions();

        // Initialize Wi-Fi Direct
        initializeWifiDirect();

        // Set click listeners
        setupListeners();

        // Initialize file transfer service
        transferService = new FileTransferService(this);

        // Load last connected device
        loadLastConnectedDevice();

        // Set up discovery runnable for auto-retry
        discoverPeersRunnable = new Runnable() {
            @Override
            public void run() {
                if (isDiscovering) {
                    Log.d(TAG, "Auto-retrying peer discovery");
                    startDiscovery();
                    // Schedule again after 30 seconds
                    handler.postDelayed(this, 30000);
                }
            }
        };

        Log.d(TAG, "onCreate completed");
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.INTERNET
        };

        if (!Environment.isExternalStorageManager()) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
                Toast.makeText(this, "Please grant 'All files access' permission", Toast.LENGTH_LONG).show();

                return;
            } catch (Exception e) {
                Toast.makeText(this, "Error opening permission settings", Toast.LENGTH_LONG).show();

                return;
            }
        }

        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSIONS_REQUEST_CODE);
        }
    }

    private void initializeWifiDirect() {
        Log.d(TAG, "Initializing Wi-Fi Direct");

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            Log.e(TAG, "Cannot get Wi-Fi P2P service");
            Toast.makeText(this, "Your device doesn't support Wi-Fi Direct", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        channel = manager.initialize(this, getMainLooper(), null);
        if (channel == null) {
            Log.e(TAG, "Cannot initialize Wi-Fi P2P channel");
            Toast.makeText(this, "Failed to initialize Wi-Fi Direct", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        Log.d(TAG, "Wi-Fi Direct initialized");
    }

    private void setupListeners() {
        Log.d(TAG, "Setting up button listeners");

        discoverButton.setOnClickListener(v -> {
            Log.d(TAG, "Discover button clicked");
            if (!isDiscovering) {
                isDiscovering = true;
                startDiscovery();
                // Schedule auto-retry
                handler.postDelayed(discoverPeersRunnable, 30000);
                discoverButton.setText("Stop Discovery");
            } else {
                isDiscovering = false;
                stopDiscovery();
                discoverButton.setText("Discover Devices");
            }
        });

        syncButton.setOnClickListener(v -> {
            Log.d(TAG, "Sync button clicked");
            if (connectedDevice != null) {
                syncFiles();
            } else {
                Toast.makeText(this, "Not connected to any device", Toast.LENGTH_SHORT).show();
            }
        });

        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= deviceNames.size()) {
                Log.e(TAG, "Invalid position selected: " + position);
                return;
            }

            String deviceEntry = deviceNames.get(position);
            final WifiP2pDevice device = deviceMap.get(deviceEntry);
            if (device != null) {
                Log.d(TAG, "Selected device: " + device.deviceName + ", Address: " + device.deviceAddress);
                connectToDevice(device);
            } else {
                Log.e(TAG, "Device is null for entry: " + deviceEntry);
            }
        });
    }

    private void startDiscovery() {
        Log.d(TAG, "Starting peer discovery");
        connectionStatusText.setText("Searching for devices...");

        // Clear previous results
        peers.clear();
        deviceMap.clear();
        deviceNames.clear();
        adapter.notifyDataSetChanged();

        // Check for location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted, cannot discover peers");
            Toast.makeText(this, "Location permission required for device discovery", Toast.LENGTH_LONG).show();
            connectionStatusText.setText("Permission error: Location permission required");
            return;
        }

        // Start discovery
        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discovery initiated successfully");
                Toast.makeText(MainActivity.this, "Discovery started", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                String reasonText = getFailureReason(reason);
                Log.e(TAG, "Discovery failed: " + reasonText);
                Toast.makeText(MainActivity.this, "Discovery failed: " + reasonText, Toast.LENGTH_SHORT).show();
                connectionStatusText.setText("Failed to discover devices: " + reasonText);
                isDiscovering = false;
                discoverButton.setText("Discover Devices");
            }
        });
    }

    private void stopDiscovery() {
        Log.d(TAG, "Stopping peer discovery");
        handler.removeCallbacks(discoverPeersRunnable);

        if (manager != null && channel != null) {
            manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Discovery stopped successfully");
                }

                @Override
                public void onFailure(int reason) {
                    Log.e(TAG, "Failed to stop discovery: " + getFailureReason(reason));
                }
            });
        }
    }

    private void connectToDevice(final WifiP2pDevice device) {
        Log.d(TAG, "Connecting to device: " + device.deviceName);
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        connectionStatusText.setText("Connecting to " + device.deviceName + "...");

        // Check for location permission (required for Wi-Fi Direct operations)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted, cannot connect");
            Toast.makeText(this, "Location permission required for connection", Toast.LENGTH_LONG).show();
            connectionStatusText.setText("Permission error: Location permission required");
            return;
        }

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Connection initiated successfully");
                Toast.makeText(MainActivity.this, "Connection initiated", Toast.LENGTH_SHORT).show();
                // The actual connection result will be handled in the broadcast receiver
            }

            @Override
            public void onFailure(int reason) {
                String reasonText = getFailureReason(reason);
                Log.e(TAG, "Connection failed: " + reasonText);
                Toast.makeText(MainActivity.this, "Connection failed: " + reasonText, Toast.LENGTH_SHORT).show();
                connectionStatusText.setText("Failed to connect: " + reasonText);
            }
        });
    }

    private void syncFiles() {
        Log.d(TAG, "Starting file sync");
        if (connectedDevice == null) {
            Log.e(TAG, "Not connected to a device");
            Toast.makeText(this, "Not connected to a device", Toast.LENGTH_SHORT).show();
            return;
        }

        // For simplicity, we'll just sync the first file in the DCIM/Camera folder
        File cameraDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "Camera");

        Log.d(TAG, "Camera directory path: " + cameraDir.getAbsolutePath());

        if (!cameraDir.exists() || !cameraDir.isDirectory()) {
            Log.e(TAG, "Camera directory not found: " + cameraDir.getAbsolutePath());
            Toast.makeText(this, "Camera directory not found", Toast.LENGTH_SHORT).show();
            return;
        }

        File[] files = cameraDir.listFiles();
        if (files == null || files.length == 0) {
            Log.e(TAG, "No files found in Camera folder");
            Toast.makeText(this, "No files found in Camera folder", Toast.LENGTH_SHORT).show();
            return;
        }

        // Find the first file (could be enhanced to pick specific types)
        File fileToTransfer = null;
        for (File file : files) {
            if (file.isFile() && (file.getName().endsWith(".jpg") ||
                    file.getName().endsWith(".jpeg") ||
                    file.getName().endsWith(".png") ||
                    file.getName().endsWith(".mp4"))) {
                fileToTransfer = file;
                Log.d(TAG, "Found file to transfer: " + file.getName());
                break;
            }
        }

        if (fileToTransfer == null) {
            Log.e(TAG, "No image or video files found in Camera folder");
            Toast.makeText(this, "No image or video files found in Camera folder", Toast.LENGTH_SHORT).show();
            return;
        }

        // Initiate the transfer
        Log.d(TAG, "Initiating file transfer for: " + fileToTransfer.getName());
        transferService.sendFile(fileToTransfer);
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peerList) {
        Log.d(TAG, "onPeersAvailable called, devices found: " + peerList.getDeviceList().size());

        peers.clear();
        deviceMap.clear();
        deviceNames.clear();

        for (WifiP2pDevice device : peerList.getDeviceList()) {
            peers.add(device);

            Log.d(TAG, "Found device: " + device.deviceName + ", Address: " + device.deviceAddress);

            // Format device name to show host/client status
            String displayName = device.deviceName;
            boolean isDeviceHost = displayName.contains("Backup");
            String statusLabel = isDeviceHost ? " (Host)" : " (Client)";
            String colorCode = isDeviceHost ? "🟢 " : "🔵 "; // Green for host, blue for client

            String formattedName = colorCode + displayName + statusLabel;
            deviceNames.add(formattedName);
            deviceMap.put(formattedName, device);

            // If this is our last connected device, reconnect
            if (device.deviceAddress.equals(lastConnectedDeviceAddress)) {
                Log.d(TAG, "Found last connected device, attempting reconnection");
                connectToDevice(device);
            }
        }

        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();

            if (peers.isEmpty()) {
                Log.d(TAG, "No devices found");
                connectionStatusText.setText("No devices found");
                Toast.makeText(MainActivity.this, "No devices found", Toast.LENGTH_SHORT).show();
            } else {
                connectionStatusText.setText("Found " + peers.size() + " device(s)");
            }
        });
    }

    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        Log.d(TAG, "onConnectionInfoAvailable: groupFormed=" + info.groupFormed +
                ", isGroupOwner=" + info.isGroupOwner);

        if (info.groupFormed) {
            // We're connected!

            // Determine if we're the host
            isHost = info.isGroupOwner;

            if (connectedDevice != null) {
                String hostStatus = isHost ? "Host" : "Client";
                String statusText = "Connected to " + connectedDevice.deviceName + " as " + hostStatus;
                Log.d(TAG, statusText);

                runOnUiThread(() -> {
                    connectionStatusText.setText(statusText);

                    // Enable/disable sync button based on connection type
                    syncButton.setEnabled(!isHost); // Only clients can sync to hosts
                });

                // Save the connected device
                saveLastConnectedDevice(connectedDevice.deviceAddress);

                // Stop discovery when connected
                isDiscovering = false;
                stopDiscovery();
                runOnUiThread(() -> discoverButton.setText("Discover Devices"));
            }

            // Set up socket connections accordingly
            if (isHost) {
                Log.d(TAG, "This device is the group owner, starting server");
                // Start server to receive files
                transferService.startReceiving(info.groupOwnerAddress);
            } else {
                Log.d(TAG, "This device is a client, preparing to send files to " + info.groupOwnerAddress.getHostAddress());
                // Configure client for sending files
                transferService.prepareToSend(info.groupOwnerAddress);
            }
        } else {
            Log.d(TAG, "Disconnected");
            runOnUiThread(() -> {
                connectionStatusText.setText("Disconnected");
                syncButton.setEnabled(false);
            });
            connectedDevice = null;
        }
    }

    private void saveLastConnectedDevice(String deviceAddress) {
        Log.d(TAG, "Saving last connected device: " + deviceAddress);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_LAST_CONNECTED, deviceAddress);
        editor.apply();
    }

    private void loadLastConnectedDevice() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        lastConnectedDeviceAddress = prefs.getString(KEY_LAST_CONNECTED, null);
        Log.d(TAG, "Loaded last connected device: " + lastConnectedDeviceAddress);
    }

    private String getFailureReason(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P unsupported on this device";
            case WifiP2pManager.BUSY:
                return "System is busy, try again later";
            case WifiP2pManager.ERROR:
                return "Operation failed due to an internal error";
            default:
                return "Unknown error (code " + reason + ")";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume: Registering receiver");
        registerReceiver(receiver, intentFilter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: Unregistering receiver");
        unregisterReceiver(receiver);
        stopDiscovery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopDiscovery();
        if (transferService != null) {
            transferService.stop();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            boolean allGranted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    Log.e(TAG, "Permission denied: " + permissions[i]);
                }
            }

            if (!allGranted) {
                Log.e(TAG, "Not all permissions were granted");
                Toast.makeText(this, "App requires all permissions to function properly", Toast.LENGTH_LONG).show();
            } else {
                Log.d(TAG, "All permissions granted");
            }
        }
    }

    // Method to be called from the broadcast receiver to update connected device
    public void setConnectedDevice(WifiP2pDevice device) {
        Log.d(TAG, "Setting connected device: " + (device != null ? device.deviceName : "null"));
        this.connectedDevice = device;
    }

    // Method to update the peer list from broadcast receiver
    public void requestPeers() {
        Log.d(TAG, "Requesting peers from manager");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission not granted, cannot request peers");
            return;
        }
        manager.requestPeers(channel, this);
    }
}

 */