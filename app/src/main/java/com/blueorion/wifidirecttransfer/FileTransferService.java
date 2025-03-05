package com.blueorion.wifidirecttransfer;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class FileTransferService {
    private static final String TAG = "FileTransferService";
    private static final int PORT = 8988;
    private static final int SOCKET_TIMEOUT = 10000;

    private Context context;
    private InetAddress hostAddress;
    private ServerSocket serverSocket;

    public FileTransferService(Context context) {
        this.context = context;
    }

    // Prepare to send files to host
    public void prepareToSend(InetAddress hostAddress) {
        this.hostAddress = hostAddress;
    }

    // Start receiving files (for host devices)
    public void startReceiving(InetAddress groupOwnerAddress) {
        new ServerAsyncTask().execute();
    }

    // Send a file to the host
    public void sendFile(File file) {
        if (hostAddress == null) {
            Toast.makeText(context, "No host address available", Toast.LENGTH_SHORT).show();
            return;
        }

        new ClientAsyncTask(file).execute();
    }

    // Class to represent file metadata
    public static class FileTransferData implements Serializable {
        private static final long serialVersionUID = 1L;

        String fileName;
        long fileSize;

        public FileTransferData(String fileName, long fileSize) {
            this.fileName = fileName;
            this.fileSize = fileSize;
        }
    }

    // AsyncTask for server operations (receiving files)
    private class ServerAsyncTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... params) {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.d(TAG, "Server socket opened, waiting for connections");

                while (true) {
                    Socket client = serverSocket.accept();
                    Log.d(TAG, "Client connected: " + client.getInetAddress());

                    // Get file metadata
                    ObjectInputStream ois = new ObjectInputStream(client.getInputStream());
                    FileTransferData fileData = (FileTransferData) ois.readObject();

                    // Create output file in DCIM/Camera directory
                    File cameraDir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DCIM), "Camera");
                    if (!cameraDir.exists()) {
                        cameraDir.mkdirs();
                    }

                    File outputFile = new File(cameraDir, fileData.fileName);
                    FileOutputStream fos = new FileOutputStream(outputFile);

                    // Receive file contents
                    InputStream inputStream = client.getInputStream();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    long totalRead = 0;

                    while (totalRead < fileData.fileSize &&
                            (bytesRead = inputStream.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }

                    // Clean up
                    fos.close();
                    client.close();

                    Log.d(TAG, "File received: " + outputFile.getAbsolutePath());
                }
            } catch (IOException | ClassNotFoundException e) {
                Log.e(TAG, "Error receiving file", e);
                return "Error receiving file: " + e.getMessage();
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing server socket", e);
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                Toast.makeText(context, result, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // AsyncTask for client operations (sending files)
    private class ClientAsyncTask extends AsyncTask<Void, Void, String> {
        private File file;

        ClientAsyncTask(File file) {
            this.file = file;
        }

        @Override
        protected String doInBackground(Void... params) {
            Socket socket = new Socket();

            try {
                Log.d(TAG, "Opening client socket to " + hostAddress);
                socket.bind(null);
                socket.connect(new InetSocketAddress(hostAddress, PORT), SOCKET_TIMEOUT);

                Log.d(TAG, "Client socket connected. Sending file: " + file.getName());

                // Send file metadata
                FileTransferData fileData = new FileTransferData(file.getName(), file.length());
                ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                oos.writeObject(fileData);

                // Send file contents
                OutputStream outputStream = socket.getOutputStream();
                FileInputStream fis = new FileInputStream(file);

                byte[] buffer = new byte[1024];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                fis.close();
                outputStream.close();

                Log.d(TAG, "File sent successfully");
                return "File sent successfully";

            } catch (IOException e) {
                Log.e(TAG, "Error sending file", e);
                return "Error sending file: " + e.getMessage();
            } finally {
                // Clean up
                if (socket != null && socket.isConnected()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // Ignore errors on close
                    }
                }
            }
        }

        @Override
        protected void onPostExecute(String result) {
            Toast.makeText(context, result, Toast.LENGTH_SHORT).show();
        }
    }

    // Stop the service and clean up
    public void stop() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
    }
}