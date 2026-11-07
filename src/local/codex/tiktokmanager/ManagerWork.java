package local.codex.tiktokmanager;

import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import org.lsposed.patch.*;
import org.lsposed.patch.util.Logger;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.zip.*;

public final class ManagerWork {
    interface Progress { void show(String text); }
    final Context context;
    final Progress progress;
    public ManagerWork(Context context, Progress progress) { this.context = context; this.progress = progress; }
    File modules() { return new File(context.getFilesDir(), "modules"); }
    File signing() { return new File(context.getFilesDir(), "signing"); }
    android.content.SharedPreferences prefs() { return context.getSharedPreferences("state", 0); }

    public void ensureModules() throws Exception {
        modules().mkdirs();
        String[] names = {"sim-hide.apk", "google-login.apk"};
        String[] packages = {ApkPolicy.SIM_PACKAGE, ApkPolicy.GOOGLE_PACKAGE};
        for (int i = 0; i < names.length; i++) {
            File bundled = new File(context.getCacheDir(), "bundled-" + names[i]);
            try (InputStream in = context.getAssets().open("modules/" + names[i])) { ApkPolicy.copy(in, bundled, 64L * 1024 * 1024); }
            ApkPolicy.module(bundled, packages[i]);
            File current = new File(modules(), names[i]);
            if (!current.exists() || ApkPolicy.info(bundled).version > ApkPolicy.info(current).version) {
                Files.move(bundled.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING);
                prefs().edit().remove("ready").remove("pendingHash")
                        .putString("last", "内置模块已就绪，请生成修补包应用模块").apply();
            } else Files.delete(bundled.toPath());
            ApkPolicy.module(current, packages[i]);
        }
    }
    public String moduleVersions() throws Exception {
        return "SIM 隐藏  " + ApkPolicy.info(new File(modules(), "sim-hide.apk")).versionName
                + "\nGoogle 网页登录  " + ApkPolicy.info(new File(modules(), "google-login.apk")).versionName;
    }
    private KeyStore readKey(File file, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(file)) { store.load(in, password.toCharArray()); }
        Certificate cert = store.getCertificate("sim-test");
        if (cert == null || !ApkPolicy.LOCAL.equals(ApkPolicy.digest(cert.getEncoded())) || store.getKey("sim-test", password.toCharArray()) == null) {
            throw new IOException("该签名文件不能延续当前 TikTok 的更新签名");
        }
        return store;
    }
    public boolean keyReady() {
        try {
            String pass = Files.readString(new File(signing(), "password.txt").toPath(), StandardCharsets.UTF_8);
            readKey(new File(signing(), "key.p12"), pass);
            return true;
        } catch (Exception failure) {
            android.util.Log.w("TKManager", "Signing configuration is unavailable", failure);
            return false;
        }
    }
    public void importKey(Uri uri, String password) throws Exception {
        File temporary = new File(context.getCacheDir(), "key-import.p12");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) { ApkPolicy.copy(in, temporary, 1024 * 1024); }
            readKey(temporary, password);
            signing().mkdirs();
            Files.move(temporary.toPath(), new File(signing(), "key.p12").toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(new File(signing(), "password.txt").toPath(), password, StandardCharsets.UTF_8);
        } finally { temporary.delete(); }
    }
    public String importModule(Uri uri) throws Exception {
        File temporary = new File(context.getCacheDir(), "module-import.apk");
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri)) { ApkPolicy.copy(in, temporary, 64L * 1024 * 1024); }
            ApkPolicy.Info info = ApkPolicy.info(temporary);
            String name;
            if (ApkPolicy.SIM_PACKAGE.equals(info.packageName)) name = "sim-hide.apk";
            else if (ApkPolicy.GOOGLE_PACKAGE.equals(info.packageName)) name = "google-login.apk";
            else throw new IOException("只接受内置的 SIM 隐藏或 Google 登录模块更新");
            ApkPolicy.module(temporary, info.packageName);
            File dest = new File(modules(), name);
            if (info.version < ApkPolicy.info(dest).version) throw new IOException("模块版本较旧，已拒绝降级");
            Files.move(temporary.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            String message = "模块已导入。点击“更新当前 TikTok 的模块”使其生效。";
            prefs().edit().remove("ready").remove("pendingHash").putString("last", message).apply();
            return message;
        } finally { temporary.delete(); }
    }
    private void clearJob(File file, String root) throws IOException {
        if (!file.getCanonicalPath().startsWith(root + File.separator)) throw new IOException("无效的清理路径");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) clearJob(child, root);
        }
        if (file.exists() && !file.delete()) throw new IOException("无法清理旧的工作文件");
    }
    public List<File> build(List<Uri> selected) throws Exception {
        if (!keyReady()) throw new IOException("请先导入现有 TikTok 的签名文件");
        ensureModules();
        prefs().edit().remove("ready").remove("pendingHash").apply();
        File job = new File(context.getCacheDir(), "patch-job");
        if (job.exists()) clearJob(job, context.getCacheDir().getCanonicalPath());
        File inputs = new File(job, "inputs"), originals = new File(job, "originals"), outputs = new File(job, "outputs");
        inputs.mkdirs(); originals.mkdirs(); outputs.mkdirs();
        System.setProperty("java.io.tmpdir", job.getAbsolutePath());
        List<File> incoming = new ArrayList<>();
        if (selected == null) {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(ApkPolicy.TARGET, 0);
            incoming.add(new File(app.sourceDir));
            if (app.splitSourceDirs != null) for (String path : app.splitSourceDirs) incoming.add(new File(path));
        } else {
            int n = 0;
            long total = 0, expanded = 0;
            for (Uri uri : selected) {
                if (incoming.size() >= 64) throw new IOException("安装包分包数量超限");
                File source = new File(inputs, "selected-" + n++ + ".zip");
                try (InputStream in = context.getContentResolver().openInputStream(uri)) { total += ApkPolicy.copy(in, source, ApkPolicy.MAX_SET - total); }
                try (ZipFile zip = new ZipFile(source)) {
                    if (zip.getEntry("AndroidManifest.xml") != null) {
                        if (source.length() > ApkPolicy.MAX_APK) throw new IOException("APK 文件过大");
                        expanded += source.length();
                        if (expanded > ApkPolicy.MAX_SET) throw new IOException("安装包总大小超限");
                        incoming.add(source);
                    }
                    else {
                        Enumeration<? extends ZipEntry> entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry e = entries.nextElement();
                            if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                            if (incoming.size() >= 64) throw new IOException("安装包分包数量超限");
                            File apk = new File(inputs, "archive-" + n++ + ".apk");
                            try (InputStream in = zip.getInputStream(e)) { expanded += ApkPolicy.copy(in, apk, Math.min(ApkPolicy.MAX_APK, ApkPolicy.MAX_SET - expanded)); }
                            incoming.add(apk);
                        }
                    }
                }
            }
        }
        if (incoming.isEmpty() || incoming.size() > 64) throw new IOException("没有找到完整 APK 安装包");
        long bytes = 0;
        for (File file : incoming) bytes += file.length();
        if (bytes > ApkPolicy.MAX_SET || context.getCacheDir().getUsableSpace() < bytes * 3 + 150L * 1024 * 1024) throw new IOException("存储空间不足，请预留安装包大小三倍以上的空间");
        List<File> sourceSet = new ArrayList<>();
        for (int i = 0; i < incoming.size(); i++) {
            progress.show("验证官方签名并恢复原始包  " + (i + 1) + " / " + incoming.size());
            sourceSet.add(ApkPolicy.original(incoming.get(i), originals, i));
        }
        long version = ApkPolicy.validateSet(sourceSet);
        try {
            PackageInfo installed = context.getPackageManager().getPackageInfo(ApkPolicy.TARGET, PackageManager.GET_SIGNING_CERTIFICATES);
            if (version < installed.getLongVersionCode()) throw new IOException("所选 TikTok 版本比已安装版本旧，已停止");
        } catch (PackageManager.NameNotFoundException ignored) { }
        sourceSet.sort(Comparator.comparing(File::getName));
        String password = Files.readString(new File(signing(), "password.txt").toPath(), StandardCharsets.UTF_8);
        KeyStore originalKey = readKey(new File(signing(), "key.p12"), password);
        KeyStore engineKey = KeyStore.getInstance(KeyStore.getDefaultType());
        engineKey.load(null, password.toCharArray());
        engineKey.setKeyEntry("sim-test", originalKey.getKey("sim-test", password.toCharArray()), password.toCharArray(), originalKey.getCertificateChain("sim-test"));
        File converted = new File(job, "signing.runtime");
        try (OutputStream out = new FileOutputStream(converted)) { engineKey.store(out, password.toCharArray()); }
        Logger logger = new Logger() {
            public void d(String ignored) { }
            public void i(String ignored) { }
            public void e(String message) { android.util.Log.e("TKManager", message); }
            public void stage(Stage stage, int index, int total) {
                String label = switch (stage) {
                    case READING -> "读取安装包";
                    case SIGNING -> "签名安装包";
                    case PACKING_SPLIT -> "处理分包";
                    case REWRITING -> "更新应用配置";
                    case INJECTING -> "加入加载器";
                    case EMBEDDING -> "嵌入模块";
                    case WRITING -> "写入修补包";
                    case DONE -> "修补完成";
                };
                progress.show(label + "  " + index + " / " + total + "\n较大安装包可能需要数分钟，请保持本页面打开");
            }
        };
        PatchSpec spec = PatchSpec.builder().apks(sourceSet).outputDir(outputs).sigBypassLevel(2)
                .module(new File(modules(), "sim-hide.apk")).module(new File(modules(), "google-login.apk"))
                .keystore(KeystoreSpec.of(converted, password, "sim-test", password))
                .manifestOverrides(ManifestOverrides.builder().label("TikTok").build()).build();
        List<File> result;
        try { result = new ApkPatcher(logger, spec).patch(); }
        finally { converted.delete(); }
        for (int i = 0; i < result.size(); i++) {
            progress.show("核验生成的安装包  " + (i + 1) + " / " + result.size());
            if (!ApkPolicy.LOCAL.equals(ApkPolicy.signer(result.get(i)))) throw new IOException("输出签名不一致，已禁止安装");
        }
        if (ApkPolicy.validateSet(result) != version) throw new IOException("输出版本与输入不同");
        prefs().edit().putString("ready", outputs.getAbsolutePath()).putString("last", "生成完成 · " + result.size() + " 个分包，可保留现有数据更新").apply();
        return result;
    }
    public List<File> readyFiles() throws Exception {
        String path = prefs().getString("ready", "");
        if (path.isEmpty()) throw new IOException("请先生成修补包");
        File root = new File(path);
        if (!root.getCanonicalPath().startsWith(context.getCacheDir().getCanonicalPath() + File.separator)) throw new IOException("无效的安装包目录");
        File[] files = root.listFiles((dir, name) -> name.endsWith(".apk"));
        if (files == null || files.length == 0) throw new IOException("安装包缓存已清理，请重新生成");
        List<File> result = Arrays.asList(files);
        ApkPolicy.validateSet(result);
        return result;
    }
    public void noteInstall(List<File> files) throws Exception {
        for (File file : files) if (ApkPolicy.info(file).split.isEmpty()) {
            prefs().edit().putString("pendingHash", ApkPolicy.fileHash(file)).commit();
            return;
        }
        throw new IOException("缺少主安装包");
    }
    public String reconcileInstall() throws Exception {
        if (prefs().getString("ready", "").isEmpty()) return prefs().getString("last", "内置模块已就绪");
        String expected = prefs().getString("pendingHash", "");
        if (expected.isEmpty()) {
            try {
                for (File file : readyFiles()) if (ApkPolicy.info(file).split.isEmpty()) expected = ApkPolicy.fileHash(file);
            } catch (IOException unavailable) { return "生成的安装包缓存已清理，请重新生成"; }
        }
        if (!expected.isEmpty()) {
            try {
                PackageInfo p = context.getPackageManager().getPackageInfo(ApkPolicy.TARGET, PackageManager.GET_SIGNING_CERTIFICATES);
                boolean local = p.signingInfo != null && p.signingInfo.getApkContentsSigners().length == 1
                        && ApkPolicy.LOCAL.equals(ApkPolicy.digest(p.signingInfo.getApkContentsSigners()[0].toByteArray()));
                boolean match = expected.equals(ApkPolicy.fileHash(new File(p.applicationInfo.sourceDir)));
                android.util.Log.i("TKManager", "Install check: signer=" + local + ";base=" + match);
                if (local && match) {
                    String success = "当前已安装生成包 · TikTok 主包及签名已核对";
                    prefs().edit().putString("pendingHash", expected).putString("last", success).apply();
                    return success;
                }
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        return prefs().getString("last", "内置模块已就绪");
    }
}
