package com.afmco.soot;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    // النسخة الحيّة (أون لاين) — أي تحديث للتطبيق يظهر فوراً
    private static final String ONLINE_URL =
            "https://ar-nasir-afmco.github.io/MINTENANSE/afmc/soot-app.html";
    private static final String OFFLINE_URL = "file:///android_asset/soot-app.html";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                // لو انقطع النت: ارجع للنسخة المضمّنة محلياً
                if (req != null && req.isForMainFrame()) {
                    v.loadUrl(OFFLINE_URL);
                }
            }
        });
        web.loadUrl(ONLINE_URL);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
