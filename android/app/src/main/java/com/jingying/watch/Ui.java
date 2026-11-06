package com.jingying.watch;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 界面小工具：颜色、dp、圆角背景、图标、沉浸式。 */
public final class Ui {

    public static final int BG = 0xFF0A0A0C;
    public static final int ELEVATED = 0xFF151519;
    public static final int CARD = 0xFF1B1B21;
    public static final int LINE = 0xFF26262E;
    public static final int TEXT = 0xFFF7F7FA;
    public static final int DIM = 0xFF9C9CA8;
    public static final int FAINT = 0xFF6C6C78;
    public static final int ACCENT = 0xFFFE2C55;
    public static final int CYAN = 0xFF25F4EE;

    public static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface BLACK = Typeface.create("sans-serif-black", Typeface.NORMAL);
    public static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static float dpf(Context context, float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }

    public static int screenWidth(Context context) {
        return context.getResources().getDisplayMetrics().widthPixels;
    }

    public static int screenHeight(Context context) {
        return context.getResources().getDisplayMetrics().heightPixels;
    }

    public static TextView text(Context context, String value, float sizeSp,
                                int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setTypeface(bold ? BOLD : REGULAR);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static TextView label(Context context, String value, float sizeSp, int color) {
        TextView view = text(context, value, sizeSp, color, false);
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    public static ImageView icon(Context context, int res, int sizeDp, int color) {
        ImageView view = new ImageView(context);
        view.setImageResource(res);
        view.setColorFilter(color);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return view;
    }

    public static ImageView bigIcon(Context context, int res, int sizeDp, int color) {
        ImageView view = new ImageView(context);
        view.setImageResource(res);
        view.setColorFilter(color);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setLayoutParams(new FrameLayout.LayoutParams(dp(context, sizeDp), dp(context, sizeDp)));
        return view;
    }

    public static GradientDrawable round(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dpf(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable roundStroke(Context context, int color, int stroke,
                                               float radiusDp, float strokeDp) {
        GradientDrawable drawable = round(context, color, radiusDp);
        drawable.setStroke(Math.max(1, dp(context, strokeDp)), stroke);
        return drawable;
    }

    public static GradientDrawable pill(Context context, int color, float radiusDp) {
        return round(context, color, radiusDp);
    }

    public static GradientDrawable verticalGradient(Context context, int top, int bottom) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(0f);
        return drawable;
    }

    public static Drawable ripple(Context context, Drawable content, float radiusDp) {
        GradientDrawable mask = round(context, Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, mask);
    }

    public static int statusBarHeight(Context context) {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return context.getResources().getDimensionPixelSize(id);
        }
        return dp(context, 24);
    }

    public static void toast(Context context, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    /** 危险操作的二次确认（比如删除云端文件），确认按钮默认用主题红。 */
    public static void confirm(Activity activity, String title, String message,
                               String positive, Runnable onConfirm) {
        confirm(activity, title, message, positive, ACCENT, onConfirm);
    }

    /** 同上，但可以指定确认按钮颜色（恢复这类非破坏性动作用浅色）。 */
    public static void confirm(Activity activity, String title, String message,
                               String positive, int positiveColor, Runnable onConfirm) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton(positive, (target, which) -> onConfirm.run())
                .show();
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(positiveColor);
    }

    /** 删除这类不能中途退出的等待框。 */
    public static android.app.AlertDialog busy(Activity activity, String message) {
        if (activity == null || activity.isFinishing()) {
            return null;
        }
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setMessage(message)
                .setCancelable(false)
                .create();
        dialog.show();
        return dialog;
    }

    public static void dismiss(android.app.Dialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Exception ignored) {
        }
    }

    /** 短视频页：全屏沉浸，状态栏/导航栏都藏起来。 */
    public static void enterImmersive(Activity activity) {
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false);
                View decor = window.getDecorView();
                if (decor != null) {
                    WindowInsetsController controller = decor.getWindowInsetsController();
                    if (controller != null) {
                        controller.hide(WindowInsets.Type.statusBars()
                                | WindowInsets.Type.navigationBars());
                        controller.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
