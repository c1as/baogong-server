package com.baogong.server;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.baogong.server.installer.AssetInstaller;
import com.baogong.server.installer.HotUpdateImporter;
import com.baogong.server.updater.GitHubReleaseClient;
import com.baogong.server.updater.RepoUpdater;
import com.baogong.server.updater.UpdateConfig;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：本机报工服务器控制台。
 *  - 首次启动把内置 web 程序解压到可写目录，然后启动后台服务（PHP）。
 *  - WebView 直接加载 http://127.0.0.1:8080 的本机应用。
 *  - 热更新：菜单「导入文件（可多选）」/「导入整个文件夹」/「检查服务端更新(GitHub)」
 *    三种方式覆盖 public/；DocumentsProvider 仍挂载供外部只读浏览。
 *    菜单可查看状态、重启 PHP、导出日志。
 */
public class MainActivity extends Activity implements ServerService.StateListener {

    private static final String PREFS = "bg_server";
    private static final int REQ_PICK = 1001;
    private static final int REQ_DL_PERM = 1002;
    private static final int REQ_IMPORT = 1003;      // 热更新导入文件（可多选）
    private static final int REQ_IMPORT_TREE = 1004; // 热更新导入整个文件夹

    private WebView webView;
    private SharedPreferences prefs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean updating = false;

    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private File cameraFile;
    private String pendingUrl, pendingUserAgent, pendingDisposition, pendingMime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // 初始化诊断日志（每次冷启动写一次头，记录设备/系统信息）
        com.baogong.server.DiagLog.header(this);

        // v2.1：WebView 占满全屏，不再显示顶部控制台（原状态/说明/按钮全部收进右上角菜单）
        webView = new WebView(this);
        configureWebView();
        setContentView(webView);
        setTitle("报工服务器");

        ServerService.setListener(this);

        // 安装程序文件 → 启动服务
        status("正在安装/检查程序文件…");
        AssetInstaller.install(this, (ok, message) -> {
            if (!ok) {
                status("安装失败：" + message);
                return;
            }
            status(message + "，正在启动服务…");
            ServerService.start(this);
            webView.loadUrl(Config.LOCAL_URL);
        });
    }

    /** v2.1：顶栏已移除，状态改用短暂 Toast 提示，不再常驻占屏。 */
    private void status(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** 打开系统文件选择器，可多选 php/zip/js 等文件热更新（替代不可靠的文件管理器粘贴）。 */
    private void pickImportFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); // 支持一次选多个文件
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要导入的文件（可多选，zip 会自动解压）"),
                    REQ_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开系统目录选择器，整包导入一个文件夹（兼容小米等 ROM 无法粘贴目录的情况）。 */
    private void pickImportFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // 授予持久读权限，避免某些 ROM 回调后 Uri 失效
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要导入的文件夹（将覆盖 public/ 下同名文件）"),
                    REQ_IMPORT_TREE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的文件夹选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 导入完成后的处理：提示结果并刷新 WebView。 */
    private void handleImportResult(HotUpdateImporter.Result r) {
        Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
        if (r.ok) {
            status("热更新成功：" + r.message + "\n正在重新加载页面…");
            webView.reload();
        }
    }

    @Override
    public void onState(String php, String webdav, String ip) {
        status("PHP：" + php);
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE); // 热更新后不读旧缓存
        s.setTextZoom(100);

        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                // v2.1：本机 PDF 图纸拦截下来交给系统 PDF 阅读器（WebView 自身无法渲染 PDF，
                // 否则会落到 DownloadListener 变成"只能下载"）。
                if ("http".equals(scheme) && isLocal(uri) && isPdfUrl(uri)) {
                    openPdfExternally(uri.toString());
                    return true;
                }
                // 本机应用内部跳转直接加载；外部链接交给系统浏览器
                if ("http".equals(scheme) && isLocal(uri)) {
                    return false;
                }
                if ("https".equals(scheme) || "about".equals(scheme)
                        || "data".equals(scheme) || "blob".equals(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "无法连接本机服务，请确认服务已启动", Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(buildFileChooserIntent(params), REQ_PICK);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingUrl = url;
                pendingUserAgent = userAgent;
                pendingDisposition = contentDisposition;
                pendingMime = mimeType;
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_DL_PERM);
                return;
            }
            enqueueDownload(url, userAgent, contentDisposition, mimeType);
        });
    }

    private boolean isLocal(Uri uri) {
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    /** 判断是否为图纸 PDF 请求：/img/... 路径且以 .pdf 结尾（忽略查询串大小写）。 */
    private boolean isPdfUrl(Uri uri) {
        String path = uri.getPath();
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        return lower.endsWith(".pdf");
    }

    /**
     * v2.1：把本机 PDF 图纸落到 App 私有目录，再用 FileProvider 交给系统 PDF 阅读器打开。
     * 不能把 http://127.0.0.1:8080/... 直接交给外部 App——它没有 WebView 的登录 Cookie，
     * 且回环地址对外部应用无意义，必须先带 Cookie 下载成本地文件再分享。
     * 下载放到子线程，避免阻塞 UI；完成后回主线程启动 Intent。
     */
    private void openPdfExternally(String url) {
        new Thread(() -> {
            File out = null;
            try {
                File dir = new File(Config.root(this), "tmp/pdf");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IOException("无法创建临时目录");
                }
                String name = "pdf_" + System.currentTimeMillis() + ".pdf";
                out = new File(dir, name);

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestMethod("GET");
                    // 带上 WebView 的登录 Cookie，否则 PHP 会跳登录页（拿到的是 HTML 不是 PDF）
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) {
                        conn.setRequestProperty("Cookie", cookie);
                    }
                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new IOException("HTTP " + code);
                    }
                    try (InputStream in = conn.getInputStream();
                         OutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n);
                        }
                    }
                } finally {
                    conn.disconnect();
                }

                File finalOut = out;
                runOnUiThread(() -> launchPdfViewer(finalOut));
            } catch (Exception e) {
                File toDelete = out;
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    if (toDelete != null) {
                        toDelete.delete();
                    }
                    Toast.makeText(MainActivity.this, "打开 PDF 失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        }, "bg-pdf-open").start();
    }

    /** 用系统 PDF 应用打开落地后的本地 PDF；没装阅读器时给出友好提示。 */
    private void launchPdfViewer(File pdf) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", pdf);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("", uri));
            startActivity(Intent.createChooser(intent, "打开 PDF 图纸"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "设备上没有安装可以打开 PDF 的应用", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "打开 PDF 失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Intent buildFileChooserIntent(WebChromeClient.FileChooserParams params) {
        // v2.2 修复：真机（国产 ROM）上 ACTION_GET_CONTENT + EXTRA_MIME_TYPES 仍只显示
        // 「拍照 / 照片和视频」，文件管理器入口被折叠，导致 PDF 选不到。
        // 改为：主入口用 ACTION_OPEN_DOCUMENT（系统 SAF 文件选择器，必显「文件」App），
        // 再把网页 accept 声明的类型（保底含图片与 PDF）作为 EXTRA_MIME_TYPES 过滤；
        // 相机作为附加快捷入口。这样图片/PDF/拍照三条路都通，且不依赖网页是否写对 accept。
        List<String> mimeTypes = new ArrayList<>();
        String[] accepts = params.getAcceptTypes();
        if (accepts != null) {
            for (String a : accepts) {
                if (a != null && !a.trim().isEmpty()) {
                    for (String part : a.split(",")) {
                        String t = part.trim();
                        if (!t.isEmpty() && !mimeTypes.contains(t)) {
                            mimeTypes.add(t);
                        }
                    }
                }
            }
        }
        String lowerAll = mimeTypes.toString().toLowerCase(Locale.US);
        // 网页未声明任何类型时，图纸场景按「图片+PDF」兜底
        if (mimeTypes.isEmpty()) {
            mimeTypes.add("image/*");
            mimeTypes.add("application/pdf");
            lowerAll = mimeTypes.toString().toLowerCase(Locale.US);
        }
        // 是否允许 PDF：网页 accept 明确含 pdf，或未声明类型（走保底）。
        // 纯图片上传（accept 仅 image/*）不追加 pdf，保留相机拍照入口。
        boolean wantsPdf = lowerAll.contains("pdf") || lowerAll.contains("*/*");

        Intent openDoc = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDoc.addCategory(Intent.CATEGORY_OPENABLE);
        openDoc.setType("*/*");
        if (!mimeTypes.isEmpty()) {
            openDoc.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
        }
        openDoc.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            openDoc.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }

        if (wantsPdf) {
            // 图纸上传：直达系统文件选择器（SAF DocumentsUI），必经「浏览/内部存储/Download」，
            // 能稳定选中 PDF；不套 createChooser，避免国产 ROM 把「文件」入口折叠成只有相册。
            return openDoc;
        }

        // 纯图片上传（如零件主图）：保持 chooser + 相机快捷，拍照体验不变
        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType("image/*");
        if (params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            content.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        Intent chooser = Intent.createChooser(content, "选择图片");
        Intent camera = buildCameraIntent();
        if (camera != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        return chooser;
    }

    private Intent buildCameraIntent() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            cameraFile = File.createTempFile("shot_", ".jpg", dir);
            cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("", cameraUri));
            return intent;
        } catch (IOException e) {
            cameraUri = null;
            cameraFile = null;
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_IMPORT) {
            if (resultCode == RESULT_OK && data != null) {
                final java.util.List<Uri> uris = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        uris.add(clip.getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (!uris.isEmpty()) {
                    Toast.makeText(this, "正在导入 " + uris.size() + " 个文件…", Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        final HotUpdateImporter.Result r = HotUpdateImporter.importMultiple(this, uris);
                        uiHandler.post(() -> handleImportResult(r));
                    }).start();
                }
            }
            return;
        }
        if (requestCode == REQ_IMPORT_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                // 持久化目录访问权限（部分 ROM 需要，拒绝也不影响本次导入）
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                Toast.makeText(this, "正在导入文件夹…", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    final HotUpdateImporter.Result r =
                            HotUpdateImporter.importTree(this, treeUri, Config.webRoot(this));
                    uiHandler.post(() -> handleImportResult(r));
                }).start();
            }
            return;
        }
        if (requestCode != REQ_PICK) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Uri[] results = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clip = data.getClipData();
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            } else if (cameraUri != null) {
                results = new Uri[]{cameraUri};
            }
        }
        if (results == null && cameraFile != null) {
            //noinspection ResultOfMethodCallIgnored
            cameraFile.delete();
        }
        if (fileCallback != null) {
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
        cameraUri = null;
        cameraFile = null;
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mime) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            request.setTitle(fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                Toast.makeText(this, "已开始下载：" + fileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_DL_PERM && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            enqueueDownload(pendingUrl, pendingUserAgent, pendingDisposition, pendingMime);
        }
        pendingUrl = pendingUserAgent = pendingDisposition = pendingMime = null;
    }

    // 右上角菜单项 id
    private static final int MENU_SETTINGS = 1;
    private static final int MENU_IMPORT = 2;
    private static final int MENU_RESTART = 3;
    private static final int MENU_RELOAD = 4;
    private static final int MENU_STATUS = 5;
    private static final int MENU_HOTUPDATE_HELP = 6;
    private static final int MENU_EXPORT_LOG = 7;
    private static final int MENU_CHECK_UPDATE = 8;
    private static final int MENU_IMPORT_FOLDER = 9;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // v2.1：原来顶部的所有操作 + 新增「设置」入口，全部收进右上角 ⋮ 菜单
        menu.add(0, MENU_SETTINGS, 0, "设置");
        menu.add(0, MENU_IMPORT, 1, "导入文件（可多选）");
        menu.add(0, MENU_IMPORT_FOLDER, 2, "导入整个文件夹");
        menu.add(0, MENU_RESTART, 3, "重启 PHP 服务");
        menu.add(0, MENU_RELOAD, 4, "重新加载页面");
        menu.add(0, MENU_STATUS, 5, "服务器状态");
        menu.add(0, MENU_HOTUPDATE_HELP, 6, "热更新说明");
        menu.add(0, MENU_EXPORT_LOG, 7, "导出诊断日志");
        menu.add(0, MENU_CHECK_UPDATE, 8, "检查服务端更新");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SETTINGS:
                showSettings();
                return true;
            case MENU_IMPORT:
                pickImportFile();
                return true;
            case MENU_IMPORT_FOLDER:
                pickImportFolder();
                return true;
            case MENU_RESTART:
                ServerService.start(this);
                Intent i = new Intent(this, ServerService.class);
                i.setAction(ServerService.ACTION_RESTART_PHP);
                startService(i);
                Toast.makeText(this, "正在重启 PHP 服务", Toast.LENGTH_SHORT).show();
                return true;
            case MENU_RELOAD:
                webView.reload();
                return true;
            case MENU_STATUS:
                new AlertDialog.Builder(this).setTitle("服务器状态")
                        .setMessage("PHP：" + phpText()
                                + "\n\nWeb 程序版本：" + Config.BUNDLED_WEB_VERSION
                                + "\nAPK 版本：" + Config.APP_VERSION)
                        .setPositiveButton("知道了", null).show();
                return true;
            case MENU_HOTUPDATE_HELP:
                new AlertDialog.Builder(this).setTitle("如何热更新服务端")
                        .setMessage("方式一 · 导入文件（推荐）：菜单「导入文件（可多选）」，一次选多个 php/js/css 文件，"
                                + "或选 zip 整包，会自动覆盖到 public/。\n\n"
                                + "方式二 · 导入文件夹：菜单「导入整个文件夹」，选中解压后的 public 文件夹，"
                                + "里面所有内容按原目录结构覆盖到 public/（适合整包替换）。\n\n"
                                + "方式三 · 检查服务端更新：从 GitHub 仓库拉取已发布的 web-v* 版本，可选任意版本。\n\n"
                                + "完成后 App 会自动刷新页面。PHP 每次请求重新加载脚本，改动立即生效，无需重装 APK。\n\n"
                                + "说明：小米等手机自带的文件管理器无法向「报工服务器」目录粘贴文件夹（系统限制），"
                                + "请优先用上面的「导入文件/导入文件夹」。data/ 是数据库与图片，不会被覆盖。")
                        .setPositiveButton("知道了", null).show();
                return true;
            case MENU_EXPORT_LOG:
                exportDiagnosticLog();
                return true;
            case MENU_CHECK_UPDATE:
                checkServerUpdate();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /** v2.1 新增「设置」：以对话框集中展示配置项与快捷入口。
     *  Web 识别插件等设置请在页面内的设置界面操作；这里放 App 级设置与说明。 */
    private void showSettings() {
        String updateStatus = "GitHub " + UpdateConfig.getRepoLabel(this)
                + (UpdateConfig.getToken(this).isEmpty() ? "（匿名）" : "（带令牌）");
        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setMessage("服务器地址：" + phpText() + "\n"
                        + "登录密码：在 app.env 的 APP_PASSWORD 中设置\n\n"
                        + "仓库热更新：" + updateStatus + "\n\n"
                        + "· 服务器状态、热更新、重启 PHP、导出日志等操作，见右上角菜单。\n"
                        + "· 业务与插件设置请进入报工页面内的「设置」界面操作。")
                .setPositiveButton("打开页面设置", (d, w) ->
                        webView.loadUrl(Config.LOCAL_URL + "#/settings"))
                .setNeutralButton("服务端更新配置", (d, w) -> showUpdateConfig())
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 服务端更新配置：运行时填写 GitHub 仓库/令牌，保存到私有存储，随时可改。 */
    private void showUpdateConfig() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        final EditText ownerInput = new EditText(this);
        ownerInput.setHint("仓库所有者（默认 c1as）");
        ownerInput.setText(UpdateConfig.getOwner(this));
        box.addView(ownerInput);

        final EditText repoInput = new EditText(this);
        repoInput.setHint("仓库名（默认 baogong-system）");
        repoInput.setText(UpdateConfig.getRepo(this));
        box.addView(repoInput);

        final EditText tokenInput = new EditText(this);
        tokenInput.setHint("令牌（可选，公开仓库留空即可）");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(UpdateConfig.getToken(this));
        box.addView(tokenInput);

        new AlertDialog.Builder(this)
                .setTitle("服务端更新配置（GitHub）")
                .setMessage("用菜单「检查服务端更新」从 GitHub Releases 拉取服务端程序，可选任意版本。"
                        + "\n\n公开仓库无需令牌即可使用；留空即匿名访问。"
                        + "如需提高限额或访问私有仓库，可填 GitHub 令牌（勾 public_repo / repo 只读）。"
                        + "\n令牌只保存在本机 App 内，不会打包进安装包，可随时回来修改。")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    UpdateConfig.save(this,
                            UpdateConfig.DEFAULT_API_BASE,
                            ownerInput.getText().toString(),
                            repoInput.getText().toString(),
                            tokenInput.getText().toString());
                    Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 检查服务端更新：后台拉取所有可下载版本 → 弹出列表供选择（可回退） → 下载覆盖。 */
    private void checkServerUpdate() {
        if (!UpdateConfig.isConfigured(this)) {
            Toast.makeText(this, "请先在「设置 → 服务端更新配置」填写 GitHub 仓库", Toast.LENGTH_LONG).show();
            showUpdateConfig();
            return;
        }
        if (updating) {
            Toast.makeText(this, "正在更新中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在获取版本列表…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                final java.util.List<GitHubReleaseClient.Release> releases =
                        GitHubReleaseClient.fetchReleases(this);
                uiHandler.post(() -> showVersionPicker(releases));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                uiHandler.post(() -> new AlertDialog.Builder(this).setTitle("获取版本失败")
                        .setMessage(msg)
                        .setPositiveButton("知道了", null).show());
            }
        }).start();
    }

    /** 弹出版本选择列表：标注当前已装版本与最新，点击任一版本即下载覆盖（可回退旧版）。 */
    private void showVersionPicker(final java.util.List<GitHubReleaseClient.Release> releases) {
        String installed = UpdateConfig.getInstalledWebVersion(this);
        final String[] labels = new String[releases.size()];
        for (int i = 0; i < releases.size(); i++) {
            GitHubReleaseClient.Release r = releases.get(i);
            StringBuilder sb = new StringBuilder(r.tag);
            if (r.latest) {
                sb.append("  [最新]");
            }
            if (r.tag.equalsIgnoreCase(installed)) {
                sb.append("  [当前已装]");
            }
            if (!r.name.isEmpty() && !r.name.equals(r.tag)) {
                sb.append("\n").append(r.name);
            }
            if (r.size > 0) {
                sb.append("  (").append(r.size / 1024).append(" KB)");
            }
            labels[i] = sb.toString();
        }
        new AlertDialog.Builder(this)
                .setTitle("选择服务端版本（当前：" + (installed.isEmpty() ? "未知" : installed) + "）")
                .setItems(labels, (d, which) -> {
                    GitHubReleaseClient.Release chosen = releases.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("使用 " + chosen.tag + " ?")
                            .setMessage("将下载 " + chosen.tag + " 覆盖服务端程序（public/）。"
                                    + "\n你的业务数据（data/）不会丢失。"
                                    + (chosen.tag.equalsIgnoreCase(installed)
                                        ? "\n\n这是当前已装版本，仍会重新安装。"
                                        : ""))
                            .setPositiveButton("下载安装", (dd, ww) -> applyServerUpdate(chosen))
                            .setNegativeButton("取消", null)
                            .show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 执行下载覆盖（后台线程 + 主线程进度提示）。 */
    private void applyServerUpdate(GitHubReleaseClient.Release release) {
        updating = true;
        Toast.makeText(this, "正在下载更新包…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            RepoUpdater.UpdateResult result = RepoUpdater.apply(this, release, pct -> {
                // 进度回调在下载线程，切到主线程更新（这里用轻量 Toast 节流由客户端控制）
                if (pct == 100) {
                    uiHandler.post(() -> Toast.makeText(this, "下载完成，正在安装…", Toast.LENGTH_SHORT).show());
                }
            });
            updating = false;
            uiHandler.post(() -> {
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                if (result.ok) {
                    // 更新成功：重启 PHP + 刷新页面
                    ServerService.start(this);
                    Intent i = new Intent(this, ServerService.class);
                    i.setAction(ServerService.ACTION_RESTART_PHP);
                    startService(i);
                    uiHandler.postDelayed(() -> webView.reload(), 1200);
                }
            });
        }).start();
    }

    /** 导出诊断日志：把启动日志与 PHP 错误日志合并，通过系统分享发给开发者。 */
    private void exportDiagnosticLog() {
        try {
            StringBuilder sb = new StringBuilder();
            File diag = com.baogong.server.DiagLog.file(this);
            if (diag.exists()) {
                sb.append(readText(diag)).append("\n");
            }
            File phpErr = new File(Config.phpDir(this), "php-error.log");
            if (phpErr.exists()) {
                sb.append("\n===== php-error.log =====\n").append(readText(phpErr));
            }
            if (sb.length() == 0) {
                sb.append("（暂无日志内容）");
            }
            // 写入可分享文件（cache 目录，已在 file_paths.xml 的 files-path 覆盖范围内无需额外配置；
            // 这里放到 baogong 根下的 logs 子目录，属于 bg_root）
            File outDir = new File(Config.root(this), "logs");
            if (!outDir.exists()) {
                outDir.mkdirs();
            }
            File out = new File(outDir, "diagnostic-report.txt");
            java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(out, false), "UTF-8");
            try {
                w.write(sb.toString());
            } finally {
                w.close();
            }

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "报工服务器诊断日志");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "发送诊断日志（可发给开发者）"));
        } catch (Exception e) {
            Toast.makeText(this, "导出日志失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String readText(File f) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String line;
            int count = 0;
            while ((line = r.readLine()) != null && count++ < 3000) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private String phpText() {
        return "http://127.0.0.1:" + Config.PHP_PORT;
    }

    @Override
    public void onBackPressed() {
        // 应用所有页面都在本机域内，直接交给 WebView 回退历史
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerService.setListener(this);
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
