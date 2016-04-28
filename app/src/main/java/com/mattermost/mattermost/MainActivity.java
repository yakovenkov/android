/**
 * Copyright (c) 2016 Mattermost, Inc. All Rights Reserved.
 * See License.txt for license information.
 */
package com.mattermost.mattermost;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.KeyEvent;

import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.mattermost.model.User;
import com.mattermost.service.IResultListener;
import com.mattermost.service.MattermostService;
import com.mattermost.service.Promise;


import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;


public class MainActivity extends WebViewActivity {

    WebView webView;
    Uri appUri;

    String senderID;
    GoogleCloudMessaging gcm;
    ProgressDialog dialog;
    long timeAway;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        appUri = Uri.parse(service.getBaseUrl());

        webView = (WebView) findViewById(R.id.web_view);

        initProgressBar(R.id.webViewProgress);
        initWebView(webView);
    }

    protected void loadRootView() {
        String url = service.getBaseUrl();
        if (!url.endsWith("/"))
            url += "/";

        if (MattermostService.service.GetLastPath().length() > 0) {
            Log.i("loadRootView", "loading " + MattermostService.service.GetLastPath());
            url = MattermostService.service.GetLastPath();
        }

        webView.loadUrl(url);

        dialog = new ProgressDialog(this);
        dialog.setMessage(this.getText(R.string.loading));
        dialog.setCancelable(false);
        dialog.show();
    }

    @Override
    protected void onPause() {
        Log.i("MainActivity", "paused");
        webView.onPause();
        webView.pauseTimers();
        timeAway = System.currentTimeMillis();

        // We only set the last path if it was a channel view
        if (webView.getUrl().contains("/channels/")) {
            MattermostService.service.SetLastPath(webView.getUrl());
        }

        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i("MainActivity", "resumed");
        webView.onResume();
        webView.resumeTimers();

        if ((System.currentTimeMillis() - timeAway) > 1000 * 60 * 5) {
            loadRootView();
        }

        super.onResume();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            Log.i("Back", webView.getUrl());
            if (webView.getUrl() == MattermostService.service.getBaseUrl()) {
                MattermostService.service.logout();

                Intent intent = new Intent(this, SelectServerActivity.class);
                startActivityForResult(intent, SelectServerActivity.START_CODE);
                finish();
                return true;
            }

            if (webView.getUrl().endsWith("/login")) {
                MattermostService.service.logout();

                Intent intent = new Intent(this, SelectServerActivity.class);
                startActivityForResult(intent, SelectServerActivity.START_CODE);
                finish();
                return true;
            }

            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void setWebViewClient(WebView view) {
        view.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageFinished(WebView view, String url) {
                dialog.hide();
                Log.i("onPageFinished", "onPageFinished while loading");
                Log.i("onPageFinished", url);

                if (url.equals("about:blank")) {
                    AlertDialog.Builder alert = new AlertDialog.Builder(MainActivity.this);
                    alert.setTitle(R.string.error_retry);

                    alert.setPositiveButton(R.string.error_logout, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            MainActivity.this.onLogout();
                        }
                    });

                    alert.setNegativeButton(R.string.error_refresh, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            MainActivity.this.loadRootView();
                        }
                    });

                    alert.show();
                }
            }

//            @Override
//            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
//                Log.e("onReceivedError", "onReceivedError while loading");
//                Log.e("onReceivedError", error.getDescription().toString() + " " + error.getErrorCode());
//            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Log.e("onReceivedHttpError", "onReceivedHttpError while loading");
                StringBuilder total = new StringBuilder();

                if (errorResponse.getData() != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(errorResponse.getData()));
                    String line;
                    try {
                        while ((line = r.readLine()) != null) {
                            total.append(line);
                        }
                    } catch (IOException e) {
                        total.append("failed to read data");
                    }
                } else {
                    total.append("no data");
                }

                Log.e("onReceivedHttpError", total.toString());
            }

            @Override
            public void onReceivedError (WebView view, int errorCode, String description, String failingUrl) {
                Log.e("onReceivedErrord", "onReceivedError while loading (d)");
                Log.e("onReceivedErrord", errorCode + " " + description + " " + failingUrl);
                webView.loadUrl("about:blank");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);

//                if (!isLoggedIn()) {
//                    return false;
//                }

                if (!uri.getHost().equalsIgnoreCase(appUri.getHost())) {
                    openUrl(uri);
                    return true;
                }

                if (uri.getPath().startsWith("/static/help")) {
                    openUrl(uri);
                    return true;
                }

                if (uri.getPath().contains("/files/get/")) {
                    openUrl(uri);
                    return true;
                }

                return super.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                //Log.i("WebResourceResponse", url.toLowerCase());

                // Check to see if we need to attach the device Id
                if (url.toLowerCase().contains("/channels/")) {
                    if (!MattermostService.service.isAttached()) {
                        Log.i("MainActivity", "Attempting to attach device id");
                        MattermostService.service.init(MattermostService.service.getBaseUrl());
                        Promise<User> p = MattermostService.service.attachDevice();
                        if (p != null) {
                            p.then(new IResultListener<User>() {
                                @Override
                                public void onResult(Promise<User> promise) {
                                    if (promise.getError() != null) {
                                        Log.e("AttachDeviceId", promise.getError());
                                    } else {
                                        Log.i("AttachDeviceId", "Attached device_id to session");
                                        MattermostService.service.SetAttached();
                                    }
                                }
                            });
                        }
                    }
                }

                // Check to see if the user was trying to logout
                if (url.toLowerCase().endsWith("/logout")) {
                    MattermostApplication.handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onLogout();
                        }
                    });
                }

//                String baseUrl = "";
//                int i = service.getBaseUrl().lastIndexOf("/");
//                if (i != -1) {
//                    baseUrl = service.getBaseUrl().substring(0, i);
//
//                }

                // If you're at the root then logout and so the select team view
//                if (url.toLowerCase().endsWith(baseUrl + "/") || url.toLowerCase().endsWith(baseUrl)) {
//                    MattermostApplication.handler.post(new Runnable() {
//                        @Override
//                        public void run() {
//                            onLogout();
//                        }
//                    });
//                }

                return super.shouldInterceptRequest(view, url);
            }
        });
    }

    private void openUrl(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        startActivity(intent);
    }

//    private boolean isLoggedIn() {
//        String baseUrl = service.getBaseUrl();
//        if (baseUrl == null) {
//            return false;
//        }
//
//        Log.i("WebResourceResponse", "HEREEEEE");
//
//        CookieManager m = CookieManager.getInstance();
//        Log.i("WebResourceResponse", "HEREEEEE 2");
//        Log.i("WebResourceResponse", baseUrl);
//
//
//        String cookies = CookieManager.getInstance().getCookie(baseUrl);
//        Log.i("WebResourceResponse", "HEREEEEE 3");
//
//        if (cookies == null)
//            return false;
//        if (cookies.trim().isEmpty())
//            return false;
//        if (!cookies.contains("MMAUTHTOKEN"))
//            return false;
//        return true;
//    }

    @Override
    protected void onLogout() {
        Log.i("MainActivity", "onLogout called");
        super.onLogout();

        MattermostService.service.logout();

        Intent intent = new Intent(this, SelectServerActivity.class);
        startActivityForResult(intent, SelectServerActivity.START_CODE);
        finish();
    }
}
