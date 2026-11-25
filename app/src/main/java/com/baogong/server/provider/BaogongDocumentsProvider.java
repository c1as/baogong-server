package com.baogong.server.provider;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.util.Log;

import com.baogong.server.Config;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * 自定义文档提供者（Storage Access Framework）：把 App 私有目录 filesDir/baogong
 * 挂载到系统"文件"App 侧边栏，用户可像操作普通文件夹一样浏览/复制/粘贴/删除文件。
 *
 * 用途：把新版 api.php / 插件 zip 直接粘贴到 public/ 覆盖即可热更新，无需 WebDAV、无需重装 APK。
 *
 * 实现参考 Anki / MT 管理器等成熟项目的 HomeDocumentsProvider 写法。
 * documentId 直接用文件的绝对路径（简单可靠）；所有路径都做 canonical 越界校验，
 * 防止通过 ../ 访问 baogong 根目录之外的文件。
 */
public class BaogongDocumentsProvider extends DocumentsProvider {

    private static final String TAG = "BgDocsProvider";
    private static final String ROOT_ID = "baogong_root";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    };
    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
    };

    private File home;

    @Override
    public boolean onCreate() {
        Context c = getContext();
        if (c == null) {
            return false;
        }
        home = Config.root(c);
        if (!home.exists()) {
            home.mkdirs();
        }
        Log.i(TAG, "DocumentsProvider 就绪, 根目录: " + home);
        return true;
    }

    /** 系统"文件"App 侧边栏显示的根条目。 */
    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        if (home == null) {
            return result;
        }
        MatrixCursor.RowBuilder row = result.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY
                        | DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_TITLE, "报工服务器");
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "网站程序与数据 · 粘贴文件即可热更新");
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, docId(home));
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_dialog_info);
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, home.getFreeSpace());
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        addRow(result, file(documentId));
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File parent = file(parentDocumentId);
        File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) {
                // 隐藏 App 内部配置文件，只暴露用户关心的 public/data 等业务目录
                String name = child.getName();
                if (name.startsWith(".") || "php".equals(name) || "logs".equals(name)) {
                    continue;
                }
                addRow(result, child);
            }
        }
        return result;
    }

    /** 打开文件：mode 可能是 r/w/rw/wt 等，交给 ParcelFileDescriptor 处理。 */
    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        File target = file(documentId);
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return mimeType(file(documentId));
    }

    /** 新建文件或目录（系统文件管理器"粘贴"新文件时走这里）。 */
    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        File parent = file(parentDocumentId);
        File target = new File(parent, displayName);
        // 同名时追加 (2)(3)…，避免覆盖用户已有文件
        for (int i = 2; target.exists(); i++) {
            target = new File(parent, displayName + " (" + i + ")");
        }
        try {
            boolean ok = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)
                    ? target.mkdirs()
                    : target.createNewFile();
            if (!ok) {
                throw new IOException("create failed: " + target);
            }
        } catch (IOException e) {
            throw new FileNotFoundException("无法创建 " + displayName + ": " + e.getMessage());
        }
        Log.i(TAG, "createDocument: " + target);
        return docId(target);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File target = file(documentId);
        if (isRoot(target)) {
            throw new FileNotFoundException("不能删除根目录");
        }
        if (!deleteRecursive(target)) {
            throw new FileNotFoundException("删除失败: " + documentId);
        }
        Log.i(TAG, "deleteDocument: " + target);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File target = file(documentId);
        if (isRoot(target)) {
            throw new FileNotFoundException("不能重命名根目录");
        }
        File dest = new File(target.getParentFile(), displayName);
        if (!target.renameTo(dest)) {
            throw new FileNotFoundException("重命名失败: " + documentId);
        }
        Log.i(TAG, "renameDocument: " + target + " -> " + dest);
        return docId(dest);
    }

    /**
     * 复制文件或整个目录树到目标目录（系统文件管理器「复制→粘贴」走这里）。
     * 同名时追加 (2)(3)…，不覆盖已有文件；全程越界校验。
     */
    @Override
    public String copyDocument(String sourceDocumentId, String targetParentDocumentId)
            throws FileNotFoundException {
        File src = file(sourceDocumentId);
        File targetParent = file(targetParentDocumentId);
        if (!targetParent.isDirectory()) {
            throw new FileNotFoundException("目标不是目录: " + targetParentDocumentId);
        }
        File dest = uniqueChild(targetParent, src.getName());
        try {
            if (src.isDirectory()) {
                copyRecursive(src, dest);
            } else {
                copyFile(src, dest);
            }
        } catch (IOException e) {
            // 复制中途失败，清理已产生的半成品，避免残留
            if (dest.exists()) {
                deleteRecursive(dest);
            }
            throw new FileNotFoundException("复制失败: " + e.getMessage());
        }
        Log.i(TAG, "copyDocument: " + src + " -> " + dest);
        return docId(dest);
    }

    /**
     * 移动文件/目录到新父目录（系统文件管理器「剪切→粘贴」走这里）。
     * 先尝试原子 renameTo；跨存储/失败时回退为「复制 + 删除」，保证语义一致。
     * 不允许把目录移动进自身或其子目录（防止无限递归）。
     */
    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId,
                               String targetParentDocumentId) throws FileNotFoundException {
        File src = file(sourceDocumentId);
        File targetParent = file(targetParentDocumentId);
        if (isRoot(src)) {
            throw new FileNotFoundException("不能移动根目录");
        }
        if (!targetParent.isDirectory()) {
            throw new FileNotFoundException("目标不是目录: " + targetParentDocumentId);
        }
        // 禁止把目录移动进自己或自己的子目录
        if (src.isDirectory() && isWithin(src, targetParent)) {
            throw new FileNotFoundException("不能把文件夹移动到它自己内部");
        }
        File dest = uniqueChild(targetParent, src.getName());
        if (src.renameTo(dest)) {
            Log.i(TAG, "moveDocument(rename): " + src + " -> " + dest);
            return docId(dest);
        }
        // 回退：复制后删除源
        try {
            if (src.isDirectory()) {
                copyRecursive(src, dest);
            } else {
                copyFile(src, dest);
            }
            if (!deleteRecursive(src)) {
                throw new IOException("删除源失败");
            }
        } catch (IOException e) {
            if (dest.exists()) {
                deleteRecursive(dest);
            }
            throw new FileNotFoundException("移动失败: " + e.getMessage());
        }
        Log.i(TAG, "moveDocument(copy+delete): " + src + " -> " + dest);
        return docId(dest);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = file(parentDocumentId);
            File child = file(documentId);
            String parentPath = parent.getCanonicalPath();
            String childPath = child.getCanonicalPath();
            return childPath.startsWith(parentPath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 内部工具

    private void addRow(MatrixCursor cursor, File file) {
        boolean dir = file.isDirectory();
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId(file));
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE,
                dir ? DocumentsContract.Document.MIME_TYPE_DIR : mimeType(file));
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        int flags = 0;
        if (dir) {
            flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        } else {
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        }
        flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                | DocumentsContract.Document.FLAG_SUPPORTS_MOVE
                | DocumentsContract.Document.FLAG_SUPPORTS_COPY;
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
        row.add(DocumentsContract.Document.COLUMN_SIZE, dir ? 0 : file.length());
    }

    private static String docId(File file) {
        return file.getAbsolutePath();
    }

    /**
     * 由 documentId（绝对路径）还原 File，并做越界校验：
     * 必须位于 home 根目录内，防止外部路径穿越。
     */
    private File file(String documentId) throws FileNotFoundException {
        File candidate = new File(documentId);
        try {
            String canonical = candidate.getCanonicalPath();
            String root = home.getCanonicalPath();
            if (!canonical.equals(root) && !canonical.startsWith(root + File.separator)) {
                throw new FileNotFoundException("越界路径: " + documentId);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        if (!candidate.exists()) {
            throw new FileNotFoundException("文件不存在: " + documentId);
        }
        return candidate;
    }

    private boolean isRoot(File file) {
        try {
            return file.getCanonicalPath().equals(home.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    private static String mimeType(File file) {
        if (file.isDirectory()) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        switch (ext) {
            case "php":
            case "html":
            case "htm":
            case "css":
            case "js":
            case "json":
            case "txt":
            case "md":
            case "log":
            case "env":
                return "text/plain";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "svg":
                return "image/svg+xml";
            case "zip":
                return "application/zip";
            case "sqlite":
            case "db":
                return "application/octet-stream";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    /** 在父目录下生成不冲突的目标名（追加 (2)(3)…），不覆盖已有文件。 */
    private File uniqueChild(File parent, String name) {
        File target = new File(parent, name);
        for (int i = 2; target.exists(); i++) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                target = new File(parent, name.substring(0, dot) + " (" + i + ")" + name.substring(dot));
            } else {
                target = new File(parent, name + " (" + i + ")");
            }
        }
        return target;
    }

    /** 递归复制目录树。 */
    private static void copyRecursive(File src, File destDir) throws IOException {
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        File[] children = src.listFiles();
        if (children != null) {
            for (File child : children) {
                File out = new File(destDir, child.getName());
                if (child.isDirectory()) {
                    copyRecursive(child, out);
                } else {
                    copyFile(child, out);
                }
            }
        }
    }

    /** 单文件字节复制。 */
    private static void copyFile(File src, File dest) throws IOException {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** ancestor 是否为 child 的自身或其祖先（用于阻止把目录移进自己内部）。 */
    private static boolean isWithin(File ancestor, File child) {
        try {
            String a = ancestor.getCanonicalPath();
            String c = child.getCanonicalPath();
            return c.equals(a) || c.startsWith(a + File.separator);
        } catch (IOException e) {
            // 无法解析时保守拒绝移动，避免越界
            return true;
        }
    }

    private static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }
}
