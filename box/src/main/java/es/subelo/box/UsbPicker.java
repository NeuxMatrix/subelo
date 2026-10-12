package es.subelo.box;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Elegir el disco USB sin el selector de carpetas de Android (muchos box de TV no lo traen).
 * Busca los discos montados, dice en cuáles se puede escribir y, si falta el permiso de
 * «todos los archivos», ayuda a darlo. Todo se maneja con el mando.
 */
final class UsbPicker {

    /** Un disco detectado. */
    static final class Disk {
        String label;
        File root;
        boolean rootWritable;
        File appDir; // carpeta privada de Súbelo dentro del disco (siempre se puede escribir)
        long free = -1, total = -1;
    }

    /** Se ha abierto la pantalla de permisos: al volver, la pantalla del box reabre la lista. */
    static boolean returningFromSettings;

    private UsbPicker() {}

    static boolean needsAllFiles() {
        return Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager();
    }

    /** Discos externos montados (no la memoria interna). */
    static List<Disk> detect(Context c) {
        Map<String, Disk> found = new LinkedHashMap<String, Disk>();
        String primary = Environment.getExternalStorageDirectory().getAbsolutePath();

        if (Build.VERSION.SDK_INT >= 24) {
            StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (v.isPrimary() || !Environment.MEDIA_MOUNTED.equals(v.getState())
                        && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(v.getState())) continue;
                File dir = volumeDir(v);
                if (dir == null) continue;
                Disk d = new Disk();
                d.label = v.getDescription(c);
                d.root = dir;
                found.put(dir.getAbsolutePath(), d);
            }
        }
        // carpetas privadas de la app en cada disco: nos dicen dónde está montado aunque lo anterior falle
        File[] ext = c.getExternalFilesDirs(null);
        if (ext != null) {
            for (File f : ext) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int i = p.indexOf("/Android/data/");
                if (i <= 0 || p.startsWith(primary) || p.startsWith("/storage/emulated")) continue;
                String root = p.substring(0, i);
                Disk d = found.get(root);
                if (d == null) {
                    d = new Disk();
                    d.root = new File(root);
                    found.put(root, d);
                }
                d.appDir = f;
            }
        }
        for (String base : new String[]{"/storage", "/mnt/usb", "/mnt/usb_storage", "/mnt/sdcard/usb"}) {
            File[] ls = new File(base).listFiles();
            if (ls == null) continue;
            for (File f : ls) {
                String n = f.getName();
                String p = f.getAbsolutePath();
                if (!f.isDirectory() || n.equals("emulated") || n.equals("self") || n.startsWith(".")
                        || p.equals(primary) || found.containsKey(p)) continue;
                Disk d = new Disk();
                d.root = f;
                found.put(p, d);
            }
        }
        List<Disk> out = new ArrayList<Disk>();
        for (Disk d : found.values()) {
            if (d.label == null || d.label.isEmpty()) d.label = "Disco " + d.root.getName();
            d.rootWritable = canWrite(d.root);
            if (d.appDir != null) {
                d.appDir.mkdirs();
                if (!canWrite(d.appDir)) d.appDir = null;
            }
            try {
                StatFs s = new StatFs(d.root.getAbsolutePath());
                d.free = s.getAvailableBytes();
                d.total = s.getTotalBytes();
            } catch (RuntimeException ignored) {
            }
            out.add(d);
        }
        return out;
    }

    private static File volumeDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= 30) return v.getDirectory();
        try {
            return (File) StorageVolume.class.getMethod("getPathFile").invoke(v);
        } catch (Exception e) {
            try {
                return new File((String) StorageVolume.class.getMethod("getPath").invoke(v));
            } catch (Exception e2) {
                return null;
            }
        }
    }

    static boolean canWrite(File dir) {
        if (!dir.isDirectory()) return false;
        File t = new File(dir, ".subelo_prueba_" + System.nanoTime());
        try {
            FileOutputStream o = new FileOutputStream(t);
            o.write(1);
            o.close();
            t.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- pantalla ----------

    static void show(final Activity a, final Runnable onChosen) {
        final List<Disk> disks = detect(a);
        ScrollView scroll = new ScrollView(a);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Brutal.dp(a, 20);
        col.setPadding(p, p, p, p);
        col.setBackgroundColor(Brutal.CREAM);
        scroll.addView(col);

        col.addView(Brutal.title(a, "Disco USB", 24, Brutal.INK));
        TextView diag = Brutal.mono(a, 13, Brutal.INK);
        diag.setPadding(0, Brutal.dp(a, 8), 0, Brutal.dp(a, 8));
        StringBuilder sb = new StringBuilder();
        sb.append("ANDROID ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        if (Build.VERSION.SDK_INT >= 30) {
            sb.append(" · PERMISO TODOS LOS ARCHIVOS: ").append(needsAllFiles() ? "NO" : "SÍ");
        }
        if (disks.isEmpty()) {
            sb.append("\n\nNo veo ningún disco USB conectado.\n• Comprueba que la tele lo reconoce (Ajustes › Almacenamiento).\n"
                    + "• Si el disco tiene alimentación propia, que esté encendido.\n"
                    + "• Formato recomendado: exFAT. Muchos box solo leen los discos NTFS, sin poder escribir.");
        }
        for (Disk d : disks) {
            sb.append("\n\n▸ ").append(d.label).append("  ").append(d.root.getAbsolutePath());
            if (d.total > 0) sb.append("\n  libre ").append(gb(d.free)).append(" de ").append(gb(d.total));
            sb.append("\n  se puede escribir: ").append(d.rootWritable ? "SÍ" : "NO");
        }
        diag.setText(sb.toString());
        col.addView(diag);

        final AlertDialog dlg = new AlertDialog.Builder(a).setView(scroll).create();

        for (final Disk d : disks) {
            if (d.rootWritable) {
                col.addView(Brutal.button(a, "Usar " + d.label, Brutal.PURPLE, Brutal.SNOW, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dlg.dismiss();
                        Config.setStore(a, "file", d.root.getAbsolutePath());
                        onChosen.run();
                    }
                }), Brutal.full(a));
            }
        }
        boolean anyWritable = false;
        for (Disk d : disks) anyWritable |= d.rootWritable;

        if (!anyWritable && needsAllFiles() && !disks.isEmpty()) {
            TextView t = Brutal.text(a, 15, Brutal.INK);
            t.setText("Para escribir en el disco, Android pide el permiso «Acceso a todos los archivos». "
                    + "Pulsa el botón, activa Súbelo Servidor y vuelve aquí.");
            col.addView(t);
            col.addView(Brutal.button(a, "Dar permiso de todos los archivos", Brutal.ORANGE, Brutal.BLACK,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dlg.dismiss();
                            requestAllFiles(a);
                        }
                    }), Brutal.full(a));
        }
        if (!anyWritable) {
            for (final Disk d : disks) {
                if (d.appDir == null) continue;
                col.addView(Brutal.button(a, "Usar la carpeta de Súbelo en " + d.label, Brutal.WHITE, Brutal.INK,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                confirmAppDir(a, dlg, d, onChosen);
                            }
                        }), Brutal.full(a));
            }
        }
        col.addView(Brutal.button(a, "Cerrar", Brutal.WHITE, Brutal.INK, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        }), Brutal.full(a));
        dlg.show();
    }

    private static void confirmAppDir(final Activity a, final AlertDialog parent, final Disk d, final Runnable onChosen) {
        new AlertDialog.Builder(a)
                .setTitle("Carpeta de Súbelo en el disco")
                .setMessage("Las fotos irán a " + d.appDir.getAbsolutePath() + ".\n\n"
                        + "Ojo: si DESINSTALAS la app del box, Android borra esa carpeta con todo lo que tenga. "
                        + "Actualizar la app (instalar encima) no la borra. Desde un ordenador la verás dentro del disco en "
                        + "Android/data/es.subelo.box/files.")
                .setPositiveButton("Usarla", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface di, int w) {
                        parent.dismiss();
                        Config.setStore(a, "file", d.appDir.getAbsolutePath());
                        onChosen.run();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    static void requestAllFiles(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
            returningFromSettings = true;
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            returningFromSettings = true;
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        new AlertDialog.Builder(a)
                .setTitle("Este box no tiene esa pantalla")
                .setMessage("Se puede dar el permiso desde un ordenador con ADB (depuración USB o por red activada "
                        + "en Opciones de desarrollador):\n\nadb shell appops set --uid " + a.getPackageName()
                        + " MANAGE_EXTERNAL_STORAGE allow\n\nO usa la carpeta de Súbelo en el disco.")
                .setPositiveButton("Vale", null)
                .show();
    }

    private static String gb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB", bytes / 1e9);
    }
}
