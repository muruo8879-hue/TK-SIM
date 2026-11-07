package local.codex.tiktokmanager;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Uses real signed APKs, including a tampered fixture. */
public final class PolicyTest {
    interface Checked { void run() throws Exception; }
    static int passed;
    static void rejects(Checked call) throws Exception {
        try { call.run(); } catch (Exception expected) { passed++; return; }
        throw new AssertionError("Untrusted input was accepted");
    }
    public static void main(String[] args) throws Exception {
        File fixtures = new File(args[0]), out = new File(args[1]);
        out.mkdirs();
        File base = new File(fixtures, "original/base.apk");
        if (!ApkPolicy.OFFICIAL.equals(ApkPolicy.signer(base))) throw new AssertionError("Official signer");
        if (!ApkPolicy.TARGET.equals(ApkPolicy.info(base).packageName)) throw new AssertionError("Manifest parser");
        passed += 2;
        File sim = new File(fixtures, "tools/TikTokSIMSpoof.apk");
        ApkPolicy.module(sim, ApkPolicy.SIM_PACKAGE); passed++;
        File login = new File(fixtures, "google-fix/google-web-login-fix.apk");
        ApkPolicy.module(login, ApkPolicy.GOOGLE_PACKAGE); passed++;
        rejects(() -> ApkPolicy.module(sim, ApkPolicy.GOOGLE_PACKAGE));
        rejects(() -> ApkPolicy.validateSet(Arrays.asList(base, sim)));
        rejects(() -> ApkPolicy.validateSet(Arrays.asList(base, base)));
        rejects(() -> ApkPolicy.original(sim, out, 0));
        File tampered = new File(out, "tampered.apk");
        Files.copy(login.toPath(), tampered.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile f = new RandomAccessFile(tampered, "rw")) {
            f.seek(100); int value = f.read(); f.seek(100); f.write(value ^ 1);
        }
        rejects(() -> ApkPolicy.module(tampered, ApkPolicy.GOOGLE_PACKAGE));
        tampered.delete();
        File nested = ApkPolicy.original(new File(fixtures, "patched-login/base-487-lspatched.apk"), out, 1);
        if (Files.mismatch(base.toPath(), nested.toPath()) != -1) throw new AssertionError("Original was changed");
        nested.delete(); passed++;
        System.out.println("PASS: " + passed + " signature, manifest, import, tampering and original-recovery checks");
    }
}
