package com.deztor.haven;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.OnBackPressedCallback;
import androidx.webkit.WebViewFeature;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.BridgeWebViewClient;
import com.getcapacitor.CapConfig;

/**
 * Shell around the published Haven site.
 *
 * A plain WebView would load window.open / target="_blank" links (stores, docs, the APK download)
 * over Haven, so new windows are routed here: Haven pages open in the app, everything else opens
 * in the phone's browser. Links and redirects in the app's own window follow the same rule.
 */
public class MainActivity extends BridgeActivity {

    @Override
    protected void load() {
        // Without these two features Capacitor fetches the page itself and injects its bridge into
        // whatever HTML comes back (even after a redirect to another site), and exposes the bridge
        // to every frame. Such an old WebView gets the bundled local page instead of the remote
        // site (www/index.html asks to update Android System WebView).
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            config = new CapConfig.Builder(this).create();
        }
        super.load();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = getBridge().getWebView();
        webView.getSettings().setSupportMultipleWindows(true);
        webView.setWebChromeClient(new PopupChromeClient(getBridge()));
        getBridge().setWebViewClient(new ShellWebViewClient(getBridge()));

        // Back walks the site's history; at the first screen the app goes to the background
        // instead of closing.
        getOnBackPressedDispatcher().addCallback(
            this,
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    WebView view = getBridge().getWebView();
                    if (view.canGoBack()) view.goBack();
                    else moveTaskToBack(true);
                }
            }
        );
    }

    /** Only Haven itself: https, the exact host, the default port and no user info. */
    static boolean isHaven(Uri url, Uri app) {
        return (
            "https".equals(url.getScheme()) &&
            "https".equals(app.getScheme()) &&
            url.getHost() != null &&
            url.getHost().equals(app.getHost()) &&
            (url.getPort() == -1 || url.getPort() == 443) &&
            url.getUserInfo() == null
        );
    }

    /** Opens a Haven page in the app and any other web address in the phone's browser. */
    static void open(Bridge bridge, Uri url) {
        if (isHaven(url, Uri.parse(bridge.getAppUrl()))) {
            // launchIntent still refuses Capacitor's own proxy paths and lets plugins decide.
            if (!bridge.launchIntent(url)) bridge.getWebView().loadUrl(url.toString());
            return;
        }
        openOutside(bridge, url);
    }

    private static void openOutside(Bridge bridge, Uri url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, url);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            bridge.getContext().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // No browser installed: nothing sensible to open.
        }
    }

    /**
     * Capacitor keeps a top-level navigation in the app whenever host and scheme match, whatever
     * the port or user info. Here only the exact Haven origin stays; any other http(s) page,
     * a redirect included, goes to the browser. Haven pages still pass Capacitor's own checks
     * (its proxy paths, plugins). Other schemes (mailto:, tel:, intent:) and frames keep
     * Capacitor's handling.
     */
    private static final class ShellWebViewClient extends BridgeWebViewClient {

        private final Bridge bridge;

        ShellWebViewClient(Bridge bridge) {
            super(bridge);
            this.bridge = bridge;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            String scheme = url.getScheme();
            if (request.isForMainFrame() && ("http".equals(scheme) || "https".equals(scheme))) {
                if (isHaven(url, Uri.parse(bridge.getAppUrl()))) {
                    return super.shouldOverrideUrlLoading(view, request);
                }
                openOutside(bridge, url);
                return true;
            }
            return super.shouldOverrideUrlLoading(view, request);
        }
    }

    private static final class PopupChromeClient extends BridgeWebChromeClient {

        private final Bridge bridge;

        PopupChromeClient(Bridge bridge) {
            super(bridge);
            this.bridge = bridge;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Haven needs no camera or microphone, so the page never gets them.
            request.deny();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            // A throwaway window: a page may open it empty and set its location later, so the first real http(s) address decides where it goes.
            WebView popup = new WebView(view.getContext());
            popup.setWebViewClient(
                new WebViewClient() {
                    private boolean handled;

                    private void route(WebView window, Uri url) {
                        if (handled || url == null) return;
                        String scheme = url.getScheme();
                        if (!"http".equals(scheme) && !"https".equals(scheme)) return;
                        handled = true;
                        window.stopLoading();
                        open(bridge, url);
                        window.post(window::destroy);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView window, WebResourceRequest request) {
                        route(window, request.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView window, String url, Bitmap favicon) {
                        route(window, Uri.parse(url));
                    }
                }
            );
            popup.setWebChromeClient(
                new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView window) {
                        window.destroy();
                    }
                }
            );
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }
    }
}
