package local.codex.tiktokmanager;

import com.android.apksig.ApkVerifier;
import pxb.android.axml.AxmlParser;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Fail closed before importing executable code or replacing an installed app. */
public final class ApkPolicy {
    public static final String TARGET = "com.zhiliaoapp.musically";
    public static final String OFFICIAL = "9041803e91bcb814b4b4399fb5c85a91640b755e5e8ba76813814bf4cf2ab5ba";
    public static final String LOCAL = "7f8a919bd9f545d9880b81c3d23342530c52182d23aebff87efb95213e3ab6e8";
    public static final String SIM_PACKAGE = "com.tiktokbypass.simspoof";
    public static final String GOOGLE_PACKAGE = "local.codex.tiktokgooglefix";
    public static final String SIM_SIGNER = "ac9628db9e080ce41058ad42456d00d4285024a10c4bd7de860dec843806995c";
    public static final long MAX_APK = 1200L * 1024 * 1024;
    public static final long MAX_SET = 3L * 1024 * 1024 * 1024;

    public static final class Info {
        public String packageName, split = "", versionName = "";
        public long version;
    }
    public static String digest(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static String signer(File file) throws Exception {
        ApkVerifier.Result result = new ApkVerifier.Builder(file).setMinCheckedPlatformVersion(28).setMaxCheckedPlatformVersion(37).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().size() != 1) throw new IOException("安装包签名验证失败：" + file.getName());
        return digest(result.getSignerCertificates().get(0).getEncoded());
    }
    public static String fileHash(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[128 * 1024]; int n;
            while ((n = in.read(bytes)) != -1) hash.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    public static Info info(File apk) throws Exception {
        try (ZipFile z = new ZipFile(apk)) {
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) throw new IOException("不是有效的 APK");
            byte[] bytes;
            try (InputStream in = z.getInputStream(e)) {
                bytes = in.readNBytes(4 * 1024 * 1024 + 1);
            }
            if (bytes.length > 4 * 1024 * 1024) throw new IOException("安装包清单过大");
            AxmlParser p = new AxmlParser(bytes);
            Info result = new Info();
            long major = 0, minor = 0;
            int event;
            while ((event = p.next()) != AxmlParser.END_FILE) {
                if (event != AxmlParser.START_TAG || !"manifest".equals(p.getName())) continue;
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    Object value = p.getAttrValue(i);
                    String name = p.getAttrName(i);
                    if ("package".equals(name)) result.packageName = value.toString();
                    if ("split".equals(name)) result.split = value.toString();
                    if ("versionName".equals(name)) result.versionName = value.toString();
                    if ("versionCode".equals(name)) minor = ((Number)value).longValue() & 0xffffffffL;
                    if ("versionCodeMajor".equals(name)) major = ((Number)value).longValue();
                }
                break;
            }
            result.version = (major << 32) | minor;
            if (result.packageName == null || !result.split.matches("[a-zA-Z0-9_.-]*")) throw new IOException("安装包标识无效");
            return result;
        }
    }
    public static void module(File file, String expectedPackage) throws Exception {
        Info info = info(file);
        if (!expectedPackage.equals(info.packageName)) throw new IOException("模块包名不匹配");
        String expected = SIM_PACKAGE.equals(expectedPackage) ? SIM_SIGNER : LOCAL;
        if (!expected.equals(signer(file))) throw new IOException("模块签名与内置可信版本不同，已拒绝导入");
        try (ZipFile z = new ZipFile(file)) {
            if (z.getEntry("assets/xposed_init") == null) throw new IOException("缺少模块入口");
        }
    }
    public static long copy(InputStream in, File out, long limit) throws IOException {
        long size = 0;
        try (OutputStream stream = new FileOutputStream(out)) {
            byte[] buffer = new byte[128 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                size += n;
                if (size > limit) throw new IOException("文件超过大小限制");
                stream.write(buffer, 0, n);
            }
        }
        return size;
    }
    public static File original(File input, File folder, int index) throws Exception {
        String signature = signer(input);
        File raw = new File(folder, "raw-" + index + ".apk");
        if (OFFICIAL.equals(signature)) {
            try (InputStream in = new FileInputStream(input)) { copy(in, raw, MAX_APK); }
        } else if (LOCAL.equals(signature)) {
            try (ZipFile z = new ZipFile(input)) {
                ZipEntry e = z.getEntry("assets/lspatch/origin.apk");
                if (e == null) throw new IOException("该修补包没有保留官方原始包");
                try (InputStream in = z.getInputStream(e)) { copy(in, raw, MAX_APK); }
            }
        } else throw new IOException("仅接受官方 TikTok 或本管理器签名的修补包");
        if (!OFFICIAL.equals(signer(raw))) throw new IOException("恢复出的原始包不是已验证的 TikTok 官方签名");
        Info info = info(raw);
        if (!TARGET.equals(info.packageName)) throw new IOException("只支持 TikTok 国际版 com.zhiliaoapp.musically");
        File normalized = new File(folder, info.split.isEmpty() ? "base.apk" : "split_" + info.split + ".apk");
        if (normalized.exists()) throw new IOException("重复的 APK 分包：" + info.split);
        Files.move(raw.toPath(), normalized.toPath());
        return normalized;
    }
    public static long validateSet(List<File> files) throws Exception {
        if (files.isEmpty() || files.size() > 64) throw new IOException("请选择完整的 TikTok 安装包，最多 64 个分包");
        long version = -1, bytes = 0;
        int bases = 0;
        Set<String> names = new HashSet<>();
        for (File file : files) {
            Info info = info(file);
            if (!TARGET.equals(info.packageName)) throw new IOException("安装包包含其他应用");
            if (info.split.isEmpty()) bases++;
            if (!names.add(info.split)) throw new IOException("分包重复");
            if (version == -1) version = info.version;
            if (version != info.version) throw new IOException("分包版本不同，请选择同一版本的完整安装包");
            bytes += file.length();
        }
        if (bases != 1 || bytes > MAX_SET) throw new IOException("缺少主安装包或文件总大小超限");
        return version;
    }
}
