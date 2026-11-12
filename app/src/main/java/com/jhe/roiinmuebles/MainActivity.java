package com.jhe.roiinmuebles;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * La interfaz es una pagina web local (assets/index.html). Esta actividad le da lo que una
 * pagina no puede hacer sola: pedir datos a Catastro y leer paginas de portales de anuncios
 * en un navegador oculto.
 */
public class MainActivity extends Activity {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final int SCRAPE_TIMEOUT_MS = 35000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private WebView web;
    private WebView worker;
    private String scrapeId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });
        setContentView(web);

        worker = new WebView(this);
        WebSettings w = worker.getSettings();
        w.setJavaScriptEnabled(true);
        w.setDomStorageEnabled(true);
        w.setUserAgentString(DESKTOP_UA);
        w.setUseWideViewPort(true);
        w.setLoadWithOverviewMode(true);
        w.setBlockNetworkImage(true);
        worker.layout(0, 0, 1280, 2400);

        if (savedInstanceState == null) web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        worker.destroy();
        web.destroy();
        super.onDestroy();
    }

    private static boolean hostAllowed(String url, String... hosts) {
        try {
            Uri u = Uri.parse(url);
            if (!"https".equals(u.getScheme()) || u.getHost() == null) return false;
            String h = u.getHost().toLowerCase();
            for (String allowed : hosts) {
                if (h.equals(allowed) || h.endsWith("." + allowed)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Devuelve el resultado a la pagina: window.__native(id, ok, valor). jsValue ya es un literal JS. */
    private void reply(final String id, final boolean ok, final String jsValue) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.__native(" + JSONObject.quote(id) + "," + ok + "," + jsValue + ")", null);
            }
        });
    }

    private void startScrape(final String id, String url, final String script, final int waitMs) {
        scrapeId = id;
        worker.setWebViewClient(new WebViewClient() {
            private boolean done = false;

            @Override
            public void onPageFinished(final WebView view, String finishedUrl) {
                if (done || !id.equals(scrapeId)) return;
                done = true;
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!id.equals(scrapeId)) return;
                        view.evaluateJavascript(script, new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                if (!id.equals(scrapeId)) return;
                                scrapeId = null;
                                reply(id, true, value == null ? "null" : value);
                            }
                        });
                    }
                }, waitMs);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame() || done || !id.equals(scrapeId)) return;
                done = true;
                scrapeId = null;
                reply(id, false, JSONObject.quote("carga"));
            }
        });
        worker.loadUrl(url);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!id.equals(scrapeId)) return;
                scrapeId = null;
                worker.stopLoading();
                reply(id, false, JSONObject.quote("tiempo"));
            }
        }, SCRAPE_TIMEOUT_MS);
    }

    private class Bridge {

        /** Peticion directa de datos (Catastro). */
        @JavascriptInterface
        public void httpGet(final String id, final String url) {
            if (!hostAllowed(url, "ovc.catastro.meh.es")) {
                reply(id, false, JSONObject.quote("sitio"));
                return;
            }
            io.execute(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(25000);
                        c.setRequestProperty("Accept", "application/json");
                        int code = c.getResponseCode();
                        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                        if (code >= 400) reply(id, false, JSONObject.quote("http " + code));
                        else reply(id, true, JSONObject.quote(out.toString("UTF-8")));
                    } catch (Exception e) {
                        reply(id, false, JSONObject.quote("red"));
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            });
        }

        /** Carga una pagina de un portal en el navegador oculto y devuelve lo que extrae el script. */
        @JavascriptInterface
        public void scrape(final String id, final String url, final String script, final int waitMs) {
            if (!hostAllowed(url, "habitaclia.com", "fotocasa.es")) {
                reply(id, false, JSONObject.quote("sitio"));
                return;
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    startScrape(id, url, script, Math.max(0, Math.min(waitMs, 10000)));
                }
            });
        }

        @JavascriptInterface
        public void copy(final String text) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Referencia catastral", text));
                }
            });
        }
    }
}
