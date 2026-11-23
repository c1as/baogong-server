package com.baogong.server;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 诊断日志：把服务器启动过程、PHP 输出、异常写入一个可分享的文本文件。
 * 文件路径：filesDir/baogong/diagnostic.log
 * 用户可通过菜单「导出诊断日志」分享给开发者排查问题。
 */
public final class DiagLog {

    private static final String TAG = "DiagLog";
    private static final Object LOCK = new Object();

    /** 写入一行日志（同时输出到 logcat 的 DiagLog 标签）。 */
    public static void i(Context c, String line) {
        Log.i(TAG, line);
        write(c, "I", line, null);
    }

    /** 写入异常（含完整堆栈）。 */
    public static void e(Context c, String line, Throwable t) {
        Log.e(TAG, line, t);
        write(c, "E", line, t);
    }

    /** 写入 PHP 进程输出的一行。 */
    public static void php(Context c, String line) {
        Log.d("php", line);
        write(c, "PHP", line, null);
    }

    /** 开始一次诊断会话时清空旧日志并写入头部信息。 */
    public static void header(Context c) {
        synchronized (LOCK) {
            File f = file(c);
            try {
                Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
                try {
                    w.write("===== 报工服务器诊断日志 =====\n");
                    w.write("时间: " + now() + "\n");
                    w.write("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n");
                    w.write("系统版本: Android " + android.os.Build.VERSION.RELEASE
                            + " (API " + android.os.Build.VERSION.SDK_INT + ")\n");
                    w.write("ABI: " + android.os.Build.SUPPORTED_ABIS[0] + "\n");
                    w.write("APK 版本: " + Config.APP_VERSION + "\n");
                    w.write("================================\n");
                } finally {
                    w.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "write header failed", e);
            }
        }
    }

    public static File file(Context c) {
        return new File(Config.root(c), "diagnostic.log");
    }

    private static void write(Context c, String level, String line, Throwable t) {
        synchronized (LOCK) {
            File f = file(c);
            try {
                if (!f.getParentFile().exists()) {
                    f.getParentFile().mkdirs();
                }
                Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
                try {
                    w.write("[" + now() + "][" + level + "] " + line + "\n");
                    if (t != null) {
                        java.io.StringWriter sw = new java.io.StringWriter();
                        t.printStackTrace(new java.io.PrintWriter(sw));
                        w.write(sw.toString() + "\n");
                    }
                } finally {
                    w.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "write log failed", e);
            }
        }
    }

    private static String now() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private DiagLog() {
    }
}
