package com.baogong.server.installer;

import android.content.Context;
import android.net.Uri;

import com.baogong.server.Config;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 热更新导入器：把用户从系统文件选择器选中的文件导入到 public/。
 *
 * 支持：
 *  - 单个文件（.php/.js/.css/.html/.json 等）→ 放到 public/ 根目录，同名覆盖；
 *  - zip 整包 → 解压到 public/，保留包内目录结构（如 plugins/xxx/handler.php）。
 *
 * 安全：解压时校验每个条目的目标路径必须位于 public/ 内，防止 zip-slip 路径穿越。
 */
public final class HotUpdateImporter {

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int fileCount;

        Result(boolean ok, String message, int fileCount) {
            this.ok = ok;
            this.message = message;
            this.fileCount = fileCount;
        }
    }

    /** 从 Uri 导入到 public/（会自动创建 public 目录）。 */
    public static Result importToPublic(Context context, Uri uri) {
        String name = guessName(context, uri);
        if (name == null) {
            name = "update.bin";
        }
        File webRoot = Config.webRoot(context);
        if (!webRoot.exists()) {
            webRoot.mkdirs();
        }
        try {
            if (name.toLowerCase(Locale.US).endsWith(".zip")) {
                return importZip(context, uri, webRoot);
            } else {
                File dest = new File(webRoot, name);
                copyUriToFile(context, uri, dest);
                return new Result(true, "已导入：" + name + "（" + dest.length() + " 字节）", 1);
            }
        } catch (Exception e) {
            return new Result(false, "导入失败：" + e.getMessage(), 0);
        }
    }

    private static Result importZip(Context context, Uri uri, File webRoot) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return new Result(false, "无法读取所选文件", 0);
            }
            return importZipStream(in, webRoot);
        }
    }

    /**
     * 从本地 zip 文件解压到指定目录（供仓库热更新先解压到暂存目录用）。
     * 同样带 zip-slip 防护与空包校验。
     */
    public static Result importZipFile(File zipFile, File targetDir) throws IOException {
        try (InputStream in = new java.io.FileInputStream(zipFile)) {
            return importZipStream(in, targetDir);
        }
    }

    /** 把 zip 输入流解压到 targetDir，带 zip-slip 防护。 */
    public static Result importZipStream(InputStream in, File targetDir) throws IOException {
        int count = 0;
        String rootCanonical = targetDir.getCanonicalPath();
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;
            byte[] buf = new byte[16384];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                File target = new File(targetDir, entryName);
                // zip-slip 防护：目标必须在 targetDir 内
                String targetCanonical = target.getCanonicalPath();
                if (!targetCanonical.equals(rootCanonical)
                        && !targetCanonical.startsWith(rootCanonical + File.separator)) {
                    continue; // 跳过危险条目
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                count++;
            }
        }
        if (count == 0) {
            return new Result(false, "zip 包内没有可导入的文件", 0);
        }
        return new Result(true, "已从 zip 导入 " + count + " 个文件", count);
    }

    private static void copyUriToFile(Context context, Uri uri, File dest) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("无法读取所选文件");
            }
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /** 从 Uri 推断文件名。 */
    private static String guessName(Context context, Uri uri) {
        String display = null;
        // 优先从 OpenableColumns.DISPLAY_NAME 读取
        try (android.database.Cursor c = context.getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    display = c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        if (display == null || display.isEmpty()) {
            String last = uri.getLastPathSegment();
            display = last == null ? null : last.substring(last.lastIndexOf('/') + 1);
        }
        return display;
    }

    private HotUpdateImporter() {
    }
}
