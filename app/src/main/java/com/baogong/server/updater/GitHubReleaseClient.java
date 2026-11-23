package com.baogong.server.updater;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitHub Releases API 客户端（令牌可选，公开仓库可匿名读取）。
 *
 * 职责：
 *  1. 列出仓库里所有【带 zip 资产】的 Release（用于版本选择，可回退旧版）；
 *  2. 带可选令牌下载选中的 zip 到指定文件（支持进度回调）。
 *
 * 安全：令牌只作为请求头发送，绝不写进日志 / 诊断报告 / 异常信息。
 *
 * 与旧 GitLabReleaseClient 的区别：
 *  - 数据源从极狐 GitLab 改为 GitHub；
 *  - fetchReleases() 返回列表而非单个最新版本；
 *  - 令牌可选（公开仓库匿名可读；带令牌可提高 API 限额、访问私有仓库）。
 */
public final class GitHubReleaseClient {

    /** 一个可下载的版本。 */
    public static final class Release {
        public final String tag;          // 如 web-v2.8
        public final String name;         // Release 标题（可选）
        public final String downloadUrl;  // zip 资产下载直链
        public final String fileName;     // 资产文件名，如 baogong-web-web-v2.8.zip
        public final long size;           // 资产字节数（未知为 0）
        public final boolean latest;      // 是否为最新版本（列表中第一个）

        Release(String tag, String name, String downloadUrl, String fileName,
                long size, boolean latest) {
            this.tag = tag;
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.fileName = fileName;
            this.size = size;
            this.latest = latest;
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    private static final int TIMEOUT = 20000;

    /**
     * 列出所有带 zip 的 Release，按 API 返回顺序（GitHub 默认按创建时间倒序，最新在前）。
     */
    public static List<Release> fetchReleases(Context c) throws Exception {
        String owner = UpdateConfig.getOwner(c);
        String repo = UpdateConfig.getRepo(c);
        if (owner.isEmpty() || repo.isEmpty()) {
            throw new Exception("请先填写 GitHub 仓库（格式 owner/仓库名，如 c1as/baogong-system）");
        }
        String api = UpdateConfig.getApiBase(c);
        String urlStr = api + "/repos/" + owner + "/" + repo + "/releases?per_page=100";

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            String token = UpdateConfig.getToken(c);
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "baogong-server-updater");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("查询版本列表失败（HTTP " + code
                        + "）。公开仓库无需令牌；若是私有仓库请确认令牌有效。");
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            List<Release> out = new ArrayList<>();
            boolean first = true;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.getJSONObject(i);
                String tag = rel.optString("tag_name", "");
                // 只认 web-v* 前缀的服务端程序包，排除 v* 的 App 源码包/其它 Release，
                // 避免误选完整源码包导致 public/ 结构被破坏。
                if (tag.isEmpty() || !tag.toLowerCase(Locale.US).startsWith("web-v")) {
                    continue;
                }
                String name = rel.optString("name", tag);
                Release r = buildRelease(tag, name, rel, first);
                if (r != null) {
                    out.add(r);
                    first = false;
                }
            }
            if (out.isEmpty()) {
                throw new Exception("仓库里没有找到带 zip 更新包的版本。"
                        + "\n请先在 GitHub 发布一个 web-v* 的 Release，并上传 public 打包的 zip。");
            }
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** 从 Release 的 assets 里挑第一个 zip 资产，构建 Release。 */
    private static Release buildRelease(String tag, String name, JSONObject rel, boolean latest) {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String fileName = a.optString("name", "");
            String url = a.optString("browser_download_url", "");
            if (url.isEmpty()) {
                url = a.optString("url", "");
            }
            if (!fileName.toLowerCase(Locale.US).endsWith(".zip")
                    && !url.toLowerCase(Locale.US).contains(".zip")) {
                continue;
            }
            long size = a.optLong("size", 0);
            return new Release(tag, name, url, fileName, size, latest);
        }
        return null;
    }

    /** 带可选令牌下载 zip 到 dest，返回实际字节数。 */
    public static long download(Context c, String downloadUrl, File dest, ProgressCallback cb)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(60000);
            // GitHub 资产下载需声明 octet-stream 才会返回真实文件而非 API JSON
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setRequestProperty("User-Agent", "baogong-server-updater");
            String token = UpdateConfig.getToken(c);
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("下载更新包失败（HTTP " + code + "，请检查网络与令牌）");
            }
            long total = conn.getContentLengthLong();
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            long downloaded = 0;
            byte[] buf = new byte[16384];
            int lastPercent = -1;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (cb != null && total > 0) {
                        int pct = (int) (downloaded * 100 / total);
                        if (pct != lastPercent) {
                            cb.onProgress(pct);
                            lastPercent = pct;
                        }
                    }
                }
            }
            return downloaded;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private GitHubReleaseClient() {
    }
}
