package com.mass.autoreply;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private TextInputEditText etIncoming, etOutgoing, etMissed;
    private SwitchMaterial serviceSwitch;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, systemBars.top, 0, 0);
            return insets;
        });

        prefs = getSharedPreferences("AutoReplyPrefs", MODE_PRIVATE);
        etIncoming = findViewById(R.id.etIncoming);
        etOutgoing = findViewById(R.id.etOutgoing);
        etMissed = findViewById(R.id.etMissed);
        serviceSwitch = findViewById(R.id.serviceSwitch);
        MaterialButton btnSave = findViewById(R.id.btnSave);

        // Load saved state
        etIncoming.setText(prefs.getString("incoming_message", "I'm busy right now, I'll call you back."));
        etOutgoing.setText(prefs.getString("outgoing_message", "I'll get back to you shortly."));
        etMissed.setText(prefs.getString("missed_message", "Sorry I missed your call. I will call you back soon."));
        serviceSwitch.setChecked(prefs.getBoolean("service_enabled", false));

        btnSave.setOnClickListener(v -> {
            String incoming = etIncoming.getText().toString();
            String outgoing = etOutgoing.getText().toString();
            String missed = etMissed.getText().toString();

            prefs.edit()
                    .putString("incoming_message", incoming)
                    .putString("outgoing_message", outgoing)
                    .putString("missed_message", missed)
                    .apply();

            Toast.makeText(this, "Messages saved!", Toast.LENGTH_SHORT).show();
        });

        serviceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (checkPermissions()) {
                    startReplyService();
                } else {
                    requestPermissions();
                    serviceSwitch.setChecked(false);
                }
            } else {
                stopReplyService();
            }
        });

        // Initial check if service should be running
        if (serviceSwitch.isChecked() && checkPermissions()) {
            startReplyService();
        }
    }

    private void startReplyService() {
        prefs.edit().putBoolean("service_enabled", true).apply();
        Intent serviceIntent = new Intent(this, ReplyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT).show();
    }

    private void stopReplyService() {
        prefs.edit().putBoolean("service_enabled", false).apply();
        Intent serviceIntent = new Intent(this, ReplyService.class);
        stopService(serviceIntent);
        Toast.makeText(this, "Service Stopped", Toast.LENGTH_SHORT).show();
    }

    private boolean checkPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.SEND_SMS);
        permissions.add(Manifest.permission.READ_CALL_LOG);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.READ_PHONE_STATE);
        permissions.add(Manifest.permission.SEND_SMS);
        permissions.add(Manifest.permission.READ_CALL_LOG);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                serviceSwitch.setChecked(true);
                startReplyService();
            } else {
                Toast.makeText(this, "Permissions denied!", Toast.LENGTH_SHORT).show();
                serviceSwitch.setChecked(false);
            }
        }
    }
}
