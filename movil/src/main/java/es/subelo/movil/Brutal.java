package es.subelo.movil;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Estilo visual de Súbelo: brutalismo, bordes negros gruesos, sombras duras, morado y naranja. */
final class Brutal {
    static final int PURPLE = 0xFF7B2FF7;
    static final int ORANGE = 0xFFFF6B1A;
    // Colores fijos (iguales en modo claro y oscuro): texto sobre naranja/morado y barras negras.
    static final int BLACK = 0xFF111111;
    static final int PAPER = 0xFFFFF4E6;
    static final int SNOW = 0xFFFFFFFF;

    // Colores que cambian con el modo oscuro (ver apply()).
    /** Bordes, sombras y texto. */
    static int INK = BLACK;
    /** Fondo de pantalla. */
    static int CREAM = PAPER;
    /** Superficie de tarjetas y botones normales. */
    static int WHITE = SNOW;
    static int LILAC = 0xFFE4D6FF;
    static int PEACH = 0xFFFFD9C2;
    static int DISABLED = 0xFFD8D2CA;
    static boolean dark;
    static final int RED = 0xFFE5322D;
    static final int GREEN = 0xFF14A44D;

    private static Typeface display, body, bodyBold, mono;

    private Brutal() {}

    // ---------- modo oscuro ----------

    /** Sin elegir: sigue el ajuste del sistema. */
    static boolean isDark(Context c) {
        SharedPreferences p = c.getSharedPreferences("tema", Context.MODE_PRIVATE);
        if (p.contains("oscuro")) return p.getBoolean("oscuro", false);
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static void setDark(Context c, boolean on) {
        c.getSharedPreferences("tema", Context.MODE_PRIVATE).edit().putBoolean("oscuro", on).apply();
    }

    /** Llamar antes de super.onCreate(): fija la paleta y el tema de la pantalla. */
    static void apply(Activity a) {
        dark = isDark(a);
        INK = dark ? 0xFFF3EADF : BLACK;
        CREAM = dark ? 0xFF16121C : PAPER;
        WHITE = dark ? 0xFF241D2E : SNOW;
        LILAC = dark ? 0xFF2E2148 : 0xFFE4D6FF;
        PEACH = dark ? 0xFF47291A : 0xFFFFD9C2;
        DISABLED = dark ? 0xFF3A3442 : 0xFFD8D2CA;
        a.setTheme(dark ? R.style.SubeloOscuro : R.style.Subelo);
        a.getWindow().setBackgroundDrawable(new ColorDrawable(CREAM));
    }

    /** Botón «☾ Modo oscuro / ☀ Modo claro» que cambia el modo y redibuja la pantalla. */
    static Button darkToggle(final Activity a, String dark, String light) {
        return button(a, Brutal.dark ? light : dark, WHITE, INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setDark(a, !Brutal.dark);
                a.recreate();
            }
        });
    }

    // ---------- tipografías ----------

    static Typeface display(Context c) {
        if (display == null) display = font(c, "fonts/ArchivoBlack.ttf", Typeface.DEFAULT_BOLD);
        return display;
    }

    static Typeface body(Context c) {
        if (body == null) body = font(c, "fonts/SpaceGrotesk-Medium.ttf", Typeface.DEFAULT);
        return body;
    }

    static Typeface bodyBold(Context c) {
        if (bodyBold == null) bodyBold = font(c, "fonts/SpaceGrotesk-Bold.ttf", Typeface.DEFAULT_BOLD);
        return bodyBold;
    }

    static Typeface mono(Context c) {
        if (mono == null) mono = font(c, "fonts/SpaceMono-Bold.ttf", Typeface.MONOSPACE);
        return mono;
    }

    private static Typeface font(Context c, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getApplicationContext().getAssets(), path);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- fondos ----------

    private static GradientDrawable rect(int fill, int strokePx, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    /** Bloque con borde negro y sombra dura abajo a la derecha. */
    static Drawable block(Context c, int fill, int shadowDp, int borderDp) {
        int s = dp(c, shadowDp);
        LayerDrawable l = new LayerDrawable(new Drawable[]{rect(INK, 0, 0), rect(fill, dp(c, borderDp), INK)});
        l.setLayerInset(0, s, s, 0, 0);
        l.setLayerInset(1, 0, 0, s, s);
        return l;
    }

    /** Bloque "pulsado": desplazado sobre su sombra. */
    private static Drawable pressed(Context c, int fill, int shadowDp, int borderDp) {
        int s = dp(c, shadowDp);
        LayerDrawable l = new LayerDrawable(new Drawable[]{rect(fill, dp(c, borderDp), INK)});
        l.setLayerInset(0, s, s, 0, 0);
        return l;
    }

    static Drawable buttonBg(Context c, int fill, int focusFill, int shadowDp) {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, pressed(c, fill, shadowDp, 3));
        st.addState(new int[]{android.R.attr.state_focused}, block(c, focusFill, shadowDp, 5));
        st.addState(new int[]{-android.R.attr.state_enabled}, block(c, DISABLED, shadowDp, 3));
        st.addState(new int[]{}, block(c, fill, shadowDp, 3));
        return st;
    }

    // ---------- piezas ----------

    static Button button(Context c, String label, int fill, int textColor, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label.toUpperCase(Locale.getDefault()));
        b.setAllCaps(false);
        b.setTypeface(mono(c));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setLetterSpacing(0.04f);
        int focus = fill == ORANGE ? PURPLE : ORANGE;
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{focus == PURPLE ? SNOW : BLACK, textColor}));
        b.setBackground(buttonBg(c, fill, focus, 5));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 12), dp(c, 19), dp(c, 17));
        b.setMinHeight(dp(c, 56));
        b.setOnClickListener(l);
        return b;
    }

    static LinearLayout.LayoutParams spaced(Context c, int width, int height, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height, weight);
        lp.setMargins(0, dp(c, 6), dp(c, 6), dp(c, 6));
        return lp;
    }

    static LinearLayout.LayoutParams full(Context c) {
        return spaced(c, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0);
    }

    static LinearLayout.LayoutParams half(Context c) {
        return spaced(c, 0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    /** Tarjeta: bloque con borde, sombra y una etiqueta tipo «01 // TÍTULO». */
    static LinearLayout card(Context c, String tag, int fill) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(block(c, fill, 7, 3));
        card.setPadding(dp(c, 16), dp(c, 14), dp(c, 22), dp(c, 20));
        if (tag != null) {
            TextView t = new TextView(c);
            t.setText(tag.toUpperCase(Locale.getDefault()));
            t.setTypeface(mono(c));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setBackgroundColor(fill == ORANGE ? PAPER : ORANGE);
            t.setTextColor(BLACK);
            t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, 10);
            card.addView(t, lp);
        }
        return card;
    }

    /**
     * Tarjeta plegable: tocando la etiqueta se abre o se cierra. Plegada solo enseña la etiqueta y
     * las primeras «keep» líneas (el resumen). Se recuerda abierta o cerrada; empieza cerrada.
     */
    static void foldable(final Context c, final LinearLayout card, final String key, final int keep) {
        if (card.getChildCount() == 0 || !(card.getChildAt(0) instanceof TextView)) return;
        final TextView tag = (TextView) card.getChildAt(0);
        final String label = tag.getText().toString();
        final android.content.SharedPreferences p = c.getSharedPreferences("plegado", Context.MODE_PRIVATE);
        tag.setClickable(true);
        tag.setFocusable(true);
        tag.setMinHeight(dp(c, 36));
        tag.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final Runnable apply = new Runnable() {
            @Override
            public void run() {
                boolean open = p.getBoolean(key, false);
                tag.setText(label + (open ? "   ▾ CERRAR" : "   ▸ ABRIR"));
                for (int i = 1 + keep; i < card.getChildCount(); i++) {
                    card.getChildAt(i).setVisibility(open ? View.VISIBLE : View.GONE);
                }
            }
        };
        tag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                p.edit().putBoolean(key, !p.getBoolean(key, false)).apply();
                apply.run();
            }
        });
        apply.run();
    }

    static LinearLayout.LayoutParams cardParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(c, 10), 0, dp(c, 10));
        return lp;
    }

    static TextView title(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s.toUpperCase(Locale.getDefault()));
        t.setTypeface(display(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView text(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(body(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.1f);
        t.setPadding(0, dp(c, 2), 0, dp(c, 6));
        return t;
    }

    /**
     * Campo de contraseña que se ve con puntos. Ojo: setSingleLine() después de setInputType()
     * quita los puntos y deja la contraseña a la vista, por eso va primero.
     */
    static android.widget.EditText password(Context c, String hint) {
        android.widget.EditText et = new android.widget.EditText(c);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        et.setHint(hint);
        return et;
    }

    static TextView mono(Context c, float sp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(mono(c));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static void style(CompoundButton b) {
        Context c = b.getContext();
        b.setTypeface(bodyBold(c));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(INK);
        b.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PURPLE, INK}));
        b.setPadding(b.getPaddingLeft(), dp(c, 8), b.getPaddingRight(), dp(c, 8));
    }

    static ImageView logo(Context c, int heightDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(dark ? R.drawable.logo_oscuro : R.drawable.logo);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_START);
        iv.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, heightDp)));
        return iv;
    }
}
