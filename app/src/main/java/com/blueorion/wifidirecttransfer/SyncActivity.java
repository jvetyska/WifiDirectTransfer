package com.blueorion.wifidirecttransfer;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class SyncActivity extends AppCompatActivity implements FileSyncManager.FileSyncListener {
    private static final String TAG = "SyncActivity";

    // UI Components
    private TextView statusText;
    private TextView filesCountText;
    private TextView progressText;
    private ProgressBar progressBar;
    private Button actionButton;
    private Button cancelButton;

    // Sync Components
    private FileSyncManager fileSyncManager;
    private String serverAddress;
    private List<File> availableFiles = new ArrayList<>();
    private ExecutorService scanExecutor;
    private final AtomicBoolean isSyncing = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);

        // Get server address from intent
        serverAddress = getIntent().getStringExtra("SERVER_ADDRESS");
        if (serverAddress == null || serverAddress.isEmpty()) {
            Log.e(TAG, "No server address provided");
            finish();
            return;
        }

        // Initialize UI components
        statusText = findViewById(R.id.statusText);
        filesCountText = findViewById(R.id.filesCountText);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        actionButton = findViewById(R.id.actionButton);
        cancelButton = findViewById(R.id.cancelButton);

        // Initialize FileSyncManager
        fileSyncManager = new FileSyncManager(this);
        fileSyncManager.setListener(this);

        // Set up button click listeners
        actionButton.setOnClickListener(v -> handleActionButtonClick());
        cancelButton.setOnClickListener(v -> handleCancelButtonClick());

        // Handle back press
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isSyncing.get()) {
                    // Don't allow back press during sync
                    return;
                }
                finish();
            }
        });

        // Initial UI state
        updateUIState(SyncState.SCANNING);

        // Start scanning for files
        scanFiles();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }
    }

    /**
     * Scans for available files in the DCIM/Camera directory
     */
    private void scanFiles() {
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }

        scanExecutor = Executors.newSingleThreadExecutor();
        scanExecutor.execute(() -> {
            try {
                // Get files from DCIM/Camera
                File sourceDir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Camera");

                availableFiles.clear();

                if (sourceDir.exists() && sourceDir.isDirectory()) {
                    File[] files = sourceDir.listFiles(file -> file.isFile() && !file.isHidden());
                    if (files != null) {
                        for (File file : files) {
                            availableFiles.add(file);
                        }
                    }
                }

                // Update UI on main thread
                mainHandler.post(() -> {
                    int fileCount = availableFiles.size();
                    filesCountText.setText(getString(R.string.files_found, fileCount));

                    if (fileCount > 0) {
                        updateUIState(SyncState.READY);
                    } else {
                        updateUIState(SyncState.NO_FILES);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error scanning files", e);
                mainHandler.post(() -> {
                    statusText.setText(R.string.error_scanning);
                    updateUIState(SyncState.ERROR);
                });
            }
        });
    }

    /**
     * Handles clicks on the action button
     */
    private void handleActionButtonClick() {
        SyncState currentState = (SyncState) actionButton.getTag();

        switch (currentState) {
            case READY:
                // Start sync
                updateUIState(SyncState.SYNCING);
                startSync();
                break;

            case COMPLETE:
            case ERROR:
            case NO_FILES:
                // Return to main activity
                finish();
                break;
        }
    }

    /**
     * Handles clicks on the cancel button
     */
    private void handleCancelButtonClick() {
        // Cancel ongoing sync
        if (isSyncing.getAndSet(false)) {
            updateUIState(SyncState.CANCELLING);

            // Give a brief moment to show cancelling state
            mainHandler.postDelayed(() -> {
                updateUIState(SyncState.READY);
                statusText.setText(R.string.sync_cancelled);
            }, 500);
        }
    }

    /**
     * Starts the sync process
     */
    private void startSync() {
        isSyncing.set(true);
        statusText.setText(R.string.preparing_sync);
        progressBar.setProgress(0);
        progressText.setText(getString(R.string.progress_format, 0, availableFiles.size()));

        // Start the sync process
        fileSyncManager.startClientSync(serverAddress);
    }

    /**
     * Updates the UI state based on the current sync state
     */
    private void updateUIState(SyncState state) {
        // Store current state in button tag for reference
        actionButton.setTag(state);

        switch (state) {
            case SCANNING:
                actionButton.setVisibility(View.INVISIBLE);
                cancelButton.setVisibility(View.INVISIBLE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(true);
                statusText.setText(R.string.scanning_files);
                break;

            case READY:
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText(R.string.sync_now);
                actionButton.setEnabled(true);
                cancelButton.setVisibility(View.INVISIBLE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(0);
                statusText.setText(R.string.ready_to_sync);
                break;

            case NO_FILES:
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText(R.string.done);
                actionButton.setEnabled(true);
                cancelButton.setVisibility(View.INVISIBLE);
                progressBar.setVisibility(View.INVISIBLE);
                statusText.setText(R.string.no_files_found);
                break;

            case SYNCING:
                actionButton.setVisibility(View.INVISIBLE);
                cancelButton.setVisibility(View.VISIBLE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(false);
                statusText.setText(R.string.syncing_in_progress);
                break;

            case CANCELLING:
                actionButton.setVisibility(View.INVISIBLE);
                cancelButton.setVisibility(View.INVISIBLE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(true);
                statusText.setText(R.string.cancelling_sync);
                break;

            case COMPLETE:
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText(R.string.done);
                actionButton.setEnabled(true);
                cancelButton.setVisibility(View.INVISIBLE);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(false);
                progressBar.setProgress(progressBar.getMax());
                break;

            case ERROR:
                actionButton.setVisibility(View.VISIBLE);
                actionButton.setText(R.string.done);
                actionButton.setEnabled(true);
                cancelButton.setVisibility(View.INVISIBLE);
                progressBar.setVisibility(View.INVISIBLE);
                break;
        }
    }

    // FileSyncListener implementation
    @Override
    public void onTransferProgress(int progress, int total) {
        if (!isSyncing.get()) {
            return; // Ignore updates if sync was cancelled
        }

        progressBar.setMax(total);
        progressBar.setProgress(progress);
        progressText.setText(getString(R.string.progress_format, progress, total));
        statusText.setText(getString(R.string.syncing_progress, progress, total));
    }

    @Override
    public void onFileTransferred(String fileName) {
        if (!isSyncing.get()) {
            return; // Ignore updates if sync was cancelled
        }

        // You could add a recent files list here if desired
    }

    @Override
    public void onTransferComplete(int fileCount) {
        if (!isSyncing.get()) {
            return; // Ignore updates if sync was cancelled
        }

        isSyncing.set(false);
        statusText.setText(getString(R.string.sync_complete, fileCount));
        updateUIState(SyncState.COMPLETE);
    }

    @Override
    public void onTransferError(String errorMessage) {
        isSyncing.set(false);
        statusText.setText(getString(R.string.sync_error, errorMessage));
        updateUIState(SyncState.ERROR);
    }

    /**
     * Enum to represent different states of the sync process
     */
    private enum SyncState {
        SCANNING,
        READY,
        NO_FILES,
        SYNCING,
        CANCELLING,
        COMPLETE,
        ERROR
    }
}