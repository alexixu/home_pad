package com.alex.minimalhome;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared presentation only. This class has no device or network access. */
final class HomeStyle {
    static final int PAPER = 0xFFF7F0E5, SURFACE = 0xFFFFFBF3, INK = 0xFF51402F;
    static final int MUTED = 0xFF88735C, LINE = 0xFFE8DDCD, ACCENT = 0xFF806343;

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable surface(Context context, int fill, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        shape.setStroke(dp(context, 1), LINE);
        return shape;
    }

    static void dialog(AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        Context context = dialog.getContext();
        dialog.getWindow().setBackgroundDrawable(surface(context, SURFACE, 24));
        int width = Math.min(dp(context, 580), context.getResources().getDisplayMetrics().widthPixels - dp(context, 40));
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            button.setAllCaps(false);
            button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                    new int[]{MUTED, ACCENT}));
            button.setMinHeight(dp(context, 48));
        }
    }

    static LinearLayout settingsRow(Context context, String title, String detail, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14));
        row.setMinimumHeight(dp(context, 76));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x16806343), surface(context, PAPER, 14), null));
        TextView name = new TextView(context);
        name.setText(title + "  ›"); name.setTextColor(INK); name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(name);
        TextView note = new TextView(context);
        note.setText(detail); note.setTextColor(MUTED); note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, dp(context, 5), 0, 0); row.addView(note);
        row.setFocusable(true); row.setContentDescription(title + "，" + detail);
        row.setOnClickListener(ignored -> action.run());
        row.setAccessibilityDelegate(new android.view.View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(android.view.View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
            }
        });
        name.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        note.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }
}
