package com.mass.autoreply;

import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class SubscriptionActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_subscription);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, systemBars.top, 0, 0);
            return insets;
        });

        MaterialButton btnBasicPlan = findViewById(R.id.btnBasicPlan);
        MaterialButton btnYearlyPlan = findViewById(R.id.btnYearlyPlan);
        MaterialButton btnMonthlyPlan = findViewById(R.id.btnMonthlyPlan);

        btnBasicPlan.setOnClickListener(v -> 
            Toast.makeText(this, "You are currently using the Basic Plan.", Toast.LENGTH_SHORT).show()
        );

        btnYearlyPlan.setOnClickListener(v -> 
            Toast.makeText(this, "Yearly Subscription selected!", Toast.LENGTH_SHORT).show()
        );

        btnMonthlyPlan.setOnClickListener(v -> 
            Toast.makeText(this, "Monthly Subscription selected!", Toast.LENGTH_SHORT).show()
        );
    }
}
