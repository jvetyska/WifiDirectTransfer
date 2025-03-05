package com.blueorion.wifidirecttransfer;

import android.app.IntentService;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class WiFiDirectFileTransferService extends IntentService {
    private static final String TAG = "FileTransferService";
    private static final int SOCKET_TIMEOUT = 5000;
    public static final String ACTION_SEND_FILE = "com.example.wifidirecttransfer.SEND_FILE";
    public static final String EXTRAS_FILE_PATH = "file_path";
    public static final String EXTRAS_HOST_ADDRESS = "host_address";
    public static final String EXTRAS_PORT = "port";

    public WiFiDirectFileTransferService() {
        super("WiFiDirectFileTransferService");
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (intent == null) {
            Log.e(TAG, "Intent is null");
            return;
        }

        String action = intent.getAction();
        if (ACTION_SEND_FILE.equals(action)) {
            String filePath = intent.getStringExtra(EXTRAS_FILE_PATH);
            String host = intent.getStringExtra(EXTRAS_HOST_ADDRESS);
            int port = intent.getIntExtra(EXTRAS_PORT, 8888);

            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT);

                File file = new File(filePath);
                long fileSize = file.length();
                OutputStream outputStream = socket.getOutputStream();

                // First write the file name
                String fileName = file.getName();
                byte[] fileNameBytes = fileName.getBytes();
                outputStream.write(fileNameBytes.length);
                outputStream.write(fileNameBytes);

                // Then write the file size
                outputStream.write(String.valueOf(fileSize).getBytes());

                // Finally, write the file data
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[1024];
                int bytesRead;
                long totalBytesWritten = 0;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    totalBytesWritten += bytesRead;
                }

                fis.close();
                Log.d(TAG, "File sent: " + fileName + " (" + totalBytesWritten + " bytes)");
            } catch (IOException e) {
                Log.e(TAG, "Error sending file: " + e.getMessage());
            } finally {
                if (socket != null) {
                    if (socket.isConnected()) {
                        try {
                            socket.close();
                        } catch (IOException e) {
                            Log.e(TAG, "Error closing socket: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    // Class for handling file reception
    public static class FileServerAsyncTask {
        private final ServerSocket serverSocket;
        private final String destinationDir;

        public FileServerAsyncTask(String destinationDir) throws IOException {
            this.serverSocket = new ServerSocket(8888);
            this.destinationDir = destinationDir;
        }

        public void start() {
            Thread serverThread = new Thread(() -> {
                try {
                    while (true) {
                        Socket client = serverSocket.accept();

                        InputStream inputStream = client.getInputStream();

                        // Read file name length
                        int fileNameLength = inputStream.read();
                        byte[] fileNameBytes = new byte[fileNameLength];
                        inputStream.read(fileNameBytes);
                        String fileName = new String(fileNameBytes);

                        // Read file size
                        byte[] fileSizeBytes = new byte[20]; // Enough for a long value as string
                        int read = inputStream.read(fileSizeBytes);
                        String fileSizeStr = new String(fileSizeBytes, 0, read);
                        long fileSize = Long.parseLong(fileSizeStr.trim());

                        // Create file
                        File outputDir = new File(destinationDir);
                        if (!outputDir.exists()) {
                            outputDir.mkdirs();
                        }

                        File outputFile = new File(outputDir, fileName);
                        FileOutputStream fos = new FileOutputStream(outputFile);

                        // Copy file data
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        long totalBytesRead = 0;

                        while (totalBytesRead < fileSize &&
                                (bytesRead = inputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalBytesRead += bytesRead;
                        }

                        fos.close();
                        client.close();

                        Log.d(TAG, "File received: " + fileName + " (" + totalBytesRead + " bytes)");
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Server error: " + e.getMessage());
                }
            });

            serverThread.start();
        }

        public void stop() {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket: " + e.getMessage());
            }
        }
    }
}