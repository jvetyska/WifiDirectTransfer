package com.blueorion.wifidirecttransfer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "WiFiDirectBR";

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private MainActivity activity;

    private WifiP2pDevice lastConnectedDevice = null;

    public WiFiDirectBroadcastReceiver(WifiP2pManager manager, WifiP2pManager.Channel channel, MainActivity activity) {
        this.manager = manager;
        this.channel = channel;
        this.activity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received broadcast action: " + action);

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            // Check if WiFi P2P is enabled
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            Log.d(TAG, "P2P state changed - " + (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED ? "enabled" : "disabled"));

        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            // Request available peers from the wifi p2p manager
            Log.d(TAG, "P2P peers changed");
            if (manager != null) {
                manager.requestPeers(channel, peers -> activity.updatePeers(peers));
            }

        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            // Respond to new connection or disconnections
            Log.d(TAG, "P2P connection changed");
            if (manager != null) {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo != null && networkInfo.isConnected()) {
                    Log.d(TAG, "Connected to a peer");

                    // Connected to a peer, request connection info
                    manager.requestConnectionInfo(channel, activity);

                    // Since we don't know which device we're connected to yet,
                    // we'll wait for the THIS_DEVICE_CHANGED_ACTION broadcast or
                    // request group info
                    manager.requestGroupInfo(channel, group -> {
                        if (group != null && group.getClientList().size() > 0) {
                            // If we're the group owner, get the first client
                            WifiP2pDevice device = group.getClientList().iterator().next();
                            lastConnectedDevice = device;
                            activity.updateConnectionStatus(true, device);
                        } else if (group != null) {
                            // If we're a client, get the group owner
                            WifiP2pDevice device = group.getOwner();
                            lastConnectedDevice = device;
                            activity.updateConnectionStatus(true, device);
                        }
                    });
                } else {
                    Log.d(TAG, "Disconnected from peer");
                    activity.updateConnectionStatus(false, null);
                }
            }

        } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
            // Respond to this device's wifi state changing
            WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
            Log.d(TAG, "This device changed: " + device.deviceName + " status: " + device.status);

            // If we have a connected device, update its connection status
            if (lastConnectedDevice != null) {
                activity.updateConnectionStatus(true, lastConnectedDevice);
            }
        }
    }
}