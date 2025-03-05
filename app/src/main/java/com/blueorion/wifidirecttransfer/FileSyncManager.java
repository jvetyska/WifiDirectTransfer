package com.blueorion.wifidirecttransfer;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileSyncManager {
    private static final String TAG = "FileSyncManager";
    private static final int PORT = 8988;
    private static final int BUFFER_SIZE = 8192;

    private final Context context;
    private ServerSocket serverSocket;
    private ExecutorService serverExecutor;
    private ExecutorService clientExecutor;
    private final AtomicBoolean isServerRunning = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface FileSyncListener {
        void onTransferProgress(int progress, int total);
        void onFileTransferred(String fileName);
        void onTransferComplete(int fileCount);
        void onTransferError(String errorMessage);

        void onFileSyncStarted();

        void onFileSyncProgress(int progress);

        void onFileSyncCompleted();
    }

    private FileSyncListener listener;

    public FileSyncManager(Context context) {
        this.context = context;
    }

    public void setListener(FileSyncListener listener) {
        this.listener = listener;
    }

    /**
     * Starts the server to receive files (called by Host device)
     * @param hostAddress The host device's IP address
     */
    public void startServer(String hostAddress) {
        if (isServerRunning.get()) {
            Log.w(TAG, "Server is already running");
            return;
        }

        serverExecutor = Executors.newSingleThreadExecutor();
        serverExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(hostAddress, PORT));
                isServerRunning.set(true);

                Log.d(TAG, "Server started on " + hostAddress + ":" + PORT);

                while (isServerRunning.get()) {
                    Socket clientSocket = serverSocket.accept();
                    Log.d(TAG, "Client connected: " + clientSocket.getInetAddress());
                    handleClientConnection(clientSocket);
                }
            } catch (IOException e) {
                if (isServerRunning.get()) {
                    Log.e(TAG, "Server error", e);
                    notifyError("Server error: " + e.getMessage());
                }
            } finally {
                stopServer();
            }
        });
    }

    /**
     * Handles the client connection and receives files
     */
    private void handleClientConnection(Socket clientSocket) {
        try {
            DataInputStream dis = new DataInputStream(new BufferedInputStream(clientSocket.getInputStream()));

            // First, receive the total number of files
            int totalFiles = dis.readInt();
            int filesReceived = 0;

            Log.d(TAG, "Will receive " + totalFiles + " files");
            notifyProgress(0, totalFiles);

            // Create directory if needed
            File destinationDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera");
            if (!destinationDir.exists()) {
                destinationDir.mkdirs();
            }

            // Receive each file
            for (int i = 0; i < totalFiles; i++) {
                // Receive filename
                String fileName = dis.readUTF();
                // Receive file size
                long fileSize = dis.readLong();

                Log.d(TAG, "Receiving file: " + fileName + " (" + fileSize + " bytes)");

                // Create the output file
                File outputFile = new File(destinationDir, fileName);

                // Receive file data
                try (FileOutputStream fos = new FileOutputStream(outputFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bytesRead;
                    long totalBytesRead = 0;

                    while (totalBytesRead < fileSize &&
                            (bytesRead = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {
                        bos.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                    }

                    bos.flush();

                    // Verify file size
                    if (totalBytesRead != fileSize) {
                        Log.e(TAG, "File size mismatch for " + fileName + ": expected " + fileSize + ", got " + totalBytesRead);
                        outputFile.delete(); // Delete corrupted file
                        throw new IOException("File size mismatch");
                    }

                    filesReceived++;
                    notifyFileTransferred(fileName);
                    notifyProgress(filesReceived, totalFiles);
                }
            }

            Log.d(TAG, "All files received successfully: " + filesReceived);
            notifyTransferComplete(filesReceived);

        } catch (IOException e) {
            Log.e(TAG, "Error receiving files", e);
            notifyError("Error receiving files: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing client socket", e);
            }
        }
    }

    /**
     * Stops the server
     */
    public void stopServer() {
        isServerRunning.set(false);

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }

        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }

        Log.d(TAG, "Server stopped");
    }

    /**
     * Starts the client to send files to the server (called by Client device)
     * @param serverAddress Server's IP address
     */
    public void startClientSync(String serverAddress) {
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }

        clientExecutor = Executors.newSingleThreadExecutor();
        clientExecutor.execute(() -> {
            try {
                // Connect to the server
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(serverAddress, PORT), 10000); // 10 seconds timeout

                Log.d(TAG, "Connected to server: " + serverAddress + ":" + PORT);

                DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

                // Get files to transfer from DCIM/Camera
                File sourceDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Camera");

                List<File> filesToSend = new ArrayList<>();
                if (sourceDir.exists() && sourceDir.isDirectory()) {
                    File[] files = sourceDir.listFiles(file -> file.isFile() && !file.isHidden());
                    if (files != null) {
                        for (File file : files) {
                            filesToSend.add(file);
                        }
                    }
                }

                int totalFiles = filesToSend.size();
                int filesSent = 0;

                // Send total number of files
                dos.writeInt(totalFiles);
                dos.flush();

                Log.d(TAG, "Sending " + totalFiles + " files");
                notifyProgress(0, totalFiles);

                // Send each file
                for (File file : filesToSend) {
                    // Send filename
                    dos.writeUTF(file.getName());
                    // Send file size
                    dos.writeLong(file.length());

                    Log.d(TAG, "Sending file: " + file.getName() + " (" + file.length() + " bytes)");

                    // Send file data
                    try (FileInputStream fis = new FileInputStream(file);
                         BufferedInputStream bis = new BufferedInputStream(fis)) {

                        byte[] buffer = new byte[BUFFER_SIZE];
                        int bytesRead;

                        while ((bytesRead = bis.read(buffer)) != -1) {
                            dos.write(buffer, 0, bytesRead);
                        }

                        dos.flush();
                    }

                    filesSent++;
                    notifyFileTransferred(file.getName());
                    notifyProgress(filesSent, totalFiles);
                }

                Log.d(TAG, "All files sent successfully: " + filesSent);
                notifyTransferComplete(filesSent);

            } catch (IOException e) {
                Log.e(TAG, "Error sending files", e);
                notifyError("Error sending files: " + e.getMessage());
            }
        });
    }

    // Notification methods to update UI
    private void notifyProgress(int progress, int total) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTransferProgress(progress, total));
        }
    }

    private void notifyFileTransferred(String fileName) {
        if (listener != null) {
            mainHandler.post(() -> listener.onFileTransferred(fileName));
        }
    }

    private void notifyTransferComplete(int fileCount) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTransferComplete(fileCount));
        }
    }

    private void notifyError(String errorMessage) {
        if (listener != null) {
            mainHandler.post(() -> listener.onTransferError(errorMessage));
        }
    }

    public interface OnSyncProgressListener {
    }
}