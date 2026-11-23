package com.baogong.server;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.baogong.server.server.NetUtils;
import com.baogong.server.server.PhpServer;

/** 前台服务：托管 PHP 内置服务器，保证长时间运行。 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";

    public static final String ACTION_START = "com.baogong.server.START";
    public static final String ACTION_STOP = "com.baogong.server.STOP";
    /** 主动重启 PHP（一般无需调用，php -S 每次请求自动重新读取脚本）。 */
    public static final String ACTION_RESTART_PHP = "com.baogong.server.RESTART_PHP";

    public interface StateListener {
        void onState(String php, String webdav, String ip);
    }
    // 注意：为兼容现有回调签名保留 webdav 参数，v2.0 起固定传空字符串（WebDAV 已移除）。

    private static StateListener listener;

    public static void setListener(StateListener l) {
        listener = l;
    }

    private PhpServer phpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        phpServer = new PhpServer(this, new PhpServer.Listener() {
            @Override
            public void onState(boolean running, String message) {
                Log.i(TAG, "[PHP] " + message);
                pushNotification();
                pushState();
            }

            @Override
            public void onLog(String line) {
                Log.d("php", line);
            }
        });
        // 放到前台，保证服务器常驻。个别定制 ROM 上可能因权限识别差异抛异常，
        // 这里兜底避免整个 App 闪退（根本修复见 Manifest 中的类型权限声明）。
        try {
            startForeground(1, buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBoth();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART_PHP.equals(action)) {
            if (phpServer != null) {
                phpServer.restart();
            }
            return START_STICKY;
        }
        startBoth();
        return START_STICKY;
    }

    private void startBoth() {
        if (phpServer != null) {
            phpServer.start();
        }
        pushNotification();
        pushState();
    }

    private void stopBoth() {
        if (phpServer != null) {
            phpServer.stop();
        }
        pushState();
    }

    private String phpStatus() {
        if (phpServer == null) {
            return "未启动";
        }
        return phpServer.isRunning() ? "运行中 · " + Config.LOCAL_URL : "未运行";
    }

    private void pushState() {
        if (listener != null) {
            listener.onState(phpStatus(), "", NetUtils.getLocalIp(this));
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String ip = NetUtils.getLocalIp(this);
        String text = "PHP " + (phpServer != null && phpServer.isRunning() ? "●" : "○")
                + (ip == null ? "" : "  ·  " + ip);
        return new NotificationCompat.Builder(this, ServerApp.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("报工服务器运行中")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void pushNotification() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(1, buildNotification());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopBoth();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        Intent i = new Intent(context, ServerService.class);
        i.setAction(ACTION_START);
        context.startForegroundService(i);
    }

    public static void stop(Context context) {
        Intent i = new Intent(context, ServerService.class);
        i.setAction(ACTION_STOP);
        context.startService(i);
    }
}
