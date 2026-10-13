package es.subelo.movil;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Pantalla de desbloqueo: huella (si está activada) o contraseña de Súbelo. */
public class LockActivity extends Activity {
    private EditText pw;
    private TextView error;
    private CancellationSignal cancel;
    private boolean askedBiometric;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) askedBiometric = savedInstanceState.getBoolean("pedida");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Brutal.CREAM);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(22), dp(28), dp(16), dp(28));
        scroll.addView(col);

        col.addView(Brutal.logo(this, 52), new LinearLayout.LayoutParams(-2, dp(52)));
        LinearLayout card = Brutal.card(this, "🔒 bloqueado", Brutal.WHITE);
        TextView t = Brutal.title(this, "Hola, " + Prefs.user(this), 26, Brutal.INK);
        t.setPadding(0, 0, 0, dp(6));
        card.addView(t);
        TextView msg = Brutal.text(this, 15, Brutal.INK);
        msg.setText(AppLock.useBiometric(this)
                ? "Pon tu huella o escribe tu contraseña de Súbelo para entrar."
                : "Escribe tu contraseña de Súbelo para entrar.");
        card.addView(msg);
        pw = new EditText(this);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setHint("Contraseña");
        pw.setSingleLine(true);
        pw.setTextColor(Brutal.INK);
        pw.setHintTextColor(0xFF8A8580);
        pw.setImeOptions(EditorInfo.IME_ACTION_DONE);
        pw.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                tryPassword();
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        card.addView(pw, lp);
        error = Brutal.mono(this, 12, Brutal.RED);
        error.setVisibility(View.GONE);
        card.addView(error);
        card.addView(Brutal.button(this, "Entrar", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryPassword();
            }
        }), Brutal.full(this));
        if (AppLock.useBiometric(this)) {
            card.addView(Brutal.button(this, "👆 Usar la huella", Brutal.PURPLE, Brutal.SNOW, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askBiometric();
                }
            }), Brutal.full(this));
        }
        Button forgot = Brutal.button(this, "He olvidado la contraseña", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forgot();
            }
        });
        card.addView(forgot, Brutal.full(this));
        col.addView(card, Brutal.cardParams(this));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AppLock.isUnlocked()) { // ya se desbloqueó desde otra pantalla de bloqueo
            done();
            return;
        }
        if (!askedBiometric && AppLock.useBiometric(this)) {
            askedBiometric = true;
            askBiometric();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (cancel != null) {
            cancel.cancel();
            cancel = null;
        }
        if (!isChangingConfigurations()) askedBiometric = false; // al volver a la app, se pide otra vez
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("pedida", askedBiometric);
    }

    @Override
    public void onBackPressed() {
        finishAffinity(); // atrás no deja ver lo de debajo: sale de la app
    }

    private void tryPassword() {
        long wait = AppLock.waitMs(this);
        if (wait > 0) {
            showError("Demasiados intentos. Espera " + ((wait + 999) / 1000) + " s.");
            return;
        }
        if (AppLock.check(this, pw.getText().toString())) {
            done();
            return;
        }
        pw.setText("");
        wait = AppLock.waitMs(this);
        showError(wait > 0 ? "Contraseña incorrecta. Espera " + ((wait + 999) / 1000) + " s." : "Contraseña incorrecta.");
    }

    private void showError(String s) {
        error.setText(s);
        error.setVisibility(View.VISIBLE);
    }

    private void askBiometric() {
        if (Build.VERSION.SDK_INT < 28) return;
        if (cancel != null) cancel.cancel();
        cancel = new CancellationSignal();
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(this)
                .setTitle("Súbelo")
                .setSubtitle("Pon tu huella para entrar")
                .setNegativeButton("Usar contraseña", getMainExecutor(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        cancel = null;
                        pw.requestFocus();
                    }
                });
        if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false);
        try {
            b.build().authenticate(cancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    cancel = null;
                    AppLock.biometricOk(LockActivity.this);
                    done();
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    cancel = null;
                    if (code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                            && code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && msg != null) {
                        showError(msg + ". Usa la contraseña.");
                    }
                }
            });
        } catch (RuntimeException e) {
            cancel = null;
            showError("La huella no está disponible: usa la contraseña.");
        }
    }

    private void forgot() {
        new AlertDialog.Builder(this)
                .setTitle("¿Olvidaste la contraseña?")
                .setMessage("Es la misma con la que entras en Súbelo como " + Prefs.user(this) + ".\n\n"
                        + "Si no la recuerdas, cierra la sesión en este móvil. Para volver a entrar hará falta "
                        + "la contraseña; si nadie la recuerda, se puede borrar desde la app del box en la tele "
                        + "y crear una nueva.")
                .setPositiveButton("Cerrar sesión", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Prefs.logout(LockActivity.this);
                        startActivity(new Intent(LockActivity.this, LoginActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        finishAffinity();
                    }
                })
                .setNegativeButton("Volver", null)
                .show();
    }

    private void done() {
        finish();
        overridePendingTransition(0, 0);
    }

    private int dp(float v) {
        return Brutal.dp(this, v);
    }
}
