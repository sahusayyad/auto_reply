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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
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
    private Object callListener;

    private int lastState = TelephonyManager.CALL_STATE_IDLE;
    private boolean isIncoming = false;
    private String savedPhoneNumber = null;

    private String lastSentNumber = null;
    private long lastSentTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Auto SMS Reply Active")
                .setContentText("Monitoring calls in background...")
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
            handleCallState(state, null);
        }
    }

    private void handleCallState(int state, String phoneNumber) {
        if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
            savedPhoneNumber = phoneNumber;
        }

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                isIncoming = true;
                lastState = TelephonyManager.CALL_STATE_RINGING;
                Log.d("ReplyService", "Call Ringing. Incoming = true");
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    isIncoming = false; // Outgoing call
                }
                lastState = TelephonyManager.CALL_STATE_OFFHOOK;
                Log.d("ReplyService", "Call Joined (OFFHOOK). IsIncoming = " + isIncoming);
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                Log.d("ReplyService", "Call Finished (IDLE). LastState = " + lastState);
                final boolean wasConnected = (lastState == TelephonyManager.CALL_STATE_OFFHOOK);
                final boolean wasMissed = (lastState == TelephonyManager.CALL_STATE_RINGING);
                final boolean wasIncomingCall = isIncoming;
                final String capturedNum = savedPhoneNumber;

                // Reset state for next call
                lastState = TelephonyManager.CALL_STATE_IDLE;
                isIncoming = false;
                savedPhoneNumber = null;

                if (wasConnected || wasMissed) {
                    // Schedule post-disconnect SMS send
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        attemptSendReply(wasConnected, wasIncomingCall, wasMissed, capturedNum, 0);
                    }, 800);
                }
                break;
        }
    }

    private void attemptSendReply(boolean wasConnected, boolean wasIncoming, boolean wasMissed, String capturedNum, int retryCount) {
        // If we captured a direct phone number during call state change, use it!
        if (capturedNum != null && !capturedNum.trim().isEmpty()) {
            String messageKey;
            String defaultMsg;
            if (wasMissed) {
                messageKey = "missed_message";
                defaultMsg = "Sorry I missed your call. I will call you back soon.";
            } else if (wasIncoming) {
                messageKey = "incoming_message";
                defaultMsg = "I'm busy right now, I'll call you back.";
            } else {
                messageKey = "outgoing_message";
                defaultMsg = "I'll get back to you shortly.";
            }
            sendSmsToNumber(capturedNum, messageKey, defaultMsg);
            return;
        }

        // Otherwise, fetch the disconnected call record from CallLog
        if (retryCount > 6) {
            Log.e("ReplyService", "Could not fetch current call from CallLog after retries.");
            return;
        }

        try {
            android.database.Cursor cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    null, null, null,
                    CallLog.Calls.DATE + " DESC");

            if (cursor != null && cursor.moveToFirst()) {
                int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                int typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE);

                String number = cursor.getString(numberIdx);
                long callDate = cursor.getLong(dateIdx);
                int callType = cursor.getInt(typeIdx);
                cursor.close();

                long timeDiff = System.currentTimeMillis() - callDate;

                // Ensure the top CallLog record is from the current disconnected call (within last 25 seconds)
                if (timeDiff <= 25000 && number != null && !number.trim().isEmpty()) {
                    Log.d("ReplyService", "Found current call in CallLog: " + number + ", age: " + timeDiff + "ms");

                    String messageKey;
                    String defaultMsg;

                    if (wasMissed || callType == CallLog.Calls.MISSED_TYPE || callType == CallLog.Calls.REJECTED_TYPE) {
                        messageKey = "missed_message";
                        defaultMsg = "Sorry I missed your call. I will call you back soon.";
                    } else if (wasIncoming || callType == CallLog.Calls.INCOMING_TYPE) {
                        messageKey = "incoming_message";
                        defaultMsg = "I'm busy right now, I'll call you back.";
                    } else {
                        messageKey = "outgoing_message";
                        defaultMsg = "I'll get back to you shortly.";
                    }

                    sendSmsToNumber(number, messageKey, defaultMsg);
                    return;
                }
            }
        } catch (SecurityException e) {
            Log.e("ReplyService", "Permission missing for CallLog: " + e.getMessage());
            return;
        }

        // CallLog not updated by Android OS yet, retry in 500ms
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            attemptSendReply(wasConnected, wasIncoming, wasMissed, capturedNum, retryCount + 1);
        }, 500);
    }

    private synchronized void sendSmsToNumber(String phoneNumber, String messageKey, String defaultMsg) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return;

        long now = System.currentTimeMillis();
        if (phoneNumber.equals(lastSentNumber) && (now - lastSentTime) < 10000) {
            Log.d("ReplyService", "Skipping duplicate SMS to " + phoneNumber);
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AutoReplyPrefs", MODE_PRIVATE);
        String message = prefs.getString(messageKey, defaultMsg);

        if (message == null || message.trim().isEmpty()) {
            Log.d("ReplyService", "Message for key " + messageKey + " is empty. Skipping SMS.");
            return;
        }

        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            java.util.ArrayList<String> parts = smsManager.divideMessage(message);
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);

            lastSentNumber = phoneNumber;
            lastSentTime = now;

            Log.d("ReplyService", "Auto Reply (" + messageKey + ") sent to: " + phoneNumber + " | Parts: " + parts.size());
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
