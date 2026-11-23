package com.baogong.server.updater;

import android.content.Context;

import com.baogong.server.Config;
import com.baogong.server.installer.HotUpdateImporter;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 仓库热更新执行器：下载 zip → 校验 → 暂存解压 → 替换 public/ → 失败回滚。
 *
 * 安全约束：
 *  - 只操作 public/，绝不碰 data/（用户的数据库、图纸、配置）；
 *  - 先解压到 public_new/，全部成功才替换；任一步失败保留原 public/；
 *  - 替换前把原 public/ 改名为 public_old/ 作为回滚备份，确认成功后才删除。
 */
public final class RepoUpdater {

    public static final class UpdateResult {
        public final boolean ok;
        public final String message;
        public final String version;

        UpdateResult(boolean ok, String message, String version) {
            this.ok = ok;
            this.message = message;
            this.version = version;
        }
    }

    /**
     * 执行更新。caller 负责在后台线程调用此方法。
     *
     * @param release 查到的最新版本
     */
    public static UpdateResult apply(Context c, GitLabReleaseClient.Release release,
                                     GitLabReleaseClient.ProgressCallback cb) {
        File root = Config.root(c);
        File webRoot = new File(root, "public");
        File tmpDir = new File(root, "tmp");
        File staging = new File(root, "public_new");
        File backup = new File(root, "public_old");

        File zipFile = new File(tmpDir, "repo-update.zip");
        try {
            if (!tmpDir.exists()) {
                tmpDir.mkdirs();
            }
            // 1. 下载
            long size = GitLabReleaseClient.download(c, release.downloadUrl, zipFile, cb);
            if (size < 100) {
                return fail("下载文件过小，疑似损坏（" + size + " 字节）");
            }
            // 2. 校验：必须是合法 zip 且含 api.php（防止下载到错误内容/HTML 错误页）
            if (!isWebZip(zipFile)) {
                return fail("更新包校验失败（不是有效的服务端程序包）");
            }
            // 3. 清理残留暂存/备份
            deleteRecursively(staging);
            deleteRecursively(backup);
            // 4. 解压到暂存目录
            HotUpdateImporter.Result unr = HotUpdateImporter.importZipFile(zipFile, staging);
            if (!unr.ok) {
                deleteRecursively(staging);
                return fail("解压失败：" + unr.message);
            }
            // 5. 替换：public → public_old，public_new → public
            if (webRoot.exists() && !webRoot.renameTo(backup)) {
                deleteRecursively(staging);
                return fail("无法备份当前程序（可能被占用），已保留原版本");
            }
            if (!staging.renameTo(webRoot)) {
                // 回滚：把备份改回来
                if (backup.exists()) {
                    backup.renameTo(webRoot);
                }
                return fail("替换程序失败，已回滚到原版本");
            }
            // 6. 成功：写回版本，清理备份与临时包
            UpdateConfig.setInstalledWebVersion(c, release.tag);
            UpdateConfig.setCachedReleaseTag(c, release.tag);
            deleteRecursively(backup);
            if (zipFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                zipFile.delete();
            }
            return new UpdateResult(true, "更新成功：" + release.tag + "（" + unr.fileCount + " 个文件）", release.tag);
        } catch (Exception e) {
            // 异常时尽力回滚
            try {
                if (!webRoot.exists() && backup.exists()) {
                    backup.renameTo(webRoot);
                }
            } catch (Exception ignored) {
            }
            deleteRecursively(staging);
            return fail("更新异常：" + e.getMessage() + "，已保留原版本");
        }
    }

    private static UpdateResult fail(String msg) {
        return new UpdateResult(false, msg, null);
    }

    /** 校验 zip 是否含有 api.php（服务端程序包标志），同时确认 zip 结构合法。 */
    private static boolean isWebZip(File zipFile) {
        try (ZipFile zf = new ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            boolean hasPhp = false;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                String lower = n.toLowerCase(java.util.Locale.US);
                if (lower.equals("api.php") || lower.endsWith("/api.php")) {
                    hasPhp = true;
                    break;
                }
            }
            return hasPhp;
        } catch (IOException e) {
            return false;
        }
    }

    /** 递归删除文件或目录。 */
    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private RepoUpdater() {
    }
}
