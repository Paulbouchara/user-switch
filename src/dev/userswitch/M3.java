package dev.userswitch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material 3 building blocks drawn with framework views only (the app is built
 * without Gradle, so no Material Components library): shapes, state layers,
 * the type scale and a few components. Colors are the M3 roles in res/values*.
 */
final class M3 {
    /** The M3 type scale roles the app uses: size in sp, medium weight or not. */
    enum Type {
        HEADLINE_L(32, false), TITLE_L(22, false), TITLE_M(16, true), TITLE_S(14, true),
        BODY_L(16, false), BODY_M(14, false), LABEL_L(14, true), LABEL_M(12, true);

        final int sp;
        final boolean medium;

        Type(int sp, boolean medium) {
            this.sp = sp;
            this.medium = medium;
        }
    }

    private final Context c;
    private final float density;

    M3(Context c) {
        this.c = c;
        density = c.getResources().getDisplayMetrics().density;
    }

    int dp(float v) {
        return Math.round(v * density);
    }

    int color(int res) {
        return c.getColor(res);
    }

    // ---- shapes and state layers --------------------------------------------

    /** A filled shape; one radius for all corners, or four (top-left, top-right, bottom-right, bottom-left). */
    GradientDrawable shape(int color, float... radiiDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        if (radiiDp.length == 1) {
            g.setCornerRadius(dp(radiiDp[0]));
        } else {
            float[] r = new float[8];
            for (int i = 0; i < 4; i++) r[2 * i] = r[2 * i + 1] = dp(radiiDp[i]);
            g.setCornerRadii(r);
        }
        return g;
    }

    /** M3 pressed state layer: the content color at 10% over the shape. */
    Drawable ripple(Drawable content, int onColor) {
        Drawable mask = content != null ? null : shape(Color.WHITE, 999);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(onColor, 0.10f)), content, mask);
    }

    static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0xFFFFFF);
    }

    // ---- text and icons -----------------------------------------------------

    TextView text(CharSequence s, Type type, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, type.sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(type.medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        if (type == Type.HEADLINE_L) t.setLetterSpacing(-0.01f);
        return t;
    }

    ImageView icon(int res, int tint, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(tint));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** A round icon container (avatar-like), as in Pixel settings rows. */
    ImageView badge(int res, int bg, int fg, int sizeDp) {
        ImageView v = icon(res, fg, sizeDp);
        int pad = Math.round(sizeDp * 0.22f);
        v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        v.setBackground(shape(bg, sizeDp / 2f));
        return v;
    }

    // ---- containers ---------------------------------------------------------

    LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A filled M3 card. */
    LinearLayout card(int bg, float... radiiDp) {
        LinearLayout l = column();
        l.setBackground(shape(bg, radiiDp));
        l.setPadding(dp(20), dp(20), dp(20), dp(20));
        return l;
    }

    View divider() {
        View v = new View(c);
        v.setBackgroundColor(color(R.color.outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = lp.bottomMargin = dp(16);
        v.setLayoutParams(lp);
        return v;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    LinearLayout.LayoutParams top(LinearLayout.LayoutParams lp, int dp) {
        lp.topMargin = dp(dp);
        return lp;
    }

    // ---- buttons ------------------------------------------------------------

    /** Filled, tonal or text button (M3: 40 dp high, full pill, label large). */
    TextView button(CharSequence label, int bg, int fg, int iconRes) {
        TextView b = text(label, Type.LABEL_L, fg);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        boolean text = bg == Color.TRANSPARENT;
        b.setPadding(dp(text ? 12 : iconRes != 0 ? 16 : 24), 0, dp(text ? 12 : 24), 0);
        b.setBackground(ripple(shape(bg, 20), fg));
        if (iconRes != 0) {
            Drawable d = c.getDrawable(iconRes).mutate();
            d.setTint(fg);
            d.setBounds(0, 0, dp(18), dp(18));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(dp(8));
        }
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    TextView tonalButton(CharSequence label, int iconRes) {
        return button(label, color(R.color.secondary_container), color(R.color.on_secondary_container), iconRes);
    }

    TextView filledButton(CharSequence label, int iconRes) {
        return button(label, color(R.color.primary), color(R.color.on_primary), iconRes);
    }

    TextView textButton(CharSequence label, int iconRes) {
        return button(label, Color.TRANSPARENT, color(R.color.primary), iconRes);
    }

    /** A 48 dp round icon button (standard M3 icon button). */
    ImageView iconButton(int res, int tint, CharSequence description) {
        ImageView v = icon(res, tint, 48);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(ripple(null, tint));
        v.setContentDescription(description);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    /** A key cap: one token of a sequence (assist-chip-like, 8 dp corners). */
    TextView keyChip(CharSequence label) {
        TextView t = text(label, Type.LABEL_L, color(R.color.on_secondary_container));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(32));
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setBackground(shape(color(R.color.secondary_container), 8));
        return t;
    }

    /** Lays its children out in rows, wrapping like words (for key chips). */
    static final class Flow extends ViewGroup {
        private final int gap;

        Flow(Context c, int gapPx) {
            super(c);
            gap = gapPx;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int max = MeasureSpec.getSize(wSpec);
            boolean bounded = MeasureSpec.getMode(wSpec) != MeasureSpec.UNSPECIFIED;
            int x = 0, y = 0, lineH = 0, width = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(max, bounded ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = ch.getMeasuredWidth();
                if (bounded && x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                x += w + gap;
                width = Math.max(width, x - gap);
                lineH = Math.max(lineH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(bounded ? max : width, resolveSize(y + lineH, hSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int w = ch.getMeasuredWidth(), h = ch.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + gap;
                    lineH = 0;
                }
                int left = rtl ? max - x - w : x;
                ch.layout(left, y, left + w, y + h);
                x += w + gap;
                lineH = Math.max(lineH, h);
            }
        }
    }
}
