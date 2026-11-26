package com.followup.tracker;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.view.WindowInsets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;

public final class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String HOME = "https://" + HOST + "/";
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(246, 245, 239));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safeArea = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String path = request.getUrl().getPath();
                return !isLocal(request.getUrl()) || !("/".equals(path) || "/index.html".equals(path));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String path = url.getPath();
                if (!isLocal(url) || path == null || !path.startsWith("/")) {
                    return notFound();
                }

                String name = "/".equals(path) ? "index.html" : path.substring(1);
                String type = switch (name) {
                    case "index.html" -> "text/html";
                    case "app.js" -> "text/javascript";
                    case "style.css" -> "text/css";
                    case "manifest.webmanifest" -> "application/manifest+json";
                    case "icon-192.png", "icon-512.png" -> "image/png";
                    default -> null;
                };
                if (type == null) {
                    return notFound();
                }
                try {
                    String encoding = type.startsWith("image/") ? null : "UTF-8";
                    return new WebResourceResponse(type, encoding, getAssets().open(name));
                } catch (IOException error) {
                    return notFound();
                }
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl(HOME);
    }

    private static boolean isLocal(Uri url) {
        return "https".equals(url.getScheme()) && HOST.equals(url.getHost()) && url.getPort() == -1;
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    @Override
    protected void onDestroy() {
        if (webView.getParent() instanceof FrameLayout root) {
            root.removeView(webView);
        }
        webView.destroy();
        super.onDestroy();
    }
}
