package com.dogood.handsfreeprofile.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.dogood.handsfreeprofile.MainActivity;
import com.dogood.handsfreeprofile.R;

public class NotificationHelper {
    private static final String TAG = "NotificationHelper";
    private static final String CHANNEL_ID = "HfpAgentForegroundChannel";
    private static final String CHANNEL_NAME = "HFP Agent Service";
    private static final String CHANNEL_DESCRIPTION = "Notification channel for HFP Car Kit service.";
    private static final int NOTIFICATION_ID = 123;

    private final Context context;
    private final NotificationManager notificationManager;

    public NotificationHelper(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager != null && notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel serviceChannel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                );
                serviceChannel.setDescription(CHANNEL_DESCRIPTION);
                notificationManager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created: " + CHANNEL_ID);
                // Log channel creation for debugging

            }
        }
    }

    public Notification createNotification(String contentText) {
        if (context == null) {
            Log.e(TAG, "Context is null, cannot update notification.");
            return null;
        }
        if (notificationManager == null) {
            Log.e(TAG, "NotificationManager is null, cannot create notification.");
            return null;
        }
        Intent notificationIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("HFP Car Kit Active")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_stat_bluetooth_hfp) // Ensure this drawable exists
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    public void updateNotification(String contentText) {
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification(contentText));
        }
    }

    public void cancelNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }
}