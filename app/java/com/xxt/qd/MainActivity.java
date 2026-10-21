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
import android.webkit.ValueCallback;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习通签到助手 —— 原生外壳
 *
 * 核心机制：
 *   H5 里所有网络请求都不直接发，而是拼成
 *       https://xxt.local/r?d=<base64(JSON)>
 *   交给 WebViewClient.shouldInterceptRequest 在原生层执行，
 *   原生层负责 Cookie 持久化 + 真实 HTTP，再把结果原样返回。
 *   => 完全绕开浏览器 CORS，且每个请求自动带上学习通会话 Cookie。
 *
 * payload JSON: { m:"POST", u:"https://...", b:"a=1&b=2", x:1 }
 *   m=method  u=url  b=body  x=1 表示 application/x-www-form-urlencoded
 */
public class MainActivity extends Activity {

    /* 供 MonitorService 唤起页面里的 monTick() */
    private static java.lang.ref.WeakReference<WebView> WEB_REF = new java.lang.ref.WeakReference<WebView>(null);
    public static WebView getWeb() { return WEB_REF == null ? null : WEB_REF.get(); }


    private WebView web;
    private SharedPreferences sp;
    private final Map<String, String> cookieJar = new HashMap<>();

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
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

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
        for (String raw : set) {
            if (raw == null) continue;
            String kv = raw.split(";")[0];
            int i = kv.indexOf('=');
            if (i > 0) cookieJar.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim());
        }
        saveCookies();
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
            String q = url.substring(url.indexOf('?') + 1);
            String d = null;
            for (String kv : q.split("&")) {
                if (kv.startsWith("d=")) { d = kv.substring(2); break; }
            }
            if (d == null) return json("{\"__err\":\"no payload\"}", 400);

            String json = new String(Base64.decode(URLDecoder.decode(d, "UTF-8"), Base64.DEFAULT), "UTF-8");
            JSONObject p = new JSONObject(json);
            String method = p.optString("m", "GET");
            String target = p.optString("u", "");
            String body = p.optString("b", "");
            int mode = p.optInt("x", 0);          // 0=json 1=form 2=multipart
            boolean form = mode == 1;
            JSONObject hdrs = p.optJSONObject("h");   // 自定义请求头

            HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(25000);
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

            if (mode == 2 && !"GET".equalsIgnoreCase(method)) {
                /* ---- multipart/form-data：用于上传人脸照片 ---- */
                String bd = "----XBYBOUNDARY" + System.currentTimeMillis();
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
                java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream();
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
                OutputStream os = c.getOutputStream();
                os.write(mb.toByteArray());
                os.flush();
                os.close();
            } else if (!body.isEmpty() && !"GET".equalsIgnoreCase(method)) {
                c.setDoOutput(true);
                if (mode != 3) {
                    c.setRequestProperty("Content-Type",
                            form ? "application/x-www-form-urlencoded; charset=UTF-8"
                                 : "application/json; charset=UTF-8");
                }
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
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

            WebResourceResponse r = json(text, code);
            Map<String, String> hdr = new HashMap<>();
            hdr.put("Access-Control-Allow-Origin", "*");
            hdr.put("Cache-Control", "no-store");
            r.setResponseHeaders(hdr);
            return r;

        } catch (Exception e) {
            WebResourceResponse r = json("{\"__err\":\"" + e.getMessage().replace("\"", "'") + "\"}", 500);
            Map<String, String> hdr = new HashMap<>();
            hdr.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(hdr);
            return r;
        }
    }

    private WebResourceResponse json(String text, int code) {
        WebResourceResponse r = new WebResourceResponse(
                "application/json", "UTF-8", new ByteArrayInputStream(text.getBytes()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            r.setStatusCodeAndReasonPhrase(code, "OK");
        }
        return r;
    }

    /* 把 Cookie 罐暴露给页面（页面需要 _uid / fid 才能拼签到 URL） */
    WebResourceResponse cookieJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            for (Map.Entry<String, String> e : cookieJar.entrySet()) {
                o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        } catch (Exception ignored) {}
        WebResourceResponse r = json(o.toString(), 200);
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        r.setResponseHeaders(h);
        return r;
    }

    /* 用 https://app.local/ 提供 assets —— 让页面处于安全上下文，摄像头才可用 */
    WebResourceResponse serveAsset(String url) {
        String path = url.substring("https://app.local/".length());
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.isEmpty()) path = "index.html";
        try {
            InputStream is = getAssets().open(path);
            String mime = "application/octet-stream";
            if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".js")) mime = "application/javascript";
            else if (path.endsWith(".css")) mime = "text/css";
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

    void deliverShare(android.content.Intent intent) {
        if (intent == null || intent.getData() == null) return;
        final String uri = intent.getData().toString();
        if (web == null) return;
        web.postDelayed(new ShareDeliver(this, uri), 900);
    }

    /* 摄像头授权：WebView 里 getVideoTracks 必须由 onPermissionRequest 放行 */
    static class CamClient extends WebChromeClient {
        private final MainActivity act;
        CamClient(MainActivity a) { this.act = a; }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            if (m != null) {
                android.util.Log.i("xxtjs", m.messageLevel() + " " + m.message()
                        + " @" + m.lineNumber());
            }
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            act.runOnUiThread(new PermGrant(request));
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (act.mFilePathCallback != null) {
                act.mFilePathCallback.onReceiveValue(null);
                act.mFilePathCallback = null;
            }
            act.mFilePathCallback = cb;
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            if (params != null && params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                act.startActivityForResult(android.content.Intent.createChooser(i, "选择图片"), 2001);
            } catch (Exception e) {
                act.mFilePathCallback = null;
                return false;
            }
            return true;
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
        WebResourceResponse r = json("{\"ok\":true,\"msg\":\"" + msg + "\"}", 200);
        Map<String, String> h = new HashMap<String, String>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        r.setResponseHeaders(h);
        return r;
    }

    /* ---------- 文件选择器（相册 / 拍照上传）----------
       WebView 默认不实现 onShowFileChooser，input[type=file].click() 会毫无反应 */
    ValueCallback<Uri[]> mFilePathCallback;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == 2001) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                } else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            if (mFilePathCallback != null) {
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /* 静态嵌套类：d8 处理匿名内部类会 NPE，一律用命名类 */
    static class ShareDeliver implements Runnable {
        private final MainActivity act;
        private final String uri;
        ShareDeliver(MainActivity a, String u) { this.act = a; this.uri = u; }
        @Override public void run() {
            if (act.web == null) return;
            act.web.evaluateJavascript(
                "window.__xxtShare && window.__xxtShare(" + org.json.JSONObject.quote(uri) + ")", null);
        }
    }

    /* 静态嵌套类：避免匿名内部类（d8 8.2.2 处理匿名类会 NPE） */
    static class ProxyClient extends WebViewClient {
        private final MainActivity act;

        ProxyClient(MainActivity a) { this.act = a; }

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
                    if (i2 >= 0) t = java.net.URLDecoder.decode(url.substring(i2 + 2), "UTF-8");
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
}
