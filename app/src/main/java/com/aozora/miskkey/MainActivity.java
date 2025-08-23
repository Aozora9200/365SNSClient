package com.aozora.miskkey;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.BounceInterpolator;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String BASE_URL = "https://365sns.f5.si/";

    private static final long ICON_ANIM_MS = 600L;
    private static final long SPLASH_FADE_MS = 400L;
    private static final long MIN_SPLASH_DURATION_MS = 800L;

    private static final int HTTP_422 = 422;
    private static final int MAX_422_RETRY = 2;
    private static final long BASE_BACKOFF_MS = 600L;
    private static final long MAX_BACKOFF_MS = 1600L;

    private static final String OPTIMIZATION_JS =
            "(function(){" +
                    "try{" +
                    "var imgs=document.getElementsByTagName('img');" +
                    "for(var i=0;i<imgs.length;i++){try{if(!imgs[i].hasAttribute('loading'))imgs[i].setAttribute('loading','lazy');}catch(e){}}" +
                    "var meta=document.querySelector('meta[name=viewport]');" +
                    "if(!meta){meta=document.createElement('meta');meta.name='viewport';document.head.appendChild(meta);}meta.content='width=device-width,initial-scale=1,maximum-scale=1,minimum-scale=1,user-scalable=no';" +
                    "var s=document.getElementsByTagName('script');for(var z=0;z<s.length;z++){try{if(!s[z].hasAttribute('defer')&&!s[z].hasAttribute('async'))s[z].setAttribute('defer','true');}catch(e){}}" +
                    "}catch(e){} })();";

    private WebView webView;
    private View splash;
    private ImageView splashIcon;
    private ProgressBar splashSpinner;
    private Toolbar toolbar;

    private Handler mainHandler;
    private ExecutorService ioExecutor;

    private volatile boolean pageReadyOnce = false;
    private volatile boolean splashHidden = false;
    private long splashStartTime;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<String[]> requestMultiplePermissionsLauncher;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private volatile ValueCallback<Uri[]> filePathCallback;
    private volatile PermissionRequest pendingPermissionRequest;
    private MediaPlayer mediaPlayer;

    private final Map<String, Integer> retry422Count = new ConcurrentHashMap<>();
    private final Map<String, Long> last422At = new ConcurrentHashMap<>();

    private final DownloadListener downloadListener = (url, userAgent, cd, type, len) -> showToast("ダウンロードを開始します...: " + url);

    private static final String GITHUB_API_URL =
            "https://api.github.com/repos/Aozora9200/365SNSClient/releases/latest";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            showToast("エラー：レイアウトの読み込みに失敗しました.");
            finish();
            return;
        }

        initializeViews();
        initializeThreading();
        setupPermissionLaunchers();

        if (webView == null || splash == null || splashIcon == null || splashSpinner == null) {
            showToast("アプリケーションの初期化に失敗しました.");
            finish();
            return;
        }

        setSupportActionBar(toolbar);


        checkStoragePermission();
        new CheckUpdateTask().execute();


        ioExecutor.execute(this::loadSplashIconAndPlaySound);

        configureWebView();

        splashStartTime = System.currentTimeMillis();
        playStartupAnimation();
        loadInitialContent();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                final WebView wv = webView;
                if (wv != null && wv.canGoBack()) {
                    wv.goBack();
                } else {
                    remove();
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private class CheckUpdateTask extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... voids) {
            try {
                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                InputStream in = conn.getInputStream();
                Scanner scanner = new Scanner(in).useDelimiter("\\A");
                String result = scanner.hasNext() ? scanner.next() : "";
                conn.disconnect();

                JSONObject json = new JSONObject(result);
                return json.getString("tag_name"); // バージョン
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(String latestVersion) {
            if (latestVersion == null) return;

            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                String currentVersion = pInfo.versionName;

                if (!currentVersion.equals(latestVersion)) {
                    showUpdateDialog(latestVersion);
                }
            } catch (Exception ignored) {}
        }
    }

    private void showUpdateDialog(final String latestVersion) {
        new AlertDialog.Builder(this)
                .setTitle("アップデートがあります")
                .setMessage("最新バージョン (" + latestVersion + ") が利用可能です。更新しますか？")
                .setPositiveButton("更新する", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // GitHubリリースページへ飛ばす
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/Aozora9200/365SNSClient/releases/latest"));
                        startActivity(browserIntent);
                    }
                })
                .setNegativeButton("後で", null)
                .show();
    }

    private void initializeViews() {
        splash = findViewById(R.id.splash);
        splashIcon = findViewById(R.id.splashIcon);
        splashSpinner = findViewById(R.id.splashSpinner);
        webView = findViewById(R.id.webview);
        toolbar = findViewById(R.id.toolbar);
    }

    private void initializeThreading() {
        mainHandler = new Handler(Looper.getMainLooper());
        ioExecutor = Executors.newSingleThreadExecutor();
    }

    private void setupPermissionLaunchers() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> showToast(isGranted ? "権限が許可されました." : "権限が拒否されました."));

        requestMultiplePermissionsLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                permissions -> {
                    boolean allGranted = true;
                    for (Boolean granted : permissions.values()) {
                        if (!Boolean.TRUE.equals(granted)) { allGranted = false; break; }
                    }
                    final PermissionRequest pr = pendingPermissionRequest;
                    if (pr != null) {
                        if (allGranted) pr.grant(pr.getResources()); else pr.deny();
                        pendingPermissionRequest = null;
                    }
                    if (!allGranted) showToast("権限が拒否されました.");
                });

        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleFileChooserResult);
    }

    private void handleFileChooserResult(ActivityResult result) {
        final ValueCallback<Uri[]> cb = filePathCallback;
        if (cb == null) return;
        Uri[] results = null;
        if (result.getResultCode() == RESULT_OK) {
            final Intent data = result.getData();
            if (data != null) {
                if (data.getClipData() != null) {
                    final int count = data.getClipData().getItemCount();
                    if (count > 0) {
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
        }
        cb.onReceiveValue(results);
        filePathCallback = null;
    }

    private void loadSplashIconAndPlaySound() {
        Bitmap bitmap = null;
        InputStream is = null;
        try {
            is = getAssets().open("365.png");
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, opt);
            closeQuietly(is);

            opt.inSampleSize = calculateInSampleSize(opt.outWidth, opt.outHeight, 512, 512);
            opt.inJustDecodeBounds = false;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            opt.inDither = false;
            opt.inMutable = false;
            is = getAssets().open("365.png");
            bitmap = BitmapFactory.decodeStream(is, null, opt);
        } catch (IOException e) {

        } finally {
            closeQuietly(is);
        }

        final Bitmap finalBitmap = bitmap;
        mainHandler.post(() -> {
            if (splashIcon != null && finalBitmap != null) splashIcon.setImageBitmap(finalBitmap);
            playBootSound();
        });
    }

    private static void closeQuietly(InputStream is) {
        if (is != null) try { is.close(); } catch (IOException ignored) {}
    }

    private static int calculateInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height >> 1;
            int halfWidth = width >> 1;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize <<= 1;
            }
        }
        return inSampleSize;
    }

    private void playBootSound() {
        releaseMediaPlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("boot.mp3");
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setLooping(false);
            mp.setVolume(1.0f, 1.0f);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> releaseMediaPlayer());
            mp.setOnErrorListener((m, what, extra) -> { releaseMediaPlayer(); return true; });
            mp.prepareAsync();
        } catch (IOException e) {

            releaseMediaPlayer();
        }
    }

    private void playClickSound() {
        releaseMediaPlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("touch.mp3");
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setLooping(false);
            mp.setVolume(1.0f, 1.0f);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnCompletionListener(m -> releaseMediaPlayer());
            mp.setOnErrorListener((m, what, extra) -> { releaseMediaPlayer(); return true; });
            mp.prepareAsync();
        } catch (IOException e) {

            releaseMediaPlayer();
        }
    }

    private void releaseMediaPlayer() {
        final MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignored) {}
            mp.release();
        }
    }

    private void checkStoragePermission() {
        final String[] permissions = getStoragePermissions();
        if (!hasPermissions(permissions)) requestMultiplePermissionsLauncher.launch(permissions);
    }

    private String[] getStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        } else {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
    }

    private boolean hasPermissions(String[] permissions) {
        for (String p : permissions) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void configureWebView() {
        final WebView wv = webView;
        final CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.setAcceptThirdPartyCookies(wv, true);
        cm.flush();

        final WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setSupportMultipleWindows(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setTextZoom(100);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(false);
        ws.setGeolocationEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ws.setSafeBrowsingEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        wv.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setAlpha(0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);

        wv.setWebChromeClient(new FastChromeClient(this));
        wv.setWebViewClient(new FastWebViewClient(this));
        wv.setDownloadListener(downloadListener);
    }

    private static final class FastChromeClient extends WebChromeClient {
        private final WeakReference<MainActivity> ref;
        FastChromeClient(MainActivity a) { this.ref = new WeakReference<>(a); }

        @Override public void onPermissionRequest(PermissionRequest request) {
            MainActivity a = ref.get(); if (a == null) { request.deny(); return; }


            String[] androidPerms = a.mapPermissionRequestToAndroidPermissions(request);
            if (androidPerms == null) {
                try { request.grant(request.getResources()); } catch (Throwable ignored) {}
                return;
            }

            a.pendingPermissionRequest = request;
            a.requestMultiplePermissionsLauncher.launch(androidPerms);
        }

        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            MainActivity a = ref.get(); if (a == null) return;
            if (a.pendingPermissionRequest != null && a.pendingPermissionRequest.equals(request)) {
                a.pendingPermissionRequest = null;
            }
            try { request.deny(); } catch (Throwable ignored) {}
        }

        @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
            MainActivity a = ref.get(); if (a == null) return false;
            ValueCallback<Uri[]> prev = a.filePathCallback;
            if (prev != null) prev.onReceiveValue(null);
            a.filePathCallback = filePathCallback;

            Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            File photoFile = null;
            if (takePictureIntent.resolveActivity(a.getPackageManager()) != null) {
                try {
                    photoFile = a.createImageFile();
                    takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                            androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".provider", photoFile));
                } catch (IOException e) { /* suppressed */ }
            }

            Intent contentSelection = new Intent(Intent.ACTION_GET_CONTENT);
            contentSelection.addCategory(Intent.CATEGORY_OPENABLE);
            contentSelection.setType("*/*");

            Intent[] initial = (photoFile != null) ? new Intent[]{takePictureIntent} : new Intent[0];
            Intent chooser = new Intent(Intent.ACTION_CHOOSER);
            chooser.putExtra(Intent.EXTRA_INTENT, contentSelection);
            chooser.putExtra(Intent.EXTRA_TITLE, "Choose an action");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initial);
            a.fileChooserLauncher.launch(chooser);
            return true;
        }
    }

    private String[] mapPermissionRequestToAndroidPermissions(PermissionRequest request) {
        if (request == null) return null;
        List<String> perms = new ArrayList<>(2);
        try {
            for (String res : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                    if (!hasPermissions(new String[]{ Manifest.permission.CAMERA })) perms.add(Manifest.permission.CAMERA);
                } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                    if (!hasPermissions(new String[]{ Manifest.permission.RECORD_AUDIO })) perms.add(Manifest.permission.RECORD_AUDIO);
                }
            }
        } catch (Throwable ignored) {}
        return perms.isEmpty() ? null : perms.toArray(new String[0]);
    }

    private final class FastWebViewClient extends WebViewClient {
        private final WeakReference<MainActivity> ref;
        FastWebViewClient(MainActivity a) { this.ref = new WeakReference<>(a); }

        private boolean isMainFrame(WebResourceRequest req) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return req.isForMainFrame();
            return true;
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            MainActivity a = ref.get(); if (a == null) return;
            a.onPageFirstReady();
            a.injectOptimizationJavaScript();
            a.reset422State(url);
        }

        @Override public void onPageFinished(WebView view, String url) {
            MainActivity a = ref.get(); if (a == null) return;
            playClickSound();
            a.onPageFirstReady();
            a.injectOptimizationJavaScript();
            a.reset422State(url);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            MainActivity a = ref.get(); if (a != null) a.onPageFirstReady();
        }

        @SuppressWarnings("deprecation")
        @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {

        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isMainFrame(request)) {
                final int status = errorResponse.getStatusCode();
                final String url = String.valueOf(request.getUrl());
                MainActivity a = ref.get(); if (a == null) return;
                if (status == HTTP_422) {
                    a.handleHttp422(view, url);
                    return;
                }
                if (status >= 400) a.showToast("HTTP エラー: " + status);
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) return handleUrlLoading(request.getUrl().toString(), true);
            return false;
        }

        @SuppressWarnings("deprecation")
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return handleUrlLoading(url, true);
            return false;
        }

        private boolean handleUrlLoading(String url, boolean fromShouldOverride) {
            MainActivity a = ref.get(); if (a == null) return false;
            if (a.isAllowedNavigation(url)) {
                a.webView.loadUrl(url);
                return true;
            }
            a.openExternalUrl(url);
            return true;
        }
    }

    private void openExternalUrl(final String url) {
        if (url == null) return;
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PackageManager pm = getPackageManager();
                ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri != null && ri.activityInfo != null) {
                    intent.setPackage(ri.activityInfo.packageName);
                }
                startActivity(intent);
            } catch (Exception ignored) {
                try { webView.loadUrl(BASE_URL); } catch (Throwable ignored2) {}
            }
        });
    }

    private boolean isAllowedNavigation(String url) {
        if (url == null) return false;
        if (url.contains(BASE_URL)) return true;
        if (url.startsWith("data:") || url.startsWith("about:") || url.startsWith("intent:")) return true;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            if (host.contains("365sns.f5.si")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private void handleHttp422(WebView view, String url) {
        final int tried = retry422Count.getOrDefault(url, 0);
        if (tried >= MAX_422_RETRY) {
            showToast("エラー (422)が発生しました。時間を置いてから再試行してください。");
            return;
        }
        long now = System.currentTimeMillis();
        long last = last422At.getOrDefault(url, 0L);
        int nextTry = tried + 1;
        long delay = Math.min((BASE_BACKOFF_MS << (nextTry - 1)), MAX_BACKOFF_MS);
        long wait = Math.max(0L, (last + delay) - now);

        retry422Count.put(url, nextTry);
        last422At.put(url, now + wait);

        mainHandler.postDelayed(() -> {
            try {
                if (view != null) view.evaluateJavascript("location.reload();", null);
            } catch (Throwable ignored) {}
        }, wait);
    }

    private void reset422State(String url) {
        if (url == null) return;
        retry422Count.remove(url);
        last422At.remove(url);
    }

    private void loadInitialContent() {
        if (isNetworkAvailable()) {
            webView.loadUrl(BASE_URL);
        } else {
            webView.loadUrl("about:blank");
            showToast("インターネットに接続されていません. ネットワーク設定を確認してください.");
            onPageFirstReady();
        }
    }

    private void onPageFirstReady() {
        if (pageReadyOnce) return;
        pageReadyOnce = true;
        long elapsed = System.currentTimeMillis() - splashStartTime;
        long remain = MIN_SPLASH_DURATION_MS - elapsed;
        if (remain > 0) mainHandler.postDelayed(this::hideSplash, remain); else hideSplash();
    }

    private void playStartupAnimation() {
        PropertyValuesHolder sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1f);
        PropertyValuesHolder sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1f);
        ObjectAnimator scale = ObjectAnimator.ofPropertyValuesHolder(splashIcon, sx, sy);
        AnimatorSet set = new AnimatorSet();
        set.play(scale);
        set.setDuration(ICON_ANIM_MS);
        set.setInterpolator(new BounceInterpolator());
        set.start();
    }

    private void hideSplash() {
        if (splashHidden) return;
        splashHidden = true;
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(splash, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofPropertyValuesHolder(splashSpinner,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0f)),
                ObjectAnimator.ofFloat(webView, View.ALPHA, 0f, 1f)
        );
        set.setDuration(SPLASH_FADE_MS);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { splash.setVisibility(View.GONE); }
        });
        set.start();
    }

    private void injectOptimizationJavaScript() {
        try { webView.evaluateJavascript(OPTIMIZATION_JS, null); } catch (Throwable ignored) {}
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        try {
            getMenuInflater().inflate(R.menu.main_menu, menu);
            return true;
        } catch (Throwable t) {
            showToast("メニューの読み込みに失敗しました.");
            return false;
        }
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_screenshot) { takeScreenshot(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void takeScreenshot() {
        if (!hasPermissions(getStoragePermissions())) {
            showToast("スクリーンショットを利用するには, ストレージ権限が必要です.");
            requestMultiplePermissionsLauncher.launch(getStoragePermissions());
            return;
        }
        ioExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int width = Math.max(webView.getWidth(), dm.widthPixels);
                int height = Math.max(webView.getHeight(), dm.heightPixels);
                bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                Drawable bg = webView.getBackground();
                if (bg != null) bg.draw(c); else c.drawColor(Color.WHITE);
                webView.draw(c);
                String fileName = "Screenshot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
                Uri uri = saveBitmapToStorage(bmp, fileName);
                if (uri != null) mainHandler.post(() -> showToast("スクリーンショットを保存しました."));
                else mainHandler.post(() -> showToast("問題が発生したため, スクリーンショットを保存できませんでした."));
            } catch (Throwable t) {
                final String msg = t.getMessage();
                mainHandler.post(() -> showToast("エラー: " + (msg != null ? msg : "unknown")));
            } finally {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            }
        });
    }

    private Uri saveBitmapToStorage(Bitmap bitmap, String fileName) {
        OutputStream out = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/365");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return null;
                out = getContentResolver().openOutputStream(uri);
                if (out == null) return null;
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
                out.flush();
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, v, null, null);
                return uri;
            } else {
                File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                File dir = new File(pics, "365");
                if (!dir.exists() && !dir.mkdirs()) return null;
                File file = new File(dir, fileName);
                out = new FileOutputStream(file);
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
                out.flush();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
        }
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private File createImageFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("JPEG_" + ts + "_", ".jpg", storageDir);
    }

    @Override protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override protected void onResume() {
        super.onResume();
        CookieManager.getInstance().flush();
    }

    @Override protected void onDestroy() {
        releaseMediaPlayer();
        final WebView wv = webView;
        if (wv != null) {
            try {
                wv.loadUrl("about:blank");
                wv.stopLoading();
                wv.setWebChromeClient(null);
                wv.setWebViewClient(null);
                wv.clearHistory();
                wv.clearCache(true);
                CookieManager.getInstance().flush();
                if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
                wv.removeAllViews();
                wv.destroy();
            } catch (Throwable t) {

            }
        }

        mainHandler.removeCallbacksAndMessages(null);
        if (ioExecutor != null && !ioExecutor.isShutdown()) {
            ioExecutor.shutdownNow();
            try { if (!ioExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {} }
            catch (InterruptedException e) {}
        }
        super.onDestroy();
    }
}
