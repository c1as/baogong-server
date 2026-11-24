package com.baogong.server.installer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

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

    /**
     * 导入用户选中的多个文件（对应 ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE）。
     * 每个文件按其自身规则处理：zip 解压、单文件放到 public/ 根。
     * 汇总成功数与失败信息；只要有一个成功即返回 ok。
     */
    public static Result importMultiple(Context context, java.util.List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            return new Result(false, "未选择任何文件", 0);
        }
        int okCount = 0;
        int fileCount = 0;
        StringBuilder fails = new StringBuilder();
        for (Uri uri : uris) {
            Result r = importToPublic(context, uri);
            if (r.ok) {
                okCount++;
                fileCount += r.fileCount;
            } else {
                if (fails.length() > 0) {
                    fails.append("；");
                }
                fails.append(r.message);
            }
        }
        String msg = "成功导入 " + okCount + " 项（共 " + fileCount + " 个文件）";
        if (fails.length() > 0) {
            msg += "；失败：" + fails;
        }
        return new Result(okCount > 0, msg, fileCount);
    }

    /**
     * 从 SAF 目录树 Uri 递归导入整个文件夹（对应 ACTION_OPEN_DOCUMENT_TREE）。
     *
     * 语义：把用户选中的文件夹当作“新的 public/ 根”——里面的文件按相对路径覆盖到 public/，
     * 例如选中解压后的 public 文件夹，其内部 api.php → public/api.php，
     * plugins/xxx/handler.php → public/plugins/xxx/handler.php。
     *
     * 同名直接覆盖（不是追加 (2)），符合“整包替换”的直觉。
     * 安全：每个目标文件都做 canonical 越界校验，必须位于 webRoot 内；只写 public/，不碰 data/。
     *
     * @param treeUri   ACTION_OPEN_DOCUMENT_TREE 返回的目录树 Uri
     * @param webRoot   目标根目录（Config.webRoot(context)）
     */
    public static Result importTree(Context context, Uri treeUri, File webRoot) {
        if (treeUri == null) {
            return new Result(false, "未选择文件夹", 0);
        }
        if (!webRoot.exists()) {
            webRoot.mkdirs();
        }
        final String rootCanonical;
        try {
            rootCanonical = webRoot.getCanonicalPath();
        } catch (IOException e) {
            return new Result(false, "无法解析目标目录：" + e.getMessage(), 0);
        }
        final int[] counter = new int[]{0};
        final int[] skipped = new int[]{0};
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            if (rootId == null) {
                return new Result(false, "无法识别所选文件夹", 0);
            }
            walkTree(context, treeUri, rootId, webRoot, rootCanonical, counter, skipped);
        } catch (Exception e) {
            return new Result(false, "导入文件夹失败：" + e.getMessage(), counter[0]);
        }
        if (counter[0] == 0) {
            return new Result(false, "所选文件夹内没有可导入的文件"
                    + (skipped[0] > 0 ? "（" + skipped[0] + " 个被安全策略跳过）" : ""), 0);
        }
        String msg = "已从文件夹导入 " + counter[0] + " 个文件";
        if (skipped[0] > 0) {
            msg += "（" + skipped[0] + " 个越界/无效项已跳过）";
        }
        return new Result(true, msg, counter[0]);
    }

    /** 递归遍历 SAF 目录树，把内容按相对路径写入 destDir。 */
    private static void walkTree(Context context, Uri treeUri, String parentDocId, File destDir,
                                 String rootCanonical, int[] counter, int[] skipped) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        Cursor cursor = context.getContentResolver().query(childrenUri, projection, null, null, null);
        if (cursor == null) {
            return;
        }
        byte[] buf = new byte[16384];
        try {
            while (cursor.moveToNext()) {
                String docId = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                String mime = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE));
                if (docId == null || name == null || name.startsWith(".")) {
                    continue;
                }
                File target = new File(destDir, name);
                // 越界校验：目标必须位于 webRoot 内，防止恶意 displayName 含 ../
                String targetCanonical = target.getCanonicalPath();
                if (!targetCanonical.equals(rootCanonical)
                        && !targetCanonical.startsWith(rootCanonical + File.separator)) {
                    skipped[0]++;
                    continue;
                }
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (isDir) {
                    if (!target.exists()) {
                        target.mkdirs();
                    }
                    walkTree(context, treeUri, docId, target, rootCanonical, counter, skipped);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    InputStream in = context.getContentResolver().openInputStream(docUri);
                    if (in == null) {
                        skipped[0]++;
                        continue;
                    }
                    try (OutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    counter[0]++;
                }
            }
        } finally {
            cursor.close();
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
