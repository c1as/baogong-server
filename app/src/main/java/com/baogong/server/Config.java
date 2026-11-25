package com.baogong.server;

import android.content.Context;

import java.io.File;

/**
 * 全局路径与端口配置。
 *
 * 关键设计：所有"可被热更新"的程序文件都放在【可写目录】 filesDir/baogong 下，
 * 而不是 assets（assets 只读、随 APK 固化）。该目录通过 DocumentsProvider 挂载到系统"文件"App，
 * 覆盖其中的 api.php / app.js 即可实现免重装 APK 的服务端热更新。
 */
public final class Config {

    /** 服务器根：$BG_ROOT，对应 api.php 里的 dirname(__DIR__)。 */
    public static File root(Context c) {
        return new File(c.getFilesDir(), "baogong");
    }

    /** Web 根目录，php -S -t 指向这里。 */
    public static File webRoot(Context c) {
        return new File(root(c), "public");
    }

    /** 数据目录，api.php 默认 $BG_ROOT/data。 */
    public static File dataDir(Context c) {
        return new File(root(c), "data");
    }

    /** PHP 配置/日志目录（php.ini、php-error.log）。运行时二进制不在此处，见 nativeLibDir。 */
    public static File phpDir(Context c) {
        return new File(root(c), "php");
    }

    /**
     * PHP 二进制所在目录 = 系统 nativeLibraryDir。
     *
     * 关键（v1.3 修复闪退/无法启动的根因）：
     * Android 10+ 禁止在 App 私有数据目录(filesDir)里 exec 可执行文件（noexec）。
     * v1.2 把 php 放在 filesDir/baogong/php/bin/php，虽然 chmod +x 成功、
     * canExecute=true，但 forkAndExec 仍报 error=13 Permission denied。
     * nativeLibraryDir（系统安装 .so 的目录）是可执行的，故 v1.3 把 php 及依赖
     * 全部作为 native 库打进 jniLibs，安装后即位于此目录，可被正常 exec。
     */
    public static String nativeLibDir(Context c) {
        return c.getApplicationInfo().nativeLibraryDir;
    }

    /** 标记文件：记录已安装的程序版本，用于判断首次启动/升级。 */
    public static File installedMarker(Context c) {
        return new File(root(c), ".installed-version");
    }

    /** PHP 内置服务器监听端口（仅本机 WebView 访问）。 */
    public static final int PHP_PORT = 8080;

    /** 本机访问地址。 */
    public static final String LOCAL_URL = "http://127.0.0.1:" + PHP_PORT + "/";

    /** 当前内置的 web 程序版本（与首次安装标记比对，变更时覆盖 public/）。 */
    public static final String BUNDLED_WEB_VERSION = "v2.5";

    /** APK 版本号（用于诊断日志）。 */
    public static final String APP_VERSION = "2.6";

    private Config() {
    }
}
