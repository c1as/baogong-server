package com.baogong.server;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/** 应用入口：创建前台服务通知渠道。 */
public class ServerApp extends Application {

    public static final String CHANNEL_ID = "baogong_server";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "报工服务器",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("本机 PHP + SQLite 服务运行状态");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
