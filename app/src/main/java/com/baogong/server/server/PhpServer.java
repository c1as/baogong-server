package com.baogong.server.server;

import android.content.Context;
import android.util.Log;

import com.baogong.server.Config;
import com.baogong.server.DiagLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 托管 PHP 内置 Web 服务器（php -S）。
 *
 * Android 没有官方 PHP，运行时采用 Termux 交叉编译的 php 二进制（见 docs 与 tools/fetch_php_runtime.sh）。
 * v1.3 起二进制与全部依赖 .so 以 native 库形式打进 jniLibs，安装到 nativeLibraryDir
 * （该目录可执行；filesDir 被 Android 标记 noexec，不能直接 exec，这正是 v1.2 无法启动的根因）。
 * 所有带版本号的 SONAME 已用「原地二进制替换」规范化（不改 ELF 段结构，避免 patchelf 导致的 SIGSEGV），web 程序放在可写的 filesDir。
 *
 * 热更新原理：php -S 对每个 HTTP 请求都会重新读取并编译脚本文件（不常驻内存），
 * 因此通过系统文件管理器或"导入文件"覆盖 public/api.php 后，下一次请求立即使用新代码——无需重装 APK。
 */
public final class PhpServer {

    private static final String TAG = "PhpServer";

    private final Context context;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shouldRun = new AtomicBoolean(false);
    private Process process;
    private Thread watcher;

    public interface Listener {
        void onState(boolean running, String message);
        void onLog(String line);
    }

    private final Listener listener;

    public PhpServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * 查找 PHP 可执行文件（v1.3）。
     *
     * 二进制随 APK 以 native 库形式下发到 nativeLibraryDir，文件名 libphp_bin.so。
     * 该目录由系统标记为可执行（不同于 filesDir 的 noexec），故能被 forkAndExec 正常加载。
     * 其全部依赖库也在同一目录（已用原地二进制替换规范化 SONAME，去掉带版本号后缀）。
     */
    private File findPhpBinary() {
        File phpBin = new File(Config.nativeLibDir(context), "libphp_bin.so");
        if (phpBin.exists()) {
            DiagLog.i(context, "PHP 二进制: " + phpBin + ", canExecute=" + phpBin.canExecute()
                    + ", length=" + phpBin.length() + ", nativeLibDir=" + Config.nativeLibDir(context));
            return phpBin;
        }
        DiagLog.i(context, "PHP 二进制不存在: " + phpBin + "（jniLibs 未正确打包？）");
        return null;
    }

    /**
     * 生成 php.ini：限制目录、关闭错误显示、延长超时。
     *
     * v1.5 关键修复：php.ini 的任何值都【绝不能包含 nativeLibraryDir 路径】。
     * Android 安装目录形如 /data/app/~~XXXX==/...，其中的 '~~' 会被 PHP 的 ini
     * 解析器当成一元位取反运算符，导致 "syntax error, unexpected '~' on line 1"，
     * 进而使【整个 php.ini 失效】——连关闭 opcache 的指令都读不到，最终触发
     * "Unable to create opcache lock file in /data/data/com.termux/files/usr/tmp"。
     * 因此：去掉 extension_dir 和 open_basedir 里的 libDir，并显式关闭 opcache。
     */
    private File ensurePhpIni(File phpBinary) throws IOException {
        File root = Config.root(context);
        File ini = new File(Config.phpDir(context), "php.ini");
        File tmp = new File(root, "tmp");
        if (!tmp.exists()) {
            tmp.mkdirs();
        }
        // Termux 的 php 主程序已把 pdo_sqlite / sqlite3 / mbstring / curl /
        // openssl 等扩展【静态编译进二进制】（无独立 .so），无需 extension_dir。
        // 动态链接器在 exec 之前加载 .so，不受 open_basedir 约束，故无需把 libDir 写进来。
        String content = ""
                + "display_errors=Off\n"
                + "log_errors=On\n"
                + "error_log=" + new File(Config.phpDir(context), "php-error.log").getAbsolutePath() + "\n"
                + "date.timezone=Asia/Shanghai\n"
                + "default_socket_timeout=30\n"
                + "max_execution_time=120\n"
                + "upload_max_filesize=20M\n"
                + "post_max_size=22M\n"
                // open_basedir 只含 root 与系统临时目录，绝不含 '~~' 的 nativeLibraryDir
                + "open_basedir=" + root.getAbsolutePath() + ":/data/local/tmp\n"
                + "sys_temp_dir=" + tmp.getAbsolutePath() + "\n"
                + "upload_tmp_dir=" + tmp.getAbsolutePath() + "\n"
                // 关闭 opcache：一是避开编译时写死的 /data/data/com.termux/files/usr/tmp 锁文件，
                // 二是 php -S 每次请求重新读脚本，禁用缓存正好保证文件覆盖热更新即时生效。
                + "opcache.enable=0\n"
                + "opcache.enable_cli=0\n";
        try (OutputStream out = new FileOutputStream(ini)) {
            out.write(content.getBytes("UTF-8"));
        }
        return ini;
    }

    public void start() {
        if (running.get()) {
            notifyState(true, "PHP 服务已在运行");
            return;
        }
        File php = findPhpBinary();
        if (php == null) {
            DiagLog.i(context, "找不到 PHP 二进制（nativeLibraryDir 未找到 libphp_bin.so）");
            notifyState(false, "缺少 PHP 运行时，请按文档放置二进制到 jniLibs");
            return;
        }
        DiagLog.i(context, "找到 PHP 二进制: " + php.getAbsolutePath()
                + " exists=" + php.exists() + " canExecute=" + php.canExecute()
                + " length=" + php.length());
        shouldRun.set(true);
        startProcess(php);
    }

    private void startProcess(File php) {
        try {
            File ini = ensurePhpIni(php);
            File webRoot = Config.webRoot(context);
            // 所有依赖库与 php 二进制都在 nativeLibraryDir；linker 会从 LD_LIBRARY_PATH 加载。
            String nativeLibDir = Config.nativeLibDir(context);
            File root = Config.root(context);

            // 确保目录存在
            if (!webRoot.exists()) {
                DiagLog.i(context, "webRoot 不存在，创建: " + webRoot);
                webRoot.mkdirs();
            }

            List<String> cmd = new ArrayList<>();
            // Termux 的 php 是 aarch64 动态链接 ELF，interpreter 指向 Android linker64。
            // 通常可直接 exec；若失败，watcher 会捕获退出码并在日志中记录，便于进一步处理。
            cmd.add(php.getAbsolutePath());
            cmd.add("-S");
            cmd.add("127.0.0.1:" + Config.PHP_PORT);
            cmd.add("-t");
            cmd.add(webRoot.getAbsolutePath());
            cmd.add("-c");
            cmd.add(ini.getAbsolutePath());
            // 双保险：命令行 -d 优先级高于 php.ini，强制关闭 opcache，
            // 彻底避开编译时写死的 /data/data/com.termux/files/usr/tmp 锁文件致命错误。
            cmd.add("-d");
            cmd.add("opcache.enable=0");
            cmd.add("-d");
            cmd.add("opcache.enable_cli=0");

            StringBuilder cs = new StringBuilder();
            for (String s : cmd) {
                cs.append(s).append(" ");
            }
            DiagLog.i(context, "启动命令: " + cs.toString());
            DiagLog.i(context, "LD_LIBRARY_PATH=" + nativeLibDir
                    + " | webRoot=" + webRoot + " (exists=" + webRoot.exists()
                    + ", files=" + (webRoot.list() == null ? 0 : webRoot.list().length) + ")");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(root);
            // nativeLibraryDir 已包含 php 的全部依赖（SONAME 已规范化），
            // 放在最前优先加载；系统库目录作为兜底。
            String libPath = nativeLibDir + ":/system/lib64:/vendor/lib64:/apex/com.android.runtime/lib64/bionic";
            pb.environment().put("LD_LIBRARY_PATH", libPath);
            pb.environment().put("TMPDIR", new File(root, "tmp").getAbsolutePath());
            pb.environment().put("HOME", Config.phpDir(context).getAbsolutePath());

            process = pb.start();
            running.set(true);
            DiagLog.i(context, "PHP 进程已创建, pid 监控启动, PHP 输出如下:");
            notifyState(true, "PHP 服务已启动 :" + Config.PHP_PORT);
            startLogReader(process);
            startWatcher(php);
        } catch (Exception e) {
            DiagLog.e(context, "PHP 启动失败（ProcessBuilder.start 抛异常）", e);
            Log.e(TAG, "start php failed", e);
            notifyState(false, "PHP 启动失败：" + e.getMessage());
        }
    }

    /** 读取 PHP 输出日志，回调给界面展示。 */
    private void startLogReader(final Process p) {
        new Thread(() -> {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    DiagLog.php(context, line);
                    if (listener != null) {
                        listener.onLog(line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "php-log-reader").start();
    }

    /** 监视进程：意外退出时自动拉起（除非主动 stop）。 */
    private void startWatcher(final File php) {
        watcher = new Thread(() -> {
            try {
                process.waitFor();
            } catch (InterruptedException ignored) {
                return;
            }
            boolean wasRunning = running.getAndSet(false);
            try {
                DiagLog.i(context, "PHP 进程退出, exitCode=" + process.exitValue());
            } catch (Exception ignored) {
                DiagLog.i(context, "PHP 进程退出（无法获取退出码）");
            }
            if (wasRunning && shouldRun.get()) {
                notifyState(false, "PHP 异常退出，3 秒后重启…（可导出日志查看原因）");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (shouldRun.get()) {
                    startProcess(php);
                }
            }
        }, "php-watcher");
        watcher.start();
    }

    public void stop() {
        shouldRun.set(false);
        running.set(false);
        if (watcher != null) {
            watcher.interrupt();
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
        notifyState(false, "PHP 服务已停止");
    }

    public boolean isRunning() {
        return running.get();
    }

    private void notifyState(boolean ok, String message) {
        if (listener != null) {
            listener.onState(ok, message);
        }
    }

    /** 供外部重启（例如导入文件后主动重载）。 */
    public void restart() {
        stop();
        start();
    }
}
