package com.baogong.server.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 服务端仓库热更新配置（v2.4 起改为 GitHub）。
 *
 * 设计要点（用户要求）：
 *  - 数据源为 GitHub Releases（原极狐 GitLab 方案已弃用）；
 *  - 令牌【不写死、不打包进 APK】，而是在 App「设置」里运行时填写，
 *    保存在本 App 私有 SharedPreferences（其他 App 读不到）；
 *  - 【令牌可选】：公开仓库匿名即可读取版本列表和下载；
 *    填令牌可提高 GitHub API 限额、或访问私有仓库；
 *  - 令牌泄露或过期时，用户随时进设置改掉即可，无需重装 APK。
 *
 * 可配置项：
 *  - API 地址（默认 https://api.github.com）
 *  - 仓库 owner（默认 c1as）
 *  - 仓库名 repo（默认 baogong-system）
 *  - 令牌（可选，留空即匿名）
 */
public final class UpdateConfig {

    private static final String PREFS = "bg_repo_update";
    private static final String K_API_BASE = "api_base";
    private static final String K_OWNER = "repo_owner";
    private static final String K_REPO = "repo_name";
    private static final String K_TOKEN = "token";
    private static final String K_INSTALLED_WEB = "installed_web_version";
    private static final String K_RELEASE_TAG = "release_tag_cache";

    public static final String DEFAULT_API_BASE = "https://api.github.com";
    public static final String DEFAULT_OWNER = "c1as";
    public static final String DEFAULT_REPO = "baogong-system";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getApiBase(Context c) {
        String v = prefs(c).getString(K_API_BASE, "");
        return TextUtils.isEmpty(v) ? DEFAULT_API_BASE : v;
    }

    public static String getOwner(Context c) {
        String v = prefs(c).getString(K_OWNER, "");
        return TextUtils.isEmpty(v) ? DEFAULT_OWNER : v;
    }

    public static String getRepo(Context c) {
        String v = prefs(c).getString(K_REPO, "");
        return TextUtils.isEmpty(v) ? DEFAULT_REPO : v;
    }

    public static String getToken(Context c) {
        return prefs(c).getString(K_TOKEN, "");
    }

    /**
     * 保存配置。apiBase/owner/repo 留空时用默认值；token 可为空（匿名）。
     */
    public static void save(Context c, String apiBase, String owner, String repo, String token) {
        prefs(c).edit()
                .putString(K_API_BASE, TextUtils.isEmpty(apiBase) ? DEFAULT_API_BASE : apiBase.trim())
                .putString(K_OWNER, TextUtils.isEmpty(owner) ? DEFAULT_OWNER : owner.trim())
                .putString(K_REPO, TextUtils.isEmpty(repo) ? DEFAULT_REPO : repo.trim())
                .putString(K_TOKEN, token == null ? "" : token.trim())
                .apply();
    }

    /** 是否可发起检查更新：仓库 owner 与 repo 非空即可（默认已有值，令牌非必需）。 */
    public static boolean isConfigured(Context c) {
        return !TextUtils.isEmpty(getOwner(c)) && !TextUtils.isEmpty(getRepo(c));
    }

    /** 配置展示用：仓库标识 owner/repo。 */
    public static String getRepoLabel(Context c) {
        return getOwner(c) + "/" + getRepo(c);
    }

    /** 本地已安装的服务端程序版本（如 web-v2.8）。 */
    public static String getInstalledWebVersion(Context c) {
        return prefs(c).getString(K_INSTALLED_WEB, "");
    }

    public static void setInstalledWebVersion(Context c, String version) {
        prefs(c).edit().putString(K_INSTALLED_WEB, version == null ? "" : version).apply();
    }

    /** 缓存最近一次选用的 Release tag，便于下次展示对照。 */
    public static String getCachedReleaseTag(Context c) {
        return prefs(c).getString(K_RELEASE_TAG, "");
    }

    public static void setCachedReleaseTag(Context c, String tag) {
        prefs(c).edit().putString(K_RELEASE_TAG, tag == null ? "" : tag).apply();
    }

    private UpdateConfig() {
    }
}
