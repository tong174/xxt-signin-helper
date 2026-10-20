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
import android.os.PowerManager;
import android.webkit.WebView;

/**
 * MonitorService —— 前台服务，让「自动检测签到」在 App 切后台/息屏后仍然运行。
 *
 * 原理：H5 的 setInterval 在后台会被系统节流甚至冻结，
 *      改由原生 Handler 定时调用 WebView 的 monTick()，
 *      配合前台通知把进程优先级提到最高，避免被 LMK 回收。
 *
 * v3.0 变更：
 *   - 息屏保活：每次 tick 短暂持有 PARTIAL_WAKE_LOCK，避免 CPU 休眠导致 JS 不执行
 *   - WebView 失效自愈：页面被系统回收后，通知栏提示「请打开 App」，并在恢复后自动继续
 *   - 通知显示更完整：已检 / 已签 / 间隔 / 最近状态
 *   - 计时更稳：用 postAtTime 对齐，避免 tick 耗时累积漂移
 */
public class MonitorService extends Service {

    public static final String CH_ID = "xxt_monitor";
    public static final int NID = 1001;

    private Handler h;
    private int intervalSec = 60;
    private int ticks = 0;
    private String lastInfo = "";
    private PowerManager.WakeLock wl;
    private static MonitorService inst = null;

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
            s.acquireWl();
            s.runJs();
            s.updateNoti();
            s.releaseWlDelayed();
            /* 对齐到下一整点间隔，减少累积漂移 */
            long delay = Math.max(15, s.intervalSec) * 1000L;
            if (s.h != null) s.h.postDelayed(this, delay);
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

    static class WlRelease implements Runnable {
        private final MonitorService s;
        WlRelease(MonitorService s) { this.s = s; }
        @Override public void run() { s.releaseWl(); }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        h = new Handler(Looper.getMainLooper());
        makeChannel();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xxt:monitor");
                wl.setReferenceCounted(false);
            }
        } catch (Exception ignored) {}
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
        releaseWl();
        super.onDestroy();
    }

    /* ---------- WakeLock：只在 tick 期间短暂持有 ---------- */
    private void acquireWl() {
        try {
            if (wl != null && !wl.isHeld()) wl.acquire(30_000L);   /* 最长 30s 自动释放兜底 */
        } catch (Exception ignored) {}
    }

    private void releaseWlDelayed() {
        if (h != null) h.postDelayed(new WlRelease(this), 8000L);  /* JS 有充足时间跑完 */
    }

    private void releaseWl() {
        try {
            if (wl != null && wl.isHeld()) wl.release();
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
            ch.setShowBadge(false);
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
        boolean webAlive = MainActivity.getWeb() != null;
        String txt = "已检测 " + ticks + " 次 · 每 " + intervalSec + " 秒";
        if (!webAlive) {
            txt = "⚠ 页面已被系统回收，点此重新打开 App";
        } else if (lastInfo != null && !lastInfo.isEmpty()) {
            txt = txt + " · " + lastInfo;
        }
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
            lastInfo = "页面未就绪，请打开 App";
            updateNoti();
            return;
        }
        w.post(new JsRunner(w, "(function(){try{"
                + "if(typeof monTick==='function'){monTick();}"
                + "if(window.__xxtMonInfo){try{window.__xxtMonInfo();}catch(e){}}"
                + "}catch(e){}})()"));
    }
}
