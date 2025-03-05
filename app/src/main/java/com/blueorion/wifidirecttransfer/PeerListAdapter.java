package com.blueorion.wifidirecttransfer;

import android.content.Context;
import android.graphics.Color;
import android.net.wifi.p2p.WifiP2pDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;

public class PeerListAdapter extends ArrayAdapter<WifiP2pDevice> {
    private Context context;
    private List<WifiP2pDevice> devices;
    private WifiP2pDevice connectedDevice;

    // Colors
    private final int COLOR_HOST = Color.parseColor("#4CAF50"); // Green
    private final int COLOR_CLIENT = Color.parseColor("#9E9E9E"); // Gray
    private final int COLOR_TEXT = Color.parseColor("#212121"); // Dark text
    private final int COLOR_TEXT_CONNECTED = Color.parseColor("#FFFFFF"); // White text for connected item

    public PeerListAdapter(Context context, List<WifiP2pDevice> devices) {
        super(context, 0, devices);
        this.context = context;
        this.devices = devices;
        this.connectedDevice = null;
    }

    public void setConnectedDevice(WifiP2pDevice device) {
        this.connectedDevice = device;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_peer, parent, false);
        }

        WifiP2pDevice device = devices.get(position);
        TextView deviceName = convertView.findViewById(R.id.text_device_name);
        TextView deviceStatus = convertView.findViewById(R.id.text_device_status);
        View deviceTypeIndicator = convertView.findViewById(R.id.view_device_type);

        // Set device name
        String displayName = device.deviceName;
        if (displayName.isEmpty()) {
            displayName = device.deviceAddress;
        }

        // Check if this device is connected
        boolean isConnected = connectedDevice != null &&
                device.deviceAddress.equals(connectedDevice.deviceAddress);

        if (isConnected) {
            displayName += " [CONNECTED]";
        }

        deviceName.setText(displayName);

        // Set device status
        String status = getDeviceStatus(device.status);
        deviceStatus.setText(status);

        // Determine if this is a host or client based on name
        boolean isHost = device.deviceName.contains("Backup");

        // Color the item based on host/client status
        if (isConnected) {
            // Connected device has primary color background with white text
            convertView.setBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary));
            deviceName.setTextColor(COLOR_TEXT_CONNECTED);
            deviceStatus.setTextColor(COLOR_TEXT_CONNECTED);
        } else {
            // Non-connected device has white background with dark text
            convertView.setBackgroundColor(Color.WHITE);
            deviceName.setTextColor(COLOR_TEXT);
            deviceStatus.setTextColor(COLOR_TEXT);
        }

        // Set indicator color based on host/client
        deviceTypeIndicator.setBackgroundColor(isHost ? COLOR_HOST : COLOR_CLIENT);

        return convertView;
    }

    private String getDeviceStatus(int deviceStatus) {
        switch (deviceStatus) {
            case WifiP2pDevice.AVAILABLE:
                return "Available";
            case WifiP2pDevice.INVITED:
                return "Invited";
            case WifiP2pDevice.CONNECTED:
                return "Connected";
            case WifiP2pDevice.FAILED:
                return "Failed";
            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";
            default:
                return "Unknown";
        }
    }
}