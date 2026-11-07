package local.codex.tiktokmanager;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int FILE_TIKTOK = 1, FILE_MODULE = 2, FILE_KEY = 3;
    private final int ink = Color.rgb(25, 42, 36), green = Color.rgb(23, 102, 83);
    private LinearLayout content;
    private TextView appState, moduleState, status;
    private ProgressBar progress;
    private final List<Button> actions = new ArrayList<>();
    private Button build, importTikTok, install;
    private ManagerWork work;
    private boolean busy, resumeInstall;
    private interface Action { String run() throws Exception; }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        work = new ManagerWork(this, value -> runOnUiThread(() -> status.setText(value)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(24));
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(22) + bars.left, dp(18) + bars.top, dp(22) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        scroll.addView(content);
        setContentView(scroll);
        text(content, "TK / PRIVACY", 13, green, true);
        text(content, "TK SIM", 29, ink, true);
        text(content, "内置模块 · 手机本地处理 · 无需 Root", 14, Color.DKGRAY, false);

        LinearLayout device = card();
        text(device, "当前 TikTok", 18, ink, true);
        appState = text(device, "正在检测…", 14, Color.DKGRAY, false);
        build = button(device, "更新当前 TikTok 的模块", true, () -> task(() -> {
            work.build(null); return "修补包已生成。点击“安装生成的 TikTok”完成更新。";
        }));
        importTikTok = button(device, "导入新版 TikTok 安装包", false, () -> choose(FILE_TIKTOK));
        text(device, "支持 APK、APKM、XAPK、APKS，或同时选择一组 APK 分包。新版本需从官方可信来源取得。", 12, Color.DKGRAY, false);

        LinearLayout moduleCard = card();
        text(moduleCard, "内置保护", 18, ink, true);
        moduleState = text(moduleCard, "SIM 隐藏\nGoogle 网页登录兼容", 15, ink, false);
        text(moduleCard, "模块更新后，再点上方按钮应用到 TikTok。导入时会核对模块签名和包名。", 12, Color.DKGRAY, false);
        button(moduleCard, "导入模块更新", false, () -> choose(FILE_MODULE));

        LinearLayout result = card();
        text(result, "处理状态", 18, ink, true);
        status = text(result, "准备中…", 14, ink, false);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        result.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
        install = button(result, "安装生成的 TikTok", true, this::requestInstall);
        button(result, "打开 TikTok", false, () -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(ApkPolicy.TARGET);
            if (launch != null) startActivity(launch); else message("尚未安装 TikTok");
        });

        LinearLayout extra = card();
        button(extra, "导入已有签名文件", false, () -> choose(FILE_KEY));
        button(extra, "关于与使用说明", false, () -> new AlertDialog.Builder(this).setTitle("TK SIM 1.0.1")
                .setMessage("本机离线处理，不申请联网权限。\n\n基于 JingMatrix/LSPatch v1.2，内置 TikTokSIMSpoof v1.0.0 与本机 Google 网页登录兼容模块。\n\n模块更新会重新打包 TikTok，并沿用现有签名；不会自动卸载应用。处理时请保持此页面打开。\n\n此工具不提供 TikTok 安装包下载。模块只能从原作者或本地可信版本导入。后续 TikTok 版本可能需要重新验证兼容性。\n\n卸载管理器会删除其签名配置；恢复时需要本机保存的签名文件和密码。\n\nLSPatch: GPL-3.0\nTikTokSIMSpoof: MIT\n源码与许可随本机交付文件提供。")
                .setPositiveButton("知道了", null).show());
        task(() -> { work.ensureModules(); return work.reconcileInstall(); });
    }
    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(3), 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        parent.addView(view, params);
        return view;
    }
    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(18)); return shape;
    }
    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(20), dp(18), dp(12)); box.setBackground(shape(Color.WHITE));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(18); content.addView(box, params); return box;
    }
    private Button button(LinearLayout parent, String label, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : green);
        b.setBackground(shape(primary ? green : Color.rgb(231, 242, 235)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52)); params.bottomMargin = dp(12);
        parent.addView(b, params); actions.add(b); b.setOnClickListener(v -> { if (!busy) action.run(); }); return b;
    }
    private void message(String value) { status.setText(value); }
    private void task(Action action) {
        if (busy) return;
        busy = true; progress.setVisibility(View.VISIBLE);
        for (Button b : actions) b.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String result;
            try { result = action.run(); }
            catch (Throwable failure) {
                android.util.Log.e("TKManager", "Operation failed", failure);
                result = "操作未完成：" + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            }
            final String completed = result;
            runOnUiThread(() -> {
                busy = false; progress.setVisibility(View.GONE); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                refresh(); status.setText(completed);
            });
        }, "TK-local-worker").start();
    }
    private void refresh() {
        if (work == null || busy) return;
        for (Button b : actions) b.setEnabled(true);
        boolean installed = false;
        String version = "尚未安装 TikTok";
        try { PackageInfo p = getPackageManager().getPackageInfo(ApkPolicy.TARGET, 0); version = "版本 " + p.versionName; installed = true; }
        catch (PackageManager.NameNotFoundException ignored) { }
        boolean key = work.keyReady();
        appState.setText(version + "\n" + (key ? "更新签名已就绪 · 可延续现有数据" : "请先导入已有签名文件"));
        build.setEnabled(installed && key); importTikTok.setEnabled(key);
        try { moduleState.setText(work.moduleVersions()); } catch (Exception ignored) { }
        install.setEnabled(key && !work.prefs().getString("ready", "").isEmpty());
    }
    @Override public void onResume() {
        super.onResume(); refresh();
        if (resumeInstall) {
            resumeInstall = false;
            if (getPackageManager().canRequestPackageInstalls()) requestInstall();
        } else if (work != null && !busy && status != null) {
            if (!work.prefs().getString("ready", "").isEmpty()) task(work::reconcileInstall);
            else status.setText(work.prefs().getString("last", "内置模块已就绪"));
        }
    }
    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); refresh();
        if (!busy && !work.prefs().getString("ready", "").isEmpty()) task(work::reconcileInstall);
    }
    private void choose(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if (request == FILE_TIKTOK) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, request);
    }
    @Override public void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++) uris.add(intent.getClipData().getItemAt(i).getUri());
        else if (intent.getData() != null) uris.add(intent.getData());
        if (uris.isEmpty()) return;
        if (request == FILE_TIKTOK) task(() -> { work.build(uris); return "新版修补包已生成，点击安装完成更新。"; });
        if (request == FILE_MODULE) task(() -> work.importModule(uris.get(0)));
        if (request == FILE_KEY) {
            EditText password = new EditText(this); password.setSingleLine(true); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setHint("签名文件密码");
            new AlertDialog.Builder(this).setTitle("导入原签名").setMessage("仅接受与当前测试版一致的签名文件；密码只保存在此应用的私有存储中。")
                    .setView(password).setNegativeButton("取消", null).setPositiveButton("导入", (d, w) -> {
                        String secret = password.getText().toString(); password.setText("");
                        task(() -> { work.importKey(uris.get(0), secret); return "原签名已导入，可以保留数据更新。"; });
                    }).show();
        }
    }
    private void requestInstall() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            resumeInstall = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        task(() -> {
            List<File> files = work.readyFiles();
            long version = ApkPolicy.validateSet(files);
            try {
                PackageInfo current = getPackageManager().getPackageInfo(ApkPolicy.TARGET, PackageManager.GET_SIGNING_CERTIFICATES);
                if (current.signingInfo == null || current.signingInfo.getApkContentsSigners().length != 1
                        || !ApkPolicy.LOCAL.equals(ApkPolicy.digest(current.signingInfo.getApkContentsSigners()[0].toByteArray())))
                    throw new IOException("当前 TikTok 签名不同，无法保留数据覆盖；本工具不会自动卸载它");
                if (version < current.getLongVersionCode()) throw new IOException("不能覆盖安装更旧的版本");
            } catch (PackageManager.NameNotFoundException ignored) { }
            long total = 0;
            for (File file : files) {
                if (!ApkPolicy.LOCAL.equals(ApkPolicy.signer(file))) throw new IOException("安装包签名验证失败");
                total += file.length();
            }
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ApkPolicy.TARGET); params.setSize(total);
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            int id = installer.createSession(params);
            boolean committed = false;
            try (PackageInstaller.Session session = installer.openSession(id)) {
                for (File file : files) {
                    work.progress.show("准备系统安装：" + file.getName());
                    try (InputStream in = new FileInputStream(file); OutputStream out = session.openWrite(file.getName(), 0, file.length())) {
                        in.transferTo(out); session.fsync(out);
                    }
                }
                Intent callback = new Intent(this, InstallReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(this, id, callback, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                work.noteInstall(files);
                session.commit(pending.getIntentSender()); committed = true;
            } finally { if (!committed) installer.abandonSession(id); }
            return "已提交系统安装，请按屏幕提示确认。";
        });
    }
}
