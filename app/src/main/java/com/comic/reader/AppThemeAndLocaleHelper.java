package com.comic.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import androidx.appcompat.app.AppCompatDelegate;
import java.util.Locale;

public class AppThemeAndLocaleHelper {

    public static Context attachBaseContext(Context context) {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String lang = sp.getString("app_language", "zh");
        Locale locale;
        if ("en".equalsIgnoreCase(lang)) {
            locale = Locale.ENGLISH;
        } else if ("ja".equalsIgnoreCase(lang)) {
            locale = Locale.JAPANESE;
        } else {
            locale = Locale.SIMPLIFIED_CHINESE;
        }
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    public static void applyAppTheme(Context context) {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        String theme = sp.getString("app_theme", "system");
        if ("dark".equalsIgnoreCase(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else if ("light".equalsIgnoreCase(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else {
            // 默认跟随系统
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    public static void setAppTheme(Context context, String theme) {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        sp.edit().putString("app_theme", theme).apply();
        applyAppTheme(context);
    }

    public static void setAppLanguage(Context context, String lang) {
        SharedPreferences sp = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        sp.edit().putString("app_language", lang).apply();
    }
}
