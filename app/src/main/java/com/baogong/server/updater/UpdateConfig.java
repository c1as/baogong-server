package com.baogong.server.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 服务端仓库热更新配置。
 *
 * 设计要点（用户要求）：令牌【不写死在源码 / 不打包进 APK】，而是在 App「设置」里
 * 运行时填写，保存在本 App 私有 SharedPreferences（其他 App 读不到）。
 * 令牌泄露或过期时，用户随时进设置改掉即可，无需重装 APK。
 *
 * 需要填写的三项：
 *  - API 地址（默认 https://jihulab.com/api/v4）
 *  - 项目 ID（数字，如 389585）
 *  - 只读令牌（只勾 read_repository 的 PAT）
 */
public final class UpdateConfig {

    private static final String PREFS = "bg_repo_update";
    private static final String K_API_BASE = "api_base";
    private static final String K_PROJECT_ID = "project_id";
    private static final String K_TOKEN = "token";
    private static final String K_INSTALLED_WEB = "installed_web_version";
    private static final String K_RELEASE_TAG = "release_tag_cache";

    public static final String DEFAULT_API_BASE = "https://jihulab.com/api/v4";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getApiBase(Context c) {
        String v = prefs(c).getString(K_API_BASE, "");
        return TextUtils.isEmpty(v) ? DEFAULT_API_BASE : v;
    }

    public static String getProjectId(Context c) {
        return prefs(c).getString(K_PROJECT_ID, "");
    }

    public static String getToken(Context c) {
        return prefs(c).getString(K_TOKEN, "");
    }

    public static void save(Context c, String apiBase, String projectId, String token) {
        prefs(c).edit()
                .putString(K_API_BASE, TextUtils.isEmpty(apiBase) ? DEFAULT_API_BASE : apiBase.trim())
                .putString(K_PROJECT_ID, projectId == null ? "" : projectId.trim())
                .putString(K_TOKEN, token == null ? "" : token.trim())
                .apply();
    }

    /** 三项是否都已配置（能发起检查更新的前提）。 */
    public static boolean isConfigured(Context c) {
        return !TextUtils.isEmpty(getProjectId(c)) && !TextUtils.isEmpty(getToken(c));
    }

    /** 本地已安装的服务端程序版本（如 web-v2.7）。 */
    public static String getInstalledWebVersion(Context c) {
        return prefs(c).getString(K_INSTALLED_WEB, "");
    }

    public static void setInstalledWebVersion(Context c, String version) {
        prefs(c).edit().putString(K_INSTALLED_WEB, version == null ? "" : version).apply();
    }

    /** 缓存最近一次查到的 Release tag，便于下次比对。 */
    public static String getCachedReleaseTag(Context c) {
        return prefs(c).getString(K_RELEASE_TAG, "");
    }

    public static void setCachedReleaseTag(Context c, String tag) {
        prefs(c).edit().putString(K_RELEASE_TAG, tag == null ? "" : tag).apply();
    }

    private UpdateConfig() {
    }
}
