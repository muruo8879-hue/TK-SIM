# TK SIM

[简体中文说明](README.zh-CN.md)

An offline Android manager that embeds Xposed modules into the user's own TikTok APK with [LSPatch](https://github.com/JingMatrix/LSPatch/tree/v1.2). The patched TikTok app keeps its original package name and, when the same private signing key is used, can be updated in place without clearing app data.

**This repository does not include TikTok APKs or a signing private key.** Use an official TikTok install on your own device and bring your own signing key. Do not publish an APK set extracted from TikTok.

## What it does

The manager bundles two modules:

- **TikTokSIMSpoof v1.0.0** hooks selected `TelephonyManager` and `SubscriptionManager` Java APIs in the patched TikTok process. In the configured hide mode, methods report an absent SIM, empty carrier identifiers, or an empty subscription list. It does not disable the physical SIM.
- **Google web-login compatibility** is a small local Xposed module. It hooks TikTok's internal `com.bytedance.lobby.google.GoogleAuth.isAvailable()` method and returns `false`; in the tested TikTok 47.6.3 build, this selected TikTok's own Google web sign-in fallback. A read-only SIM API check runs at startup and logs only whether the tested APIs appeared hidden. It does not log carrier, country, or subscription values.

To update, import a module APK and tap **Update current TikTok's modules**. The manager repatches the installed TikTok locally and sends the result to Android's package installer. Modules are embedded in the patched APK, not hot-loaded by the manager. No root, Shizuku, network access, update service, or TikTok download is needed. The manager requests only `REQUEST_INSTALL_PACKAGES` and has no `INTERNET` permission.

## How patching works

1. Read the installed TikTok base APK and split APKs, or import a complete set supplied by the user.
2. Verify the APK signatures, package name, split names, and version. If the input is already patched, recover its retained original APK and verify TikTok's signer before repatching.
3. Validate both module package names and signer certificates. This build accepts only the SIM module and the local Google module.
4. Use the embedded, hash-pinned LSPatch v1.2 engine in embedded-module mode. LSPatch inserts its loader and runtime resources into the app and embeds the two module APKs. LSPatch's runtime uses its LSPosed/LSPlant-based hook engine.
5. Sign every output split with the user's existing key, set the patched app's display label to `TikTok`, verify all outputs, and ask Android to install them as one package session.

Android will preserve app data only if package name, signer, version direction, and the app's own data migration rules permit an update. The signing key belongs to the owner. It must be backed up securely and must never be committed or distributed. The public certificate fingerprint in `ApkPolicy.java` is not the private key.

The SIM hook is limited to the Java methods covered by that module. It does not hide IP address, account region, location, existing TikTok data, or queries made through unhooked native or Binder paths. Compatibility can change with TikTok and Android releases. Google login was verified only on TikTok 47.6.3.

## Upstream projects and attribution

- [JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch/tree/v1.2) — APK rewriting, embedded loader, and module runtime; upstream release v1.2, build 487, commit `0dc50f42503711b14f5e2bb217c2bdd6321ce5be`. GPL-3.0. Its source archive is in `third-party/`; upstream git submodules and their licenses remain separate.
- [NZM7973/TikTokSIMSpoof](https://github.com/NZM7973/TikTokSIMSpoof/tree/v1.0.0) — embedded SIM module v1.0.0, commit `f08cc9292b52c7b3ac0b4a439ce4bf2d19c4af0a`. MIT. License text is in `third-party/TikTokSIMSpoof-LICENSE`.
- [rovo89/XposedBridge](https://github.com/rovo89/XposedBridge) — legacy `de.robv.android.xposed` API used by both modules. The repository is archived; its license and notices apply to that API.
- [Android apksig](https://android.googlesource.com/platform/tools/apksig) and [apkzlib](https://android.googlesource.com/platform/tools/apkzlib) — Android APK signature verification and archive repacking dependencies used by the patch engine.
- `modules/google-web-login/` — this repository's local module source. Its readback output is deliberately reduced to one boolean so a failed hook does not put the SIM's carrier data into logcat.

The Android manager and local Google module are distributed under GPL-3.0; see `LICENSE`. Each bundled upstream component keeps its own license. See `third-party/LSPatch-v1.2-source.zip`, the `LSPatch` upstream `.gitmodules`, and the upstream license files when preparing a public source release.

## Build

Requirements: Python 3, JDK 21, Android SDK platform 36 and build-tools 37.0.0. Set `JAVA_HOME` or pass `--jdk`; `ANDROID_HOME` or `ANDROID_SDK_ROOT` can select the SDK.

Build the manager with a key you control:

```text
python build.py --sdk <Android-SDK> --jdk <JDK> --keystore <your-key.p12> --password-file <password-file> --output <TK-SIM-Manager.apk>
```

The manager pins the patched TikTok signer and the local Google module signer to the certificate configured in `ApkPolicy.LOCAL`. A fork must generate its own key, rebuild and sign the local Google module with that key, then set the fork's public certificate digest there. Never add the private key or password to the repository or a release asset.

This first source release preserves a single-device prototype's certificate pin. It does not generate keys or accept arbitrary keys at runtime. To use your own key, update `ApkPolicy.LOCAL`, rebuild `assets/modules/google-login.apk` with that key (alias `sim-test`), build the manager, and import the PKCS12 key/password through the manager. The prebuilt Google module is signed by the original prototype author.

`--debug-setup` exists only for the initial private-key transfer workflow; distribute the normal build with `debuggable=false`. Do not publish a debug APK.

Build the local Google module separately with `modules/google-web-login/build.ps1`, providing the same key and alias `sim-test`. It depends on the Xposed API 82 jar available from [api.xposed.info](https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar).

To run the small policy check, compile `tests/PolicyTest.java` together with `ApkPolicy.java` against the LSPatch release jar. It uses real TikTok APK fixtures, which are intentionally not included in this repository.

## Version notes

- Manager 1.0.1 is named **TK SIM**.
- TikTok generated by this manager is named **TikTok**.
- Google module 1.0.1 avoids logging SIM identifiers during its readback check.
- Existing phone setup was tested with TikTok 47.6.3. This source and the bundled modules are not a claim of compatibility with every TikTok release.
