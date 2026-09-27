package com.mass.autoreply;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ReplyService extends Service {
    private static final String CHANNEL_ID = "AutoReplyServiceChannel";
    private TelephonyManager telephonyManager;
    private Object callListener; // Can be PhoneStateListener or TelephonyCallback

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Auto SMS Reply Active")
                .setContentText("Listening for calls in background...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }

        telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        setupCallListener();
    }

    private void setupCallListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Executor executor = Executors.newSingleThreadExecutor();
            TelephonyCallback callback = new CallStateCallback();
            telephonyManager.registerTelephonyCallback(executor, callback);
            callListener = callback;
        } else {
            PhoneStateListener listener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    handleCallState(state, phoneNumber);
                }
            };
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            callListener = listener;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            // Note: phoneNumber is not provided here for privacy reasons in newer APIs
            // We'll need to get it from CallLog if state is OFFHOOK/RINGING
            handleCallState(state, null);
        }
    }

    private void handleCallState(int state, String phoneNumber) {
        // Send when call joins (OFFHOOK)
        if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            Log.d("ReplyService", "Call Joined (OFFHOOK)");
            // On newer Android versions, phoneNumber might be null in the listener.
            // In a real app, you'd fetch the last number from CallLog here.
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                sendAutoReply(phoneNumber);
            } else {
                // Fallback: try to get from recent call log
                String lastNumber = getLastNumberFromCallLog();
                if (lastNumber != null) {
                    sendAutoReply(lastNumber);
                }
            }
        }
    }

    private String getLastNumberFromCallLog() {
        try {
            android.database.Cursor cursor = getContentResolver().query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    null, null, null,
                    android.provider.CallLog.Calls.DATE + " DESC");
            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER);
                String number = cursor.getString(numberIdx);
                cursor.close();
                return number;
            }
        } catch (SecurityException e) {
            Log.e("ReplyService", "Permission missing for CallLog: " + e.getMessage());
        }
        return null;
    }

    private void sendAutoReply(String phoneNumber) {
        SharedPreferences prefs = getSharedPreferences("AutoReplyPrefs", MODE_PRIVATE);
        String message = prefs.getString("reply_message", "I'm busy right now, I'll call you back.");
        
        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }
            
            // Always use divideMessage and sendMultipartTextMessage to perfectly handle 
            // emojis and long text regardless of the character count or carrier limits.
            java.util.ArrayList<String> parts = smsManager.divideMessage(message);
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            
            Log.d("ReplyService", "Multipart SMS triggered for: " + phoneNumber + " | Parts: " + parts.size());
        } catch (Exception e) {
            Log.e("ReplyService", "Failed to send SMS: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Auto Reply Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (telephonyManager != null && callListener != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) callListener);
            } else {
                telephonyManager.listen((PhoneStateListener) callListener, PhoneStateListener.LISTEN_NONE);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
