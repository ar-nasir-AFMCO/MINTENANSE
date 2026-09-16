package com.afmco.dabbabat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

public class MainActivity extends AppCompatActivity {

    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final String PAGE = ORIGIN + "/app/index.html";
    private static final String MASTER = "master.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private ActivityResultLauncher<Intent> picker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        picker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (fileCb == null) return;
                    Uri[] out = null;
                    if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null
                            && r.getData().getData() != null) {
                        out = new Uri[]{ r.getData().getData() };
                    }
                    fileCb.onReceiveValue(out);
                    fileCb = null;
                });

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        WebView.setWebContentsDebuggingEnabled(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain("appassets.androidplatform.net")
                .addPathHandler("/app/", path -> {
                    if (!"index.html".equals(path)) return null;
                    try {
                        File up = new File(getFilesDir(), MASTER);
                        InputStream in = up.exists()
                                ? new FileInputStream(up)
                                : getAssets().open("app.html");
                        return new WebResourceResponse("text/html", "utf-8", in);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (r.getUrl().toString().startsWith(ORIGIN)) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    picker.launch(p.createIntent());
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(PAGE);
    }

    public class Bridge {

        @JavascriptInterface
        public void save(String name, String dataUrl, String mime) {
            try {
                byte[] b = Base64.decode(dataUrl.substring(dataUrl.indexOf(",") + 1), Base64.DEFAULT);
                String ext = mime != null && mime.contains("json") ? ".json"
                        : mime != null && mime.contains("csv") ? ".csv" : ".txt";
                OutputStream o;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name + ext);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                    o = getContentResolver().openOutputStream(
                            getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv));
                } else {
                    o = new FileOutputStream(new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), name + ext));
                }
                o.write(b);
                o.close();
                toastUi("حُفظ الملف في مجلد التنزيلات");
            } catch (Exception e) {
                toastUi("تعذّر حفظ الملف");
            }
        }

        @JavascriptInterface
        public void syncConfig(final String url, final String token) {
            if (url == null || url.isEmpty()) return;
            new Thread(() -> fetchMaster(url, token == null ? "" : token)).start();
        }
    }

    private void toastUi(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private void fetchMaster(String base, String token) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(base + "?mode=app&t=" + Uri.encode(token)).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            if (c.getResponseCode() != 200) return;

            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
            byte[] fresh = buf.toByteArray();

            if (fresh.length < 10000) return;
            if (!new String(fresh, 0, 200, "UTF-8").contains("<!DOCTYPE html")) return;

            File cur = new File(getFilesDir(), MASTER);
            if (cur.exists() && sha(readAll(cur)).equals(sha(fresh))) return;

            FileOutputStream o = new FileOutputStream(cur);
            o.write(fresh);
            o.close();
            toastUi("وصل تحديث للنسخة الأم — يُطبّق عند إعادة فتح التطبيق. بياناتك كما هي.");
        } catch (Exception ignored) { }
    }

    private byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        in.close();
        return buf.toByteArray();
    }

    private String sha(byte[] b) throws Exception {
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(b), Base64.NO_WRAP);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
