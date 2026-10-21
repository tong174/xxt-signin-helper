package com.xxt.qd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.WebView;

/**
 * MonitorService —— 前台服务，让「自动检测签到」在 App 切后台/息屏后仍然运行。
 *
 * 原理：H5 的 setInterval 在后台会被系统节流甚至冻结，
 *      改由原生 Handler 定时调用 WebView 的 monTick()，
 *      配合前台通知把进程优先级提到最高，避免被 LMK 回收。
 */
public class MonitorService extends Service {

    public static final String CH_ID = "xxt_monitor";
    public static final int NID = 1001;

    private Handler h;
    private int intervalSec = 60;
    private int ticks = 0;
    private String lastInfo = "";
    private static MonitorService inst = null;
    private android.os.PowerManager.WakeLock wl = null;

    public static boolean isRunning() { return inst != null; }

    public static void setLastInfo(String s) {
        if (inst != null && s != null) {
            inst.lastInfo = s;
            inst.updateNoti();
        }
    }

    /* ---------- 静态嵌套类：d8 处理匿名内部类会 NPE ---------- */
    static class Tick implements Runnable {
        private final MonitorService s;
        Tick(MonitorService s) { this.s = s; }
        @Override public void run() {
            s.ticks++;
            s.holdWl();
            s.runJs();
            s.updateNoti();
            if (s.h != null) s.h.postDelayed(this, Math.max(15, s.intervalSec) * 1000L);
        }
    }

    static class JsRunner implements Runnable {
        private final WebView w;
        private final String js;
        JsRunner(WebView w, String js) { this.w = w; this.js = js; }
        @Override public void run() {
            try { w.evaluateJavascript(js, null); } catch (Exception ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        h = new Handler(Looper.getMainLooper());
        makeChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int s = intent.getIntExtra("sec", 0);
            if (s > 0) intervalSec = s;
        }
        startForeground(NID, buildNoti());
        if (h != null) {
            h.removeCallbacksAndMessages(null);
            h.post(new Tick(this));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        inst = null;
        if (h != null) h.removeCallbacksAndMessages(null);
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    /* 每轮检测持锁 2 分钟：息屏时 CPU 不休眠，WebView 的 JS 才能跑完 */
    private void holdWl() {
        try {
            if (wl == null) {
                android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "xxt:monitor");
                wl.setReferenceCounted(false);
            }
            wl.acquire(120000);
        } catch (Exception ignored) {}
    }

    /* ---------- 通知 ---------- */
    private void makeChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "签到监听", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台自动检测学习通签到");
            ch.enableLights(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNoti() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlag);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        String txt = "已检测 " + ticks + " 次 · 每 " + intervalSec + " 秒";
        if (lastInfo != null && !lastInfo.isEmpty()) txt = txt + " · " + lastInfo;
        b.setContentTitle("学习通助手 · 监听中")
         .setContentText(txt)
         .setSmallIcon(android.R.drawable.stat_notify_sync)
         .setContentIntent(pi)
         .setOngoing(true)
         .setOnlyAlertOnce(true);
        return b.build();
    }

    private void updateNoti() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NID, buildNoti());
        } catch (Exception ignored) {}
    }

    /* ---------- 唤起 WebView 里的 monTick ---------- */
    private void runJs() {
        WebView w = MainActivity.getWeb();
        if (w == null) {
            lastInfo = "页面未就绪";
            return;
        }
        w.post(new JsRunner(w, "(function(){try{"
                + "if(typeof monTick==='function'){monTick();}"
                + "if(window.__xxtMonInfo){try{window.__xxtMonInfo();}catch(e){}}"
                + "}catch(e){}})()"));
    }
}
