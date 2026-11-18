package com.comic.reader;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import java.util.List;

public class SettingsDialogManager {

    public interface OnSettingsActionListener {
        void onOpenLanTransfer();
        void onOpenMissingComics();
        void onChangeTransferDir(java.util.function.Consumer<String> callback);
        void onResetTransferDir(java.util.function.Consumer<String> callback);
        void onShowPasswordSetup(boolean isEnableFlow, java.util.function.Consumer<Boolean> callback);
        void onShowWhitelistManagement(Runnable onUpdated);
        void onShowBlacklistManagement(Runnable onUpdated);
        void onOpenSortDialog();
        void onClearShelf();
    }

    public static void showSettingsDialog(
            Activity activity,
            List<ComicItem> allComics,
            LanTransferManager lanTransferManager,
            int currentSortMode,
            OnSettingsActionListener listener) {
        if (activity == null || activity.isFinishing()) return;

        try {
            View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_settings, null);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();

            Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            }

            // 1. 关闭按钮
            View btnClose = dialogView.findViewById(R.id.btn_close_settings);
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> dialog.dismiss());
            }

            // 2. 局域网互传入口卡片及按钮
            View cardLanEntry = dialogView.findViewById(R.id.card_settings_lan_entry);
            View btnGotoLan = dialogView.findViewById(R.id.btn_settings_goto_lan);
            View.OnClickListener openLanAction = v -> {
                dialog.dismiss();
                if (listener != null) listener.onOpenLanTransfer();
            };
            if (cardLanEntry != null) cardLanEntry.setOnClickListener(openLanAction);
            if (btnGotoLan != null) btnGotoLan.setOnClickListener(openLanAction);

            // 3. 界面主题外观切换 (跟随系统 system / 暗色 dark / 浅色 light)
            SharedPreferences spSettings = activity.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
            String currentTheme = spSettings.getString("app_theme", "system");

            MaterialButton btnThemeSystem = dialogView.findViewById(R.id.btn_settings_theme_system);
            MaterialButton btnThemeDark = dialogView.findViewById(R.id.btn_settings_theme_dark);
            MaterialButton btnThemeLight = dialogView.findViewById(R.id.btn_settings_theme_light);

            int colorPrimary = ContextCompat.getColor(activity, R.color.primary);
            int colorSurface = ContextCompat.getColor(activity, R.color.bg_surface);
            int colorTextWhite = ContextCompat.getColor(activity, R.color.text_white);
            int colorTextMuted = ContextCompat.getColor(activity, R.color.text_muted);

            java.util.function.Consumer<String> updateThemeButtonsUi = (theme) -> {
                boolean isSys = "system".equals(theme);
                boolean isDark = "dark".equals(theme);
                boolean isLight = "light".equals(theme);

                if (btnThemeSystem != null) {
                    btnThemeSystem.setBackgroundTintList(ColorStateList.valueOf(isSys ? colorPrimary : colorSurface));
                    btnThemeSystem.setTextColor(isSys ? Color.WHITE : colorTextWhite);
                }
                if (btnThemeDark != null) {
                    btnThemeDark.setBackgroundTintList(ColorStateList.valueOf(isDark ? colorPrimary : colorSurface));
                    btnThemeDark.setTextColor(isDark ? Color.WHITE : colorTextWhite);
                }
                if (btnThemeLight != null) {
                    btnThemeLight.setBackgroundTintList(ColorStateList.valueOf(isLight ? colorPrimary : colorSurface));
                    btnThemeLight.setTextColor(isLight ? Color.WHITE : colorTextWhite);
                }
            };
            updateThemeButtonsUi.accept(currentTheme);

            if (btnThemeSystem != null) {
                btnThemeSystem.setOnClickListener(v -> {
                    AppThemeAndLocaleHelper.setAppTheme(activity, "system");
                    dialog.dismiss();
                    activity.recreate();
                });
            }
            if (btnThemeDark != null) {
                btnThemeDark.setOnClickListener(v -> {
                    AppThemeAndLocaleHelper.setAppTheme(activity, "dark");
                    dialog.dismiss();
                    activity.recreate();
                });
            }
            if (btnThemeLight != null) {
                btnThemeLight.setOnClickListener(v -> {
                    AppThemeAndLocaleHelper.setAppTheme(activity, "light");
                    dialog.dismiss();
                    activity.recreate();
                });
            }

            // 4. 多语言切换 (zh / en / ja)
            String currentLang = spSettings.getString("app_language", "zh");
            TextView tvCurrentLang = dialogView.findViewById(R.id.tv_settings_current_lang);
            MaterialButton btnLangZh = dialogView.findViewById(R.id.btn_settings_lang_zh);
            MaterialButton btnLangEn = dialogView.findViewById(R.id.btn_settings_lang_en);
            MaterialButton btnLangJa = dialogView.findViewById(R.id.btn_settings_lang_ja);

            java.util.function.Consumer<String> updateLangButtonsUi = (lang) -> {
                boolean isZh = "zh".equals(lang);
                boolean isEn = "en".equals(lang);
                boolean isJa = "ja".equals(lang);

                if (tvCurrentLang != null) {
                    String langName = isZh ? activity.getString(R.string.lang_zh) : (isEn ? activity.getString(R.string.lang_en) : activity.getString(R.string.lang_ja));
                    tvCurrentLang.setText(activity.getString(R.string.current_lang_format, langName));
                }
                if (btnLangZh != null) {
                    btnLangZh.setBackgroundTintList(ColorStateList.valueOf(isZh ? colorPrimary : colorSurface));
                    btnLangZh.setTextColor(isZh ? Color.WHITE : colorTextWhite);
                }
                if (btnLangEn != null) {
                    btnLangEn.setBackgroundTintList(ColorStateList.valueOf(isEn ? colorPrimary : colorSurface));
                    btnLangEn.setTextColor(isEn ? Color.WHITE : colorTextWhite);
                }
                if (btnLangJa != null) {
                    btnLangJa.setBackgroundTintList(ColorStateList.valueOf(isJa ? colorPrimary : colorSurface));
                    btnLangJa.setTextColor(isJa ? Color.WHITE : colorTextWhite);
                }
            };
            updateLangButtonsUi.accept(currentLang);

            View.OnClickListener langClickListener = v -> {
                String newLang = "zh";
                if (v.getId() == R.id.btn_settings_lang_en) newLang = "en";
                else if (v.getId() == R.id.btn_settings_lang_ja) newLang = "ja";

                AppThemeAndLocaleHelper.setAppLanguage(activity, newLang);
                dialog.dismiss();
                activity.recreate();
            };
            if (btnLangZh != null) btnLangZh.setOnClickListener(langClickListener);
            if (btnLangEn != null) btnLangEn.setOnClickListener(langClickListener);
            if (btnLangJa != null) btnLangJa.setOnClickListener(langClickListener);

            // 5. 丢失漫画卡片
            TextView tvBadge = dialogView.findViewById(R.id.tv_settings_missing_count_badge);
            int missingCount = 0;
            if (allComics != null) {
                for (ComicItem item : allComics) {
                    if (item != null && item.isMissing()) missingCount++;
                }
            }
            if (tvBadge != null) {
                tvBadge.setText(missingCount + " 部");
            }
            View cardMissing = dialogView.findViewById(R.id.card_missing_books_setting);
            View btnViewMissing = dialogView.findViewById(R.id.btn_view_missing_comics);
            View.OnClickListener openMissingListener = v -> {
                dialog.dismiss();
                if (listener != null) listener.onOpenMissingComics();
            };
            if (cardMissing != null) cardMissing.setOnClickListener(openMissingListener);
            if (btnViewMissing != null) btnViewMissing.setOnClickListener(openMissingListener);

            // 6. 局域网接收目录
            TextView tvTransferDir = dialogView.findViewById(R.id.tv_settings_transfer_dir);
            if (tvTransferDir != null) {
                tvTransferDir.setText(ShelfStorage.getTransferSaveDir(activity));
            }
            View btnChangeTransferDir = dialogView.findViewById(R.id.btn_settings_change_transfer_dir);
            if (btnChangeTransferDir != null) {
                btnChangeTransferDir.setOnClickListener(v -> {
                    if (listener != null) {
                        listener.onChangeTransferDir(newDir -> {
                            if (tvTransferDir != null) tvTransferDir.setText(newDir);
                        });
                    }
                });
            }
            View btnResetTransferDir = dialogView.findViewById(R.id.btn_settings_reset_transfer_dir);
            if (btnResetTransferDir != null) {
                btnResetTransferDir.setOnClickListener(v -> {
                    ShelfStorage.setTransferSaveDir(activity, "");
                    String defDir = ShelfStorage.getTransferSaveDir(activity);
                    if (tvTransferDir != null) tvTransferDir.setText(defDir);
                    Toast.makeText(activity, "已恢复默认接收目录: " + defDir, Toast.LENGTH_SHORT).show();
                });
            }

            // 7. 互传安全与连接防护（密码保护与白名单）
            SwitchCompat switchSettingsRequirePwd = dialogView.findViewById(R.id.switch_settings_lan_require_pwd);
            TextView tvSettingsPwdStatus = dialogView.findViewById(R.id.tv_settings_lan_pwd_status);
            View btnSettingsEditPwd = dialogView.findViewById(R.id.btn_settings_lan_edit_pwd);
            MaterialButton btnSettingsWhitelist = dialogView.findViewById(R.id.btn_settings_lan_whitelist);
            MaterialButton btnSettingsBlacklist = dialogView.findViewById(R.id.btn_settings_lan_blacklist);

            if (lanTransferManager != null) {
                Runnable updateSecurityButtons = () -> {
                    if (btnSettingsWhitelist != null) {
                        int count = lanTransferManager.getWhitelist().size();
                        btnSettingsWhitelist.setText("⭐ 信任白名单 (" + count + ")");
                    }
                    if (btnSettingsBlacklist != null) {
                        int bCount = lanTransferManager.getBlacklist().size();
                        btnSettingsBlacklist.setText("🚫 黑名单 (" + bCount + ")");
                    }
                };
                updateSecurityButtons.run();

                Runnable updatePasswordUi = () -> {
                    boolean req = lanTransferManager.isRequirePassword();
                    if (switchSettingsRequirePwd != null && switchSettingsRequirePwd.isChecked() != req) {
                        switchSettingsRequirePwd.setChecked(req);
                    }
                    if (btnSettingsEditPwd != null) {
                        btnSettingsEditPwd.setVisibility(req ? View.VISIBLE : View.GONE);
                    }
                    if (tvSettingsPwdStatus != null) {
                        if (req) {
                            tvSettingsPwdStatus.setText("已启用（对方需输入密码直接连接，白名单设备免密免确认）");
                            tvSettingsPwdStatus.setTextColor(Color.parseColor("#4ADE80"));
                        } else {
                            tvSettingsPwdStatus.setText("未启用（对方连接需本机手动点击确认）");
                            tvSettingsPwdStatus.setTextColor(colorTextMuted);
                        }
                    }
                };
                updatePasswordUi.run();

                if (switchSettingsRequirePwd != null) {
                    switchSettingsRequirePwd.setOnCheckedChangeListener((btnView, isChecked) -> {
                        if (!btnView.isPressed()) return;
                        if (isChecked) {
                            if (!lanTransferManager.hasConnectionPassword()) {
                                switchSettingsRequirePwd.setChecked(false);
                                if (listener != null) {
                                    listener.onShowPasswordSetup(true, success -> {
                                        if (success) {
                                            switchSettingsRequirePwd.setChecked(true);
                                            lanTransferManager.setRequirePassword(true);
                                            updatePasswordUi.run();
                                            Toast.makeText(activity, "已开启密码连接保护", Toast.LENGTH_SHORT).show();
                                        } else {
                                            updatePasswordUi.run();
                                        }
                                    });
                                }
                            } else {
                                lanTransferManager.setRequirePassword(true);
                                updatePasswordUi.run();
                                Toast.makeText(activity, "已开启密码连接限制", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            lanTransferManager.setRequirePassword(false);
                            updatePasswordUi.run();
                            Toast.makeText(activity, "已关闭密码连接限制", Toast.LENGTH_SHORT).show();
                        }
                    });
                }

                if (btnSettingsEditPwd != null) {
                    btnSettingsEditPwd.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onShowPasswordSetup(false, success -> updatePasswordUi.run());
                        }
                    });
                }

                if (btnSettingsWhitelist != null) {
                    btnSettingsWhitelist.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onShowWhitelistManagement(updateSecurityButtons);
                        }
                    });
                }

                if (btnSettingsBlacklist != null) {
                    btnSettingsBlacklist.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onShowBlacklistManagement(updateSecurityButtons);
                        }
                    });
                }
            }

            // 8. 排序模式卡片
            TextView tvSortMode = dialogView.findViewById(R.id.tv_settings_sort_mode);
            String[] sortOptions = new String[] {
                    activity.getString(R.string.sort_name),
                    activity.getString(R.string.sort_time),
                    activity.getString(R.string.sort_reads),
                    activity.getString(R.string.sort_recent),
                    activity.getString(R.string.sort_pages)
            };
            if (tvSortMode != null && currentSortMode >= 0 && currentSortMode < sortOptions.length) {
                tvSortMode.setText("当前: " + sortOptions[currentSortMode]);
            }
            View btnChangeSort = dialogView.findViewById(R.id.btn_settings_change_sort);
            if (btnChangeSort != null) {
                btnChangeSort.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (listener != null) listener.onOpenSortDialog();
                });
            }

            // 9. 清空书架卡片
            View btnClearShelf = dialogView.findViewById(R.id.btn_settings_clear_shelf);
            if (btnClearShelf != null) {
                btnClearShelf.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (listener != null) listener.onClearShelf();
                });
            }

            // 10. 软件版本与信息
            TextView tvVersionBadge = dialogView.findViewById(R.id.tv_settings_app_version_badge);
            if (tvVersionBadge != null) {
                String vName = ApkUpdateManager.getLocalVersionName(activity);
                int vCode = ApkUpdateManager.getLocalVersionCode(activity);
                tvVersionBadge.setText("v" + vName + " (Build " + vCode + ")");
            }

            dialog.show();

            // 保证 Dialog 宽度与弹窗优雅展示
            if (dialog.getWindow() != null) {
                int dialogWidth = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.94);
                dialog.getWindow().setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Throwable t) {
            android.util.Log.e("ComicReader", "showSettingsDialog failed", t);
            Toast.makeText(activity, "打开设置出错: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
