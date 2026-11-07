package com.comic.reader;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean hasNavigated = false;

    private final Runnable navigateRunnable = this::navigateToMain;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        findViewById(R.id.splash_root).setOnClickListener(v -> navigateToMain());

        // 默认停留 1.5 秒开屏封面展示，随后平滑进入书架
        handler.postDelayed(navigateRunnable, 1500);
    }

    private synchronized void navigateToMain() {
        if (!hasNavigated) {
            hasNavigated = true;
            handler.removeCallbacks(navigateRunnable);
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(navigateRunnable);
    }
}
