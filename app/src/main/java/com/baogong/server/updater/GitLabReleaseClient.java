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
import java.util.Locale;

/**
 * GitLab Releases API 客户端（带私有仓库只读令牌）。
 *
 * 职责：
 *  1. 查最新 web-v* Release → 得到 tag 名与 zip 资产链接；
 *  2. 带令牌下载 zip 到指定文件（支持进度回调）。
 *
 * 安全：令牌只作为请求头发送，绝不写进日志 / 诊断报告 / 异常信息。
 */
public final class GitLabReleaseClient {

    /** 查到的最新版本信息。 */
    public static final class Release {
        public final String tag;        // 如 web-v2.7
        public final String downloadUrl; // zip 的 API 下载直链
        public final long size;          // zip 字节数（可能未知，0）

        Release(String tag, String downloadUrl, long size) {
            this.tag = tag;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    public interface ProgressCallback {
        void onProgress(int percent);
    }

    private static final int TIMEOUT = 20000;

    /** 查询最新 Release（Releases API 按创建时间倒序，取第一个 web-v* 的）。 */
    public static Release fetchLatest(Context c) throws Exception {
        String api = UpdateConfig.getApiBase(c);
        String pid = UpdateConfig.getProjectId(c);
        String token = UpdateConfig.getToken(c);
        String urlStr = api + "/projects/" + pid + "/releases?per_page=20";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestProperty("PRIVATE-TOKEN", token);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("查询更新失败（HTTP " + code + "，请检查令牌/项目ID/网络）");
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject rel = arr.getJSONObject(i);
                String tag = rel.optString("tag_name", "");
                if (!tag.toLowerCase(Locale.US).startsWith("web-v")) {
                    continue; // 只认服务端 web 版本 tag
                }
                String dlUrl = findZipLink(rel);
                if (dlUrl != null) {
                    long size = rel.optJSONObject("assets") != null
                            ? parseAssetSize(rel.optJSONObject("assets"), dlUrl) : 0;
                    return new Release(tag, dlUrl, size);
                }
            }
            throw new Exception("仓库里没有找到 web-v* 版本的更新包");
        } finally {
            conn.disconnect();
        }
    }

    /** 从 Release 的 assets.links 里找 zip 下载直链。 */
    private static String findZipLink(JSONObject rel) throws Exception {
        JSONObject assets = rel.optJSONObject("assets");
        if (assets == null) {
            return null;
        }
        JSONArray links = assets.optJSONArray("links");
        if (links == null) {
            return null;
        }
        for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.getJSONObject(i);
            String name = link.optString("name", "");
            String url = link.optString("url", "");
            String direct = link.optString("direct_asset_url", "");
            String candidate = !direct.isEmpty() ? direct : url;
            if (name.toLowerCase(Locale.US).endsWith(".zip") || candidate.toLowerCase(Locale.US).contains(".zip")) {
                return candidate;
            }
        }
        return null;
    }

    private static long parseAssetSize(JSONObject assets, String url) {
        // Release links 一般不带 size；预留解析，取不到返回 0
        return 0;
    }

    /** 带令牌下载 zip 到 dest，返回实际字节数。 */
    public static long download(Context c, String downloadUrl, File dest, ProgressCallback cb) throws Exception {
        String token = UpdateConfig.getToken(c);
        HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("PRIVATE-TOKEN", token);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("下载更新包失败（HTTP " + code + "，请检查令牌是否有 read_repository 权限）");
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

    private GitLabReleaseClient() {
    }
}
