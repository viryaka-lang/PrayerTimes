package com.ptimes.rabat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;

/** Applies the app language, and recreates itself when the language or clock format changes. */
public abstract class BaseActivity extends Activity {

    private String appliedSignature;
    private boolean recreating;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppSettings.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appliedSignature = AppSettings.signature(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AppSettings.signature(this).equals(appliedSignature)) {
            recreating = true;
            recreate();
        }
    }

    /** True when onResume found changed settings and this activity is about to be rebuilt. */
    protected boolean isRecreating() {
        return recreating;
    }

    /** Pads {@code root} so content clears the status and navigation bars (edge-to-edge). */
    protected static void padForSystemBars(View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
    }
}
