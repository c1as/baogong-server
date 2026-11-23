package com.baogong.server.installer;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.baogong.server.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 首次安装 / 版本升级时，把 assets 里内置的报工系统程序文件解压到可写目录。
 *
 * 设计要点（决定能否热更新）：
 *  - public/ 下的 PHP/JS/CSS 会覆盖到 filesDir/baogong/public（可写）——
 *    这是 Web 程序主体，之后通过系统文件管理器覆盖这里的文件即可热更新。
 *  - data/ 属于用户数据，【永不覆盖】：仅在首次创建空目录结构，
 *    升级时保留数据库/图片/快照，避免热更新或 App 升级导致数据丢失。
 *  - app.env 仅首次从模板生成，之后不覆盖（保留用户密码）。
 */
public final class AssetInstaller {

    private static final String TAG = "AssetInstaller";
    private static final String ASSET_BASE = "baogong/";

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    public static void install(final Context context, final Callback callback) {
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                File root = Config.root(context);
                File marker = Config.installedMarker(context);
                String installed = read(marker);
                boolean firstTime = installed == null || installed.trim().isEmpty();

                // 1) 建立可写目录骨架
                mkdirs(Config.webRoot(context));
                mkdirs(new File(Config.dataDir(context), "db"));
                mkdirs(new File(Config.dataDir(context), "snapshots"));
                mkdirs(new File(Config.dataDir(context), "images/parts"));
                mkdirs(new File(Config.dataDir(context), "images/attachments"));
                mkdirs(new File(Config.dataDir(context), "images/drawings"));
                mkdirs(Config.phpDir(context));

                // 2) 首次安装 app.env（从模板复制，仅一次）
                File env = new File(root, "app.env");
                if (!env.exists()) {
                    copyAssetIfExists(context, ASSET_BASE + "app.env.example", env);
                }

                // 3) PHP 运行时（v1.3 起）不再解压到 filesDir：
                //    Android 10+ 禁止在私有数据目录 exec（noexec），v1.2 因此报 error=13。
                //    现二进制与全部依赖 .so 作为 native 库随 APK 安装到 nativeLibraryDir（可执行）。
                //    此处仅创建 php 配置/日志目录即可，运行时由 PhpServer 从 nativeLibraryDir 加载。
                File phpBin = new File(Config.nativeLibDir(context), "libphp_bin.so");
                com.baogong.server.DiagLog.i(context, "PHP 运行时(libphp_bin.so)位于 nativeLibraryDir, 存在="
                        + phpBin.exists() + ", 目录=" + Config.nativeLibDir(context));

                // 4) 解压 public/ 程序文件（首次安装或版本变更时覆盖）
                if (firstTime || !Config.BUNDLED_WEB_VERSION.equals(installed)) {
                    copyAssetsRecursive(context, ASSET_BASE + "public/", Config.webRoot(context));
                    write(marker, Config.BUNDLED_WEB_VERSION);
                    ok = true;
                    msg = firstTime ? "首次安装完成" : "程序已更新到 " + Config.BUNDLED_WEB_VERSION;
                } else {
                    ok = true;
                    msg = "使用已安装程序（可通过文件管理器热更新）";
                }
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                ok = false;
                msg = "安装失败：" + e.getMessage();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            if (callback != null) {
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(() -> callback.onDone(fOk, fMsg));
            }
        }, "asset-installer").start();
    }

    private static void mkdirs(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "mkdirs failed: " + dir);
        }
    }

    /** 递归复制 assets 某个目录下的所有文件到目标目录。 */
    private static void copyAssetsRecursive(Context c, String assetPath, File target)
            throws IOException {
        AssetManager am = c.getAssets();
        String[] children = am.list(assetPath);
        if (children == null) {
            return;
        }
        mkdirs(target);
        for (String child : children) {
            String sub = assetPath + child;   // assetPath 以 '/' 结尾
            File dest = new File(target, child);
            // AssetManager.list() 对"目录"返回其条目（可能为空数组），对"文件"返回 null。
            // 由于空目录和无扩展文件都可能返回空/null，用 open() 能否成功来最终判定。
            InputStream probe = null;
            try {
                probe = am.open(sub);
            } catch (IOException ignored) {
                // 打不开 => 是目录
            }
            if (probe == null) {
                // 目录：确保路径以 '/' 结尾后递归
                copyAssetsRecursive(c, sub + "/", dest);
            } else {
                // 文件：复制内容
                try (InputStream in = probe;
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void copyAssetIfExists(Context c, String assetPath, File dest) {
        try (InputStream in = c.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Log.w(TAG, "copy asset failed: " + assetPath + " -> " + e.getMessage());
        }
    }

    private static String read(File f) {
        if (!f.exists()) {
            return null;
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, String text) {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        } catch (IOException e) {
            Log.w(TAG, "write marker failed", e);
        }
    }

    private AssetInstaller() {
    }
}
