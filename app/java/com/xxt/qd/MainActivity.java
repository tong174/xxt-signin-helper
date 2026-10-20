package com.xxt.qd;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 学习通签到助手 —— 原生外壳 v3.0
 *
 * 核心机制：
 *   H5 里所有网络请求都不直接发，而是拼成
 *       https://xxt.local/r?d=<base64(JSON)>
 *   交给 WebViewClient.shouldInterceptRequest 在原生层执行，
 *   原生层负责 Cookie 持久化 + 真实 HTTP，再把结果原样返回。
 *   => 完全绕开浏览器 CORS，且每个请求自动带上学习通会话 Cookie。
 *
 * payload JSON: { m:"POST", u:"https://...", b:"a=1&b=2", x:1 }
 *   m=method  u=url  b=body  x=0 json / 1 form / 2 multipart
 *   h=自定义请求头  f=multipart 字段  file=multipart 文件
 *
 * v3.0 变更：
 *   - 修复：异常消息为 null 时错误分支自身 NPE（safeMsg 兜底）
 *   - 修复：分享链接冷启动竞态 —— onPageFinished 后投递 + 轮询兜底，替代固定 900ms
 *   - 新增：xxt.local/clearcookies —— 登录/切换账号前清罐，杜绝跨账号 Cookie 串号
 *   - 新增：xxt.local/version —— 页面可显示原生版本
 *   - 新增：网络抖动自动重试 1 次（仅 IOException 类，重试间隔 350ms）
 *   - 新增：Set-Cookie 过期/删除语义（Max-Age<=0 或 1970 过期时移除罐内对应项）
 *   - 加固：关闭 file:// 与 content:// 访问、目录穿越防护、正确 reason phrase、连接显式 disconnect
 */
public class MainActivity extends Activity {

    public static final String VERSION = "3.0";

    /* 供 MonitorService 唤起页面里的 monTick() */
    private static java.lang.ref.WeakReference<WebView> WEB_REF = new java.lang.ref.WeakReference<WebView>(null);
    public static WebView getWeb() { return WEB_REF == null ? null : WEB_REF.get(); }

    private WebView web;
    private SharedPreferences sp;
    private final Map<String, String> cookieJar = new HashMap<>();
    /* 页面加载完成前收到的分享链接，先排队 */
    volatile String pendingShare = null;
    volatile boolean pageReady = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("xxt", MODE_PRIVATE);
        loadCookies();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        /* v3.0：页面全部经 https://app.local/ 拦截器提供，不再需要 file:// 权限 */
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);

        web.setWebViewClient(new ProxyClient(this));
        WEB_REF = new java.lang.ref.WeakReference<WebView>(web);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1002);
                }
            } catch (Exception ignored) {}
        }
        web.setWebChromeClient(new CamClient(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.CAMERA)
                   != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1001);
        }

        web.loadUrl("https://app.local/index.html");
        setContentView(web);
        deliverShare(getIntent());
    }

    /* ---------------- Cookie 持久化 ---------------- */
    private void loadCookies() {
        String all = sp.getString("cookies", "");
        if (all.isEmpty()) return;
        for (String line : all.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) cookieJar.put(line.substring(0, i), line.substring(i + 1));
        }
    }

    private void saveCookies() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieJar.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        sp.edit().putString("cookies", sb.toString()).apply();
    }

    private void absorbCookies(HttpURLConnection c) {
        Map<String, List<String>> h = c.getHeaderFields();
        if (h == null) return;
        List<String> set = h.get("Set-Cookie");
        if (set == null) set = h.get("set-cookie");
        if (set == null) return;
        boolean dirty = false;
        for (String raw : set) {
            if (raw == null) continue;
            String lower = raw.toLowerCase(Locale.ROOT);
            String kv = raw.split(";")[0];
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            String k = kv.substring(0, i).trim();
            String v = kv.substring(i + 1).trim();
            /* v3.0：识别删除语义（服务端登出/换号时会发） */
            boolean expired = lower.contains("max-age=0")
                    || lower.contains("max-age=-1")
                    || lower.contains("expires=thu, 01 jan 1970")
                    || lower.contains("expires=thu, 01-jan-1970");
            if (expired || v.isEmpty()) {
                if (cookieJar.remove(k) != null) dirty = true;
            } else if (!v.equals(cookieJar.get(k))) {
                cookieJar.put(k, v);
                dirty = true;
            }
        }
        if (dirty) saveCookies();
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookieJar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /* ---------------- 代理执行 ---------------- */
    private WebResourceResponse handleProxy(String url) {
        try {
            String q = url.indexOf('?') >= 0 ? url.substring(url.indexOf('?') + 1) : "";
            String d = null;
            for (String kv : q.split("&")) {
                if (kv.startsWith("d=")) { d = kv.substring(2); break; }
            }
            if (d == null) return corsJson("{\"__err\":\"no payload\"}", 400, "Bad Request");

            String json = new String(Base64.decode(URLDecoder.decode(d, "UTF-8"), Base64.DEFAULT), "UTF-8");
            JSONObject p = new JSONObject(json);
            String method = p.optString("m", "GET");
            String target = p.optString("u", "");
            String body = p.optString("b", "");
            int mode = p.optInt("x", 0);          // 0=json 1=form 2=multipart
            boolean form = mode == 1;
            JSONObject hdrs = p.optJSONObject("h");   // 自定义请求头

            byte[] outBody = null;                 // 预组装的请求体（重试时复用）
            String contentType = null;

            if (mode == 2 && !"GET".equalsIgnoreCase(method)) {
                /* ---- multipart/form-data：用于上传人脸照片 ---- */
                String bd = "----XBYBOUNDARY" + System.currentTimeMillis();
                contentType = "multipart/form-data; boundary=" + bd;
                ByteArrayOutputStream mb = new ByteArrayOutputStream();
                JSONObject flds = p.optJSONObject("f");
                if (flds != null) {
                    java.util.Iterator<String> it2 = flds.keys();
                    while (it2.hasNext()) {
                        String fk = it2.next();
                        mb.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\""
                                + fk + "\"\r\n\r\n" + flds.optString(fk) + "\r\n").getBytes("UTF-8"));
                    }
                }
                JSONObject fl = p.optJSONObject("file");
                if (fl != null) {
                    String fn = fl.optString("name", "file");
                    String fam = fl.optString("filename", "upload.jpg");
                    String fmime = fl.optString("mime", "image/jpeg");
                    byte[] fb = Base64.decode(fl.optString("b64", ""), Base64.DEFAULT);
                    mb.write(("--" + bd + "\r\nContent-Disposition: form-data; name=\"" + fn
                            + "\"; filename=\"" + fam + "\"\r\nContent-Type: " + fmime
                            + "\r\n\r\n").getBytes("UTF-8"));
                    mb.write(fb);
                    mb.write("\r\n".getBytes("UTF-8"));
                }
                mb.write(("--" + bd + "--\r\n").getBytes("UTF-8"));
                outBody = mb.toByteArray();
            } else if (!body.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                contentType = form ? "application/x-www-form-urlencoded; charset=UTF-8"
                                   : "application/json; charset=UTF-8";
                outBody = body.getBytes("UTF-8");
            }

            /* v3.0：网络抖动（连接/读超时、连接重置）自动重试 1 次 */
            IOException lastIo = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return doHttp(method, target, contentType, outBody, hdrs);
                } catch (IOException io) {
                    lastIo = io;
                    android.util.Log.w("xxt", "proxy attempt " + attempt + " failed: " + io);
                    if (attempt == 0) {
                        try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                    }
                }
            }
            return corsJson("{\"__err\":\"" + safeMsg(lastIo) + "\",\"__retry\":1}", 502, "Bad Gateway");

        } catch (Exception e) {
            return corsJson("{\"__err\":\"" + safeMsg(e) + "\"}", 500, "Internal Error");
        }
    }

    private WebResourceResponse doHttp(String method, String target, String contentType,
                                       byte[] outBody, JSONObject hdrs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            String ck = cookieHeader();
            if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
            if (hdrs != null) {
                java.util.Iterator<String> hit = hdrs.keys();
                while (hit.hasNext()) {
                    String hk = hit.next();
                    try { c.setRequestProperty(hk, hdrs.optString(hk)); } catch (Exception ignored) {}
                }
            }
            if (outBody != null) {
                c.setDoOutput(true);
                if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                OutputStream os = c.getOutputStream();
                os.write(outBody);
                os.flush();
                os.close();
            }

            int code = c.getResponseCode();
            absorbCookies(c);

            InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (is != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                is.close();
            }
            String text = new String(bo.toByteArray(), "UTF-8");
            return corsJson(text, code, code == 200 ? "OK" : ("HTTP " + code));
        } finally {
            try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /* 异常消息可能为 null（如某些 NPE），统一兜底防止二次异常 */
    private static String safeMsg(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.replace("\"", "'").replace("\n", " ");
    }

    private WebResourceResponse corsJson(String text, int code, String reason) {
        WebResourceResponse r = new WebResourceResponse(
                "application/json", "UTF-8", new ByteArrayInputStream(text.getBytes()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                r.setStatusCodeAndReasonPhrase(code, reason);
            } catch (Exception ignored) {
                /* reason phrase 非法时退回默认 */
            }
        }
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        r.setResponseHeaders(h);
        return r;
    }

    /* 把 Cookie 罐暴露给页面（页面需要 _uid / fid 才能拼签到 URL） */
    WebResourceResponse cookieJson() {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : cookieJar.entrySet()) {
                o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        } catch (Exception ignored) {}
        return corsJson(o.toString(), 200, "OK");
    }

    /* v3.0：清空 Cookie 罐 —— 登录/切换账号前调用，杜绝串号 */
    WebResourceResponse clearCookies() {
        int n;
        synchronized (cookieJar) {
            n = cookieJar.size();
            cookieJar.clear();
        }
        sp.edit().remove("cookies").apply();
        /* 同步清 WebView 自身的 Cookie 存储 */
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.CookieManager.getInstance().flush();
        } catch (Exception ignored) {}
        return corsJson("{\"ok\":true,\"cleared\":" + n + "}", 200, "OK");
    }

    /* 用 https://app.local/ 提供 assets —— 让页面处于安全上下文，摄像头才可用 */
    WebResourceResponse serveAsset(String url) {
        String path = url.substring("https://app.local/".length());
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.isEmpty()) path = "index.html";
        if (path.contains("..")) return null;    /* 目录穿越防护 */
        try {
            InputStream is = getAssets().open(path);
            String mime = "application/octet-stream";
            if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".js")) mime = "application/javascript";
            else if (path.endsWith(".css")) mime = "text/css";
            else if (path.endsWith(".json")) mime = "application/json";
            else if (path.endsWith(".png")) mime = "image/png";
            else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) mime = "image/jpeg";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", is);
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /* 处理 xxtqd:// 分享链接 */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        deliverShare(intent);
    }

    /**
     * v3.0：页面就绪则立即投递；未就绪先入队，onPageFinished 再投。
     * 另有 FallbackFlush 每 500ms 探测（最多 20 次），防 onPageFinished 漏报。
     */
    void deliverShare(android.content.Intent intent) {
        if (intent == null || intent.getData() == null) return;
        pendingShare = intent.getData().toString();
        if (pageReady) {
            flushShare();
        } else if (web != null) {
            web.postDelayed(new FallbackFlush(this), 500);
        }
    }

    void onPageLoaded() {
        pageReady = true;
        flushShare();
        injectNightMode();
    }

    /* v3.0：把系统深色模式状态告诉页面（页面主题 auto 档跟随） */
    private void injectNightMode() {
        if (web == null) return;
        try {
            int night = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            boolean dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            web.evaluateJavascript(
                    "window.__xxtNight&&window.__xxtNight(" + (dark ? 1 : 0) + ")", null);
        } catch (Exception ignored) {}
    }

    synchronized void flushShare() {
        String uri = pendingShare;
        if (uri == null || web == null) return;
        pendingShare = null;
        web.post(new ShareDeliver(web, uri));
    }

    /* 摄像头授权：WebView 里 getVideoTracks 必须由 onPermissionRequest 放行 */
    static class CamClient extends WebChromeClient {
        private final MainActivity act;
        CamClient(MainActivity a) { this.act = a; }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            act.runOnUiThread(new PermGrant(request));
        }
    }

    static class PermGrant implements Runnable {
        private final PermissionRequest req;
        PermGrant(PermissionRequest r) { this.req = r; }
        @Override public void run() {
            try { req.grant(req.getResources()); } catch (Exception ignored) {}
        }
    }

    /* ---------- 前台服务控制 ---------- */
    void startMonitor(int sec) {
        try {
            android.content.Intent it = new android.content.Intent(this, MonitorService.class);
            it.putExtra("sec", sec);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it);
            } else {
                startService(it);
            }
            android.util.Log.i("xxt", "MonitorService started " + sec + "s");
        } catch (Exception e) {
            android.util.Log.e("xxt", "start service failed: " + e.getMessage());
        }
    }

    void stopMonitor() {
        try {
            stopService(new android.content.Intent(this, MonitorService.class));
        } catch (Exception ignored) {}
    }

    WebResourceResponse okJson(String msg) {
        return corsJson("{\"ok\":true,\"msg\":\"" + msg.replace("\"", "'") + "\"}", 200, "OK");
    }

    /* 静态嵌套类：d8 处理匿名内部类会 NPE，一律用命名类 */
    static class ShareDeliver implements Runnable {
        private final WebView w;
        private final String uri;
        ShareDeliver(WebView v, String u) { this.w = v; this.uri = u; }
        @Override public void run() {
            try {
                w.evaluateJavascript(
                    "window.__xxtShare && window.__xxtShare(" + JSONObject.quote(uri) + ")", null);
            } catch (Exception ignored) {}
        }
    }

    static class FallbackFlush implements Runnable {
        private final MainActivity act;
        private int tries = 0;
        FallbackFlush(MainActivity a) { this.act = a; }
        @Override public void run() {
            if (act.pendingShare == null) return;            /* 已投递 */
            if (act.pageReady) { act.flushShare(); return; }
            if (++tries < 20 && act.web != null) act.web.postDelayed(this, 500);
        }
    }

    /* 静态嵌套类：避免匿名内部类（d8 8.2.2 处理匿名类会 NPE） */
    static class ProxyClient extends WebViewClient {
        private final MainActivity act;

        ProxyClient(MainActivity a) { this.act = a; }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (url != null && url.startsWith("https://app.local/")) act.onPageLoaded();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (url.startsWith("https://xxt.local/r")) {
                return act.handleProxy(url);
            }
            if (url.startsWith("https://app.local/")) {
                return act.serveAsset(url);
            }
            if (url.startsWith("https://xxt.local/cookies")) {
                return act.cookieJson();
            }
            if (url.startsWith("https://xxt.local/clearcookies")) {
                return act.clearCookies();
            }
            if (url.startsWith("https://xxt.local/version")) {
                return act.okJson(MainActivity.VERSION);
            }
            if (url.startsWith("https://xxt.local/startmon")) {
                int sec = 60;
                try {
                    java.util.regex.Matcher mm = java.util.regex.Pattern
                            .compile("sec=(\\d+)").matcher(url);
                    if (mm.find()) sec = Integer.parseInt(mm.group(1));
                } catch (Exception ignored) {}
                act.startMonitor(sec);
                return act.okJson("monitor started " + sec + "s");
            }
            if (url.startsWith("https://xxt.local/stopmon")) {
                act.stopMonitor();
                return act.okJson("monitor stopped");
            }
            if (url.startsWith("https://xxt.local/moninfo")) {
                return act.okJson(MonitorService.isRunning() ? "running" : "stopped");
            }
            if (url.startsWith("https://xxt.local/monstat")) {
                String t = "";
                try {
                    int i2 = url.indexOf("t=");
                    if (i2 >= 0) t = URLDecoder.decode(url.substring(i2 + 2), "UTF-8");
                } catch (Exception ignored) {}
                MonitorService.setLastInfo(t);
                return act.okJson("ok");
            }
            return null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /* uiMode（深色切换）不重建 Activity，主动重注入主题状态 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        injectNightMode();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            WEB_REF.clear();
            try { web.destroy(); } catch (Exception ignored) {}
            web = null;
        }
        super.onDestroy();
    }
}
