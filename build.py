"""Build with the installed Android SDK and Java. No Gradle downloads or network.

Signing credentials are required arguments and are never included in the APK.
"""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
ENGINE_SHA = "d238fdc414d121b7fa454d8b4ccf420df3a8c97d563761861ff92bd9c5da2165"


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def main():
    parser = argparse.ArgumentParser()
    sdk_default = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_default:
        sdk_default = str(Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk") if os.name == "nt" else str(Path.home() / "Android/Sdk")
    parser.add_argument("--sdk", type=Path, default=Path(sdk_default))
    parser.add_argument("--jdk", type=Path, default=Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None, required=not bool(os.environ.get("JAVA_HOME")))
    parser.add_argument("--keystore", type=Path, required=True)
    parser.add_argument("--password-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--debug-setup", action="store_true")
    args = parser.parse_args()
    build = ROOT.parent / "work/tiktok-manager-build"
    build.mkdir(parents=True, exist_ok=True)
    classes = build / "classes"
    dex = build / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    # Remove only previous compiler outputs under the verified build directory.
    for folder, suffix in ((classes, ".class"), (dex, ".dex")):
        for path in folder.rglob("*" + suffix):
            assert path.resolve().is_relative_to(build.resolve())
            path.unlink()
    ext = ".exe" if os.name == "nt" else ""
    jbin = args.jdk / "bin"
    java = jbin / ("java" + ext)
    android = args.sdk / "platforms/android-36/android.jar"
    tools = args.sdk / "build-tools/37.0.0"
    engine = ROOT / "vendor/lspatch-v1.2-487.jar"
    assert hashlib.sha256(engine.read_bytes()).hexdigest() == ENGINE_SHA, "Unexpected patch engine"
    engine_classes = build / "engine-classes.jar"
    with zipfile.ZipFile(engine) as source, zipfile.ZipFile(engine_classes, "w", zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            if entry.filename.endswith(".class") and not entry.filename.startswith("META-INF/versions/"):
                target.writestr(entry.filename, source.read(entry))
    run([jbin / ("javac" + ext), "-encoding", "UTF-8", "-source", "17", "-target", "17", "-Xlint:-options",
         "-classpath", str(android) + os.pathsep + str(engine), "-d", classes, *ROOT.glob("src/**/*.java")])
    run([java, "-Xmx2g", "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--lib", android,
         "--min-api", "35", "--output", dex, engine_classes, *classes.rglob("*.class")])
    resources = build / "resources.zip"
    run([tools / ("aapt2" + ext), "compile", "--dir", ROOT / "res", "-o", resources])
    manifest = build / "AndroidManifest.xml"
    source_manifest = (ROOT / "AndroidManifest.xml").read_text(encoding="utf-8")
    manifest.write_text(source_manifest.replace("<application ", '<application android:debuggable="' + str(args.debug_setup).lower() + '" '), encoding="utf-8")
    unsigned = build / "manager-unsigned.apk"
    run([tools / ("aapt2" + ext), "link", "-I", android, resources, "-A", ROOT / "assets",
         "--manifest", manifest, "-o", unsigned])
    with zipfile.ZipFile(unsigned, "a") as target, zipfile.ZipFile(engine) as source:
        for entry in source.infolist():
            name = entry.filename
            if entry.is_dir() or name.endswith(".class") or name.startswith("META-INF/versions/"):
                continue
            # Shared upstream test keys are unnecessary: we require a private user key.
            if name in ("assets/keystore", "assets/new_keystore", "META-INF/MANIFEST.MF") or name.endswith((".SF", ".RSA", ".DSA")):
                continue
            target.writestr(name, source.read(entry), compress_type=zipfile.ZIP_STORED if name.endswith((".so", ".dex")) else zipfile.ZIP_DEFLATED)
        for path in dex.glob("*.dex"):
            target.write(path, path.name, compress_type=zipfile.ZIP_STORED)
    aligned = build / "manager-aligned.apk"
    run([tools / ("zipalign" + ext), "-f", "-P", "16", "4", unsigned, aligned])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run([java, "-jar", tools / "lib/apksigner.jar", "sign", "--ks", args.keystore.resolve(), "--ks-key-alias", "sim-test",
         "--ks-pass", "file:" + str(args.password_file.resolve()), "--out", args.output, aligned])
    run([java, "-jar", tools / "lib/apksigner.jar", "verify", args.output])
    print("Built", args.output, "SHA256", hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
