package com.blueorion.wifidirecttransfer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.List;

public class FileSyncAdapter extends ArrayAdapter<File> {
    private final List<File> files;
    private final List<Integer> progressList;

    public FileSyncAdapter(Context context, List<File> files, List<Integer> progressList) {
        super(context, 0, files);
        this.files = files;
        this.progressList = progressList;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        ViewHolder viewHolder;

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.file_sync_item, parent, false);

            viewHolder = new ViewHolder();
            viewHolder.fileName = convertView.findViewById(R.id.tvFileName);
            viewHolder.fileSize = convertView.findViewById(R.id.tvFileSize);
            viewHolder.progressBar = convertView.findViewById(R.id.fileProgressBar);
            viewHolder.status = convertView.findViewById(R.id.tvStatus);

            convertView.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) convertView.getTag();
        }

        File file = files.get(position);
        int progress = progressList.get(position);

        viewHolder.fileName.setText(file.getName());
        viewHolder.fileSize.setText(formatFileSize(file.length()));
        viewHolder.progressBar.setProgress(progress);

        if (progress == 0) {
            viewHolder.status.setText("Pending");
        } else if (progress == 100) {
            viewHolder.status.setText("Completed");
        } else if (progress == -1) {
            viewHolder.status.setText("Skipped (Already exists)");
            viewHolder.progressBar.setVisibility(View.GONE);
        } else {
            viewHolder.status.setText("Transferring...");
        }

        return convertView;
    }

    public void updateProgress(int position, int progress) {
        if (position >= 0 && position < progressList.size()) {
            progressList.set(position, progress);
            notifyDataSetChanged();
        }
    }

    private String formatFileSize(long size) {
        if (size <= 0) return "0 B";

        final String[] units = new String[] { "B", "KB", "MB", "GB", "TB" };
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));

        return String.format("%.1f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static class ViewHolder {
        TextView fileName;
        TextView fileSize;
        ProgressBar progressBar;
        TextView status;
    }
}