package com.blueorion.wifidirecttransfer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;


public class MainActivity extends AppCompatActivity  implements WifiP2pManager.ConnectionInfoListener {
    private static final String TAG = "WiFiDirectTransfer";
    private static final int PORT = 8888;
    private static final int PERMISSION_REQUEST_CODE = 100;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;
    private WifiP2pInfo connectionInfo;

    private List<WifiP2pDevice> peers = new ArrayList<>();
    private PeerListAdapter peerListAdapter;

    private FileSyncManager fileSyncManager;

    private boolean isDiscovering = false;

    private Button discoverButton;
    private Button syncFilesButton;
    private Button startServerButton;
    private ListView peerListView;


    private TextView tvSyncInfo;
    private ProgressBar progressBar;
    private LinearLayout syncLayout;


    private List<String> deviceNames = new ArrayList<>();

    private WifiP2pDevice connectedDevice = null;
    private boolean isConnected = false;
    private String hostAddress;
    private boolean isHost = false; // is this device a HOST or CLIENT?

    private final String[] requiredPermissions = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.INTERNET
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        discoverButton = findViewById(R.id.btn_discover);
        syncFilesButton = findViewById(R.id.btn_sync_files);
        startServerButton = findViewById(R.id.btn_start_server);
        peerListView = findViewById(R.id.list_peers);

        // Initialize WiFi P2P components
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        // Create intent filter for WiFi P2P broadcasts
        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        // Initialize adapter for peer list
        peerListAdapter = new PeerListAdapter(this, peers);
        peerListView.setAdapter(peerListAdapter);

        // Set up click listeners
        setupClickListeners();

        // Disable buttons initially until permissions are checked
        discoverButton.setEnabled(false);
        syncFilesButton.setEnabled(false);
        startServerButton.setEnabled(false);

        // Check and request permissions
        // This will then set isHost and update UI
        requestPermissionsIfNeeded();


        fileSyncManager = new FileSyncManager(this);
        //fileSyncManager.setListener((FileSyncManager.FileSyncListener) this);
        fileSyncManager.setListener(new FileSyncManager.FileSyncListener() {
            @Override
            public void onTransferProgress(int progress, int total) {

            }

            @Override
            public void onFileTransferred(String fileName) {

            }

            @Override
            public void onTransferComplete(int fileCount) {

            }

            @Override
            public void onTransferError(String errorMessage) {

            }

            @Override
            public void onFileSyncStarted() {
                // Implement what should happen when file sync starts
                Log.d(TAG, "File sync started");
            }

            @Override
            public void onFileSyncProgress(int progress) {
                // Implement what should happen when file sync progress changes
                Log.d(TAG, "File sync progress: " + progress);
            }

            @Override
            public void onFileSyncCompleted() {
                // Implement what should happen when file sync is completed
                Log.d(TAG, "File sync completed");
            }
        });

    }

    // ***********************************//
    // ****    EVENTS & BUTTONS      *****//
    // ***********************************//



    private void setupClickListeners() {
        // Discover button click listener
        discoverButton.setOnClickListener(v -> {
            if (isDiscovering) {
                stopDiscovery();
            } else {
                startDiscovery();
            }
        });

        // Peer list item click listener
        peerListView.setOnItemClickListener((parent, view, position, id) -> {
            WifiP2pDevice device = peers.get(position);

            // Check if already connected to this device
            if (connectedDevice != null && connectedDevice.deviceAddress.equals(device.deviceAddress)) {
                Log.d(TAG, "Already connected to " + device.deviceName);
                Toast.makeText(MainActivity.this, "Already connected to " + device.deviceName, Toast.LENGTH_SHORT).show();
                return;
            }

            connectToPeer(device);
        });

        // Sync files button click listener
        syncFilesButton.setOnClickListener(v -> startFileSync());


        // Start Server
        startServerButton.setOnClickListener(v -> {
            if (hostAddress == null) {
                fileSyncManager.startServer(hostAddress);
            } else {
                Toast.makeText(MainActivity.this, "Cannot start server! hostAddress is not set.", Toast.LENGTH_SHORT).show();
            }
        });

    }


    // ***********************************//
    // ****    PERMISSIONS           *****//
    // ***********************************//

    private void requestPermissionsIfNeeded() {
        List<String> permissionsToRequest = new ArrayList<>();

        // FIrst, make sure we request full access to filesystem.
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

        for (String permission : requiredPermissions) {
            // Skip NEARBY_WIFI_DEVICES permission for Android < 13
            if (permission.equals(Manifest.permission.NEARBY_WIFI_DEVICES) &&
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                continue;
            }

            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
            Log.d(TAG, "Requesting permissions: " + permissionsToRequest);
        } else {
            Log.d(TAG, "All permissions are already granted");
            // All permissions granted, initialize WiFi P2P functionality
            initializeWifiP2p();
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            List<String> deniedPermissions = new ArrayList<>();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    deniedPermissions.add(permissions[i]);
                }
            }

            if (allGranted) {
                Log.d(TAG, "All permissions granted");
                // All permissions granted, initialize WiFi P2P functionality
                initializeWifiP2p();
            } else {
                Log.e(TAG, "Some permissions were denied: " + deniedPermissions);
                // Show explanation dialog for critical permissions
                showPermissionExplanationDialog(deniedPermissions);
            }
        }
    }

    private void showPermissionExplanationDialog(List<String> deniedPermissions) {
        StringBuilder message = new StringBuilder("WiFi Direct requires the following permissions to function properly:\n\n");

        for (String permission : deniedPermissions) {
            message.append("• ").append(getReadablePermissionName(permission)).append("\n");
        }

        message.append("\nPlease grant these permissions in Settings to use the app.");

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage(message.toString())
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    // Open app settings so user can grant permissions
                    Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    // Show limited functionality message
                    Toast.makeText(this, "App will have limited functionality without required permissions",
                            Toast.LENGTH_LONG).show();

                    // Disable buttons that require permissions
                    updateUIForMissingPermissions();
                })
                .setCancelable(false)
                .show();
    }

    private String getReadablePermissionName(String permission) {
        switch (permission) {
            case Manifest.permission.ACCESS_FINE_LOCATION:
                return "Location (needed to discover nearby devices)";
            case Manifest.permission.NEARBY_WIFI_DEVICES:
                return "Nearby WiFi Devices (needed for WiFi Direct)";
            case Manifest.permission.ACCESS_WIFI_STATE:
                return "WiFi State (needed to manage WiFi)";
            case Manifest.permission.CHANGE_WIFI_STATE:
                return "Change WiFi State (needed to enable WiFi Direct)";
            case Manifest.permission.INTERNET:
                return "Internet (needed for data transfer)";
            case Manifest.permission.ACCESS_NETWORK_STATE:
                return "Network State (needed to monitor connections)";
            default:
                return permission.substring(permission.lastIndexOf('.') + 1);
        }
    }

    private void updateUIForMissingPermissions() {
        // Disable buttons that require permissions
        discoverButton.setEnabled(false);
        syncFilesButton.setEnabled(false);

        // Show message in the peer list
        TextView emptyView = new TextView(this);
        emptyView.setText("Permissions required to discover peers");
        emptyView.setGravity(android.view.Gravity.CENTER);
        emptyView.setPadding(20, 20, 20, 20);

        ViewGroup parent = (ViewGroup) peerListView.getParent();
        parent.addView(emptyView);
        peerListView.setEmptyView(emptyView);
    }

    // New method to check permissions before performing operations
    private boolean checkPermissions() {
        List<String> missingPermissions = new ArrayList<>();

        for (String permission : requiredPermissions) {
            // Skip NEARBY_WIFI_DEVICES permission for Android < 13
            if (permission.equals(Manifest.permission.NEARBY_WIFI_DEVICES) &&
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                continue;
            }

            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }

        if (!missingPermissions.isEmpty()) {
            Log.d(TAG, "Missing permissions: " + missingPermissions);

            // Don't show the dialog multiple times
            // If the user has clicked "deny" and checked "Don't ask again", we should respect that
            boolean shouldShowRationale = false;
            for (String permission : missingPermissions) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    shouldShowRationale = true;
                    break;
                }
            }

            if (shouldShowRationale) {
                // Only show the explanation dialog if we can show a rationale
                showPermissionExplanationDialog(missingPermissions);
            } else {
                // Update UI to reflect missing permissions
                updateUIForMissingPermissions();
            }

            return false;
        }

        return true;
    }


    // ***********************************//
    // ****   WIFI DIRECT CONNECTION  ****//
    // ***********************************//

    @SuppressLint("MissingPermission")
    private void determineHostStatus() {
        if (!checkPermissions()) {
            Log.e(TAG, "Cannot determine Host or Client status due to missing permissions");
            return;
        }
        manager.requestDeviceInfo(channel, device -> {
            if (device != null && device.deviceName != null) {
                isHost = device.deviceName.contains("Backup");
                Log.d(TAG, "Device name: " + device.deviceName + ", isHost: " + isHost);

                // Update UI to reflect Host or Client status
                if (isHost) {
                    startServerButton.setVisibility(View.VISIBLE);
                    syncFilesButton.setVisibility(View.GONE);
                }

            }
        });
    }
    // Initialize WiFi P2P components after permissions are granted
    private void initializeWifiP2p() {
        determineHostStatus();
        // Enable UI elements
        discoverButton.setEnabled(true);

        // Check for existing connections
        if (manager != null && channel != null) {
            checkExistingConnections();
        }

        // IF HOST, start the server to receive files.
        if (isHost) {
            // Start the server
            //startServerButton.setVisibility(View.VISIBLE);
//            startServerButton.setOnClickListener(v -> {
//                // Get the device's IP address from your WifiP2pInfo
//                //String hostAddress = "192.168.49.1"; // Example address
//                fileSyncManager.startServer(hostAddress);
//            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register broadcast receiver
        receiver = new WiFiDirectBroadcastReceiver(manager, channel, this);
        registerReceiver(receiver, intentFilter);

        // Check for existing connections
        checkExistingConnections();
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Unregister broadcast receiver
        unregisterReceiver(receiver);
    }

    @SuppressLint("MissingPermission")
    private void startDiscovery() {
        // Perform a fresh permission check before starting discovery
        if (!checkPermissions()) {
            Log.e(TAG, "Cannot start discovery due to missing permissions");
            return;
        }

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discovery started successfully");
                isDiscovering = true;
                discoverButton.setText("Stop Discovery");
                Toast.makeText(MainActivity.this, "Discovery started", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Discovery failed with reason: " + getFailureReason(reason));

                if (reason == WifiP2pManager.P2P_UNSUPPORTED) {
                    // WiFi Direct not supported - show dialog and disable functionality
                    new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("WiFi Direct Not Supported")
                            .setMessage("This device does not support WiFi Direct. The app cannot function properly.")
                            .setPositiveButton("OK", null)
                            .show();
                    discoverButton.setEnabled(false);
                } else {
                    Toast.makeText(MainActivity.this, "Discovery failed: " + getFailureReason(reason),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
    private void stopDiscovery() {
        manager.stopPeerDiscovery(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Discovery stopped successfully");
                isDiscovering = false;
                discoverButton.setText("Discover Peers");
                Toast.makeText(MainActivity.this, "Discovery stopped", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Failed to stop discovery with reason: " + getFailureReason(reason));
                // Even if stop failed, update UI to reflect user intent
                isDiscovering = false;
                discoverButton.setText("Discover Peers");
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void connectToPeer(WifiP2pDevice device) {
        // Perform a fresh permission check before connecting
        if (!checkPermissions()) {
            Log.e(TAG, "Cannot connect due to missing permissions");
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC; // Push Button Configuration

        // Set groupOwnerIntent based on isHost
        // This is very important, because we only get the GroupOnwer address, so make sure it's the host.
        config.groupOwnerIntent = isHost ? 15 : 0;

        Log.d(TAG, "Attempting to connect to: " + device.deviceName);
        Toast.makeText(this, "Connecting to " + device.deviceName + "...", Toast.LENGTH_SHORT).show();

        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Connection initiated successfully to " + device.deviceName);
                // Connection process started, but actual connection is not established yet
                // The WIFI_P2P_CONNECTION_CHANGED_ACTION broadcast will be triggered when connected
            }

            @Override
            public void onFailure(int reason) {
                Log.e(TAG, "Connection failed with reason: " + getFailureReason(reason));

                // Special handling for certain failure cases
                if (reason == WifiP2pManager.BUSY) {
                    Toast.makeText(MainActivity.this,
                            "System is busy. Try stopping discovery first.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Connection failed: " + getFailureReason(reason),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void checkExistingConnections() {
        if (checkPermissions()) {
            manager.requestConnectionInfo(channel, this);
        }
    }

    // Handles peer list updates from the broadcast receiver
    public void updatePeers(WifiP2pDeviceList peerList) {
        peers.clear();
        peers.addAll(peerList.getDeviceList());

        Log.d(TAG, "Peer list updated with " + peers.size() + " peers");
        for (WifiP2pDevice device : peers) {
            Log.d(TAG, "Peer: " + device.deviceName + " [" + device.deviceAddress + "]");
        }

        peerListAdapter.notifyDataSetChanged();
    }

    // Handles device connection status updates from the broadcast receiver
    public void updateConnectionStatus(boolean isConnected, WifiP2pDevice device) {
        if (isConnected) {
            if (device != null) {
                connectedDevice = device;
                Log.d(TAG, "Connected to " + device.deviceName);

                // Update UI to reflect connection
                syncFilesButton.setEnabled(true);
                Toast.makeText(this, "Connected to " + device.deviceName, Toast.LENGTH_SHORT).show();

                // Update adapter to show connection status
                peerListAdapter.setConnectedDevice(device);
                peerListAdapter.notifyDataSetChanged();
            }
        } else {
            connectedDevice = null;
            syncFilesButton.setEnabled(false);

            // Update adapter to show no connected devices
            peerListAdapter.setConnectedDevice(null);
            peerListAdapter.notifyDataSetChanged();
        }
    }

    // Implementation of ConnectionInfoListener interface
    @Override
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        this.connectionInfo = info;
        // For Host, set the hostAddress.
        if (isHost && info.groupOwnerAddress != null) {
            hostAddress = info.groupOwnerAddress.getHostAddress();
            startServerButton.setEnabled(true); //.setVisibility(View.VISIBLE);
            Log.d(TAG, "Device is a HOST, hostAddress: " + hostAddress);
        } else if (info.groupFormed) {
            Log.d(TAG, "Connection info available, group formed: " + info.toString());
            // Group is formed, but we don't know which device yet
            // This will be updated when we receive the WIFI_P2P_THIS_DEVICE_CHANGED_ACTION broadcast
            // Enable the sync button as we are connected
            syncFilesButton.setEnabled(true);
        } else {
            Log.d(TAG, "Connection info available, no group formed");
            syncFilesButton.setEnabled(false);
        }
    }


    // Method to be called from the broadcast receiver to update connected device
    public void setConnectedDevice(WifiP2pDevice device) {
        Log.d(TAG, "Setting connected device: " + (device != null ? device.deviceName : "null"));
        this.connectedDevice = device;
    }

    private String getFailureReason(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "WiFi Direct is not supported on this device";
            case WifiP2pManager.BUSY:
                return "System is busy, try again later";
            case WifiP2pManager.ERROR:
                return "Internal error";
            default:
                return "Unknown error: " + reason;
        }
    }



    // ***********************************//
    // ****    FILE TRANSFER         *****//
    // ***********************************//

    private void startFileSync() {
        if (connectedDevice != null && connectionInfo != null) {
            Intent intent = new Intent(MainActivity.this, SyncActivity.class);
            intent.putExtra("connectionInfo", connectionInfo);
            intent.putExtra("SERVER_ADDRESS", hostAddress);
            startActivity(intent);
        } else {
            Toast.makeText(this, "No active connection", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fileSyncManager != null) {
            fileSyncManager.stopServer();
        }
    }


}