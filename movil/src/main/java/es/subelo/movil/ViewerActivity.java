package es.subelo.movil;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visor a pantalla completa de una foto o vídeo del disco del box. Desliza para pasar. */
public class ViewerActivity extends Activity {
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final List<BoxClient.RemoteFile> files = new ArrayList<BoxClient.RemoteFile>();
    private int index;
    private BoxClient bc;
    private FrameLayout stage;
    private TextView title, message;
    private Button echo;
    private GestureDetector gestures;
    private VideoProxy proxy;

    static void open(Context c, List<BoxClient.RemoteFile> files, int index) {
        ArrayList<String> dirs = new ArrayList<String>(), names = new ArrayList<String>();
        long[] sizes = new long[files.size()], mtimes = new long[files.size()];
        for (int i = 0; i < files.size(); i++) {
            dirs.add(files.get(i).dir);
            names.add(files.get(i).name);
            sizes[i] = files.get(i).size;
            mtimes[i] = files.get(i).mtime;
        }
        c.startActivity(new Intent(c, ViewerActivity.class)
                .putStringArrayListExtra("dirs", dirs).putStringArrayListExtra("names", names)
                .putExtra("sizes", sizes).putExtra("mtimes", mtimes).putExtra("index", index));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Brutal.apply(this);
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        ArrayList<String> dirs = in.getStringArrayListExtra("dirs");
        ArrayList<String> names = in.getStringArrayListExtra("names");
        long[] sizes = in.getLongArrayExtra("sizes"), mtimes = in.getLongArrayExtra("mtimes");
        if (dirs == null || names == null || sizes == null || mtimes == null) {
            finish();
            return;
        }
        for (int i = 0; i < names.size(); i++) files.add(new BoxClient.RemoteFile(dirs.get(i), names.get(i), sizes[i], mtimes[i]));
        index = savedInstanceState != null ? savedInstanceState.getInt("index") : in.getIntExtra("index", 0);
        bc = BoxClient.fromPrefs(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        message = Brutal.mono(this, 14, Brutal.PAPER);
        message.setGravity(Gravity.CENTER);
        root.addView(message, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setBackgroundColor(Brutal.BLACK);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(4), dp(4), dp(4));
        title = Brutal.mono(this, 12, Brutal.PAPER);
        title.setPadding(dp(12), 0, dp(8), 0);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button dl = Brutal.button(this, "⬇ Al móvil", Brutal.ORANGE, Brutal.BLACK, null);
        dl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UploadService.download(ViewerActivity.this, Collections.singletonList(files.get(index)));
                Toast.makeText(ViewerActivity.this, "Descargando a la galería (carpeta Subelo)", Toast.LENGTH_SHORT).show();
            }
        });
        top.addView(Brutal.button(this, "↦", Brutal.PAPER, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (files.isEmpty()) return;
                startActivityForResult(new Intent(ViewerActivity.this, FolderActivity.class)
                        .putExtra(FolderActivity.EXTRA_PICK, files.get(index).dir), REQ_MOVE);
            }
        }), Brutal.spaced(this, -2, -2, 0));
        top.addView(Brutal.button(this, "🗑", 0xFFE5322D, Brutal.SNOW, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        }), Brutal.spaced(this, -2, -2, 0));
        top.addView(dl, Brutal.spaced(this, -2, -2, 0));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout nav = new LinearLayout(this);
        nav.setBackgroundColor(Brutal.BLACK);
        nav.setPadding(dp(10), dp(4), dp(4), dp(4));
        Button prev = Brutal.button(this, "‹", Brutal.PAPER, Brutal.BLACK, null);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(-1);
            }
        });
        Button next = Brutal.button(this, "›", Brutal.PURPLE, Brutal.SNOW, null);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(1);
            }
        });
        echo = Brutal.button(this, "📺 Al Echo", Brutal.ORANGE, Brutal.BLACK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!files.isEmpty()) Echo.send(ViewerActivity.this, Collections.singletonList(files.get(index)));
            }
        });
        nav.addView(prev, Brutal.spaced(this, dp(72), -2, 0));
        nav.addView(echo, Brutal.half(this));
        nav.addView(next, Brutal.spaced(this, dp(72), -2, 0));
        root.addView(nav, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX();
                if (Math.abs(dx) > dp(80) && Math.abs(vx) > Math.abs(vy)) {
                    step(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        setContentView(root);
        show();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gestures != null && !(stage.getChildAt(0) instanceof VideoView)) gestures.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        Echo.onPermission(this, requestCode, results);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("index", index);
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        if (proxy != null) proxy.stop();
        File[] old = new File(getCacheDir(), "visor").listFiles();
        if (old != null) for (File f : old) f.delete();
        super.onDestroy();
    }

    private static final int REQ_MOVE = 1;

    private void confirmDelete() {
        if (files.isEmpty()) return;
        final BoxClient.RemoteFile f = files.get(index);
        new android.app.AlertDialog.Builder(this)
                .setTitle("¿Borrar del disco?")
                .setMessage("Vas a borrar «" + f.name + "».\n\nNo se puede deshacer.")
                .setPositiveButton("Borrar", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        change(f, null);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MOVE || resultCode != RESULT_OK || data == null || files.isEmpty()) return;
        final BoxClient.RemoteFile f = files.get(index);
        final String to = data.getStringExtra("path");
        if (to == null || to.equals(f.dir)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("¿Mover?")
                .setMessage("«" + f.name + "» irá a DISCO:/" + Prefs.user(this) + "/" + to)
                .setPositiveButton("Mover", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        change(f, to);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Borra (to == null) o mueve la foto y la quita del visor. */
    private void change(final BoxClient.RemoteFile f, final String to) {
        bg.execute(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    if (to == null) bc.delete(f.dir, Collections.singletonList(f.name));
                    else bc.move(f.dir, Collections.singletonList(f.name), to);
                } catch (IOException e) {
                    err = e.getMessage();
                }
                final String msg = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (msg != null) {
                            Toast.makeText(ViewerActivity.this, msg, Toast.LENGTH_LONG).show();
                            return;
                        }
                        Toast.makeText(ViewerActivity.this, to == null ? "Borrada" : "Movida", Toast.LENGTH_SHORT).show();
                        int i = files.indexOf(f);
                        if (i >= 0) files.remove(i);
                        if (files.isEmpty()) {
                            finish();
                            return;
                        }
                        if (index >= files.size()) index = files.size() - 1;
                        show();
                    }
                });
            }
        });
    }

    private void step(int d) {
        int i = index + d;
        if (i < 0 || i >= files.size()) return;
        index = i;
        show();
    }

    private void show() {
        final BoxClient.RemoteFile f = files.get(index);
        title.setText((index + 1) + "/" + files.size() + "  " + f.name);
        echo.setVisibility(Downloader.isImage(f.name) ? View.VISIBLE : View.INVISIBLE);
        stage.removeAllViews();
        message.setText("");
        if (bc == null) {
            message.setText("No hay box configurado");
            return;
        }
        if (Downloader.isVideo(f.name)) {
            VideoView vv = new VideoView(this);
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            if (proxy == null) proxy = new VideoProxy(bc);
            try {
                vv.setVideoURI(Uri.parse(proxy.url(f.dir, f.name)));
            } catch (IOException e) {
                message.setText("No se pudo preparar el vídeo: " + e.getMessage());
                return;
            }
            stage.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            vv.start();
            return;
        }
        if (!Downloader.isImage(f.name)) {
            message.setText(f.name + "\n\nEste archivo no se puede ver aquí. Pulsa «Al móvil» para descargarlo.");
            return;
        }
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        message.setText("CARGANDO…");
        final int want = index;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int maxSide = Math.max(dm.widthPixels, dm.heightPixels);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                String err = null;
                try {
                    b = fetch(f, maxSide);
                } catch (IOException e) {
                    err = e.getMessage();
                } catch (OutOfMemoryError e) {
                    err = "La foto es demasiado grande para verla aquí";
                }
                final Bitmap bm = b;
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (want != index) return;
                        if (bm != null) {
                            iv.setImageBitmap(bm);
                            message.setText("");
                        } else {
                            message.setText(error != null ? error : "No se puede mostrar esta foto (formato no compatible).");
                        }
                    }
                });
            }
        });
    }

    /** Descarga la foto a la caché y la decodifica al tamaño de la pantalla, girada según EXIF. */
    private Bitmap fetch(BoxClient.RemoteFile f, int maxSide) throws IOException {
        File dir = new File(getCacheDir(), "visor");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Sin espacio en el móvil");
        File tmp = new File(dir, Integer.toHexString((f.dir + "/" + f.name).hashCode()) + ".img");
        if (!tmp.isFile() || tmp.length() != f.size) {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                bc.download(f.dir, f.name, out, null, null);
            } finally {
                out.close();
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(tmp.getPath(), o2);
        if (b == null) return null;
        int rot = 0;
        try {
            int or = new ExifInterface(tmp.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (or == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (or == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (or == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (IOException ignored) {
        }
        if (rot == 0) return b;
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
