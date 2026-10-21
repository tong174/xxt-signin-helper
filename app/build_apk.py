#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_apk.py — 纯命令行构建「学习通签到助手」APK
只用 Android SDK 的 aapt2 / d8 / zipalign / apksigner + JDK 的 javac
不需要 Gradle。

用法:
  python build_apk.py -o dist/xxt-qd.apk
"""
import argparse, os, shutil, subprocess, sys, glob, zipfile

SDK = r"C:\Users\Administrator\android-sdk"
BT = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM = os.path.join(SDK, "platforms", "android-34", "android.jar")
JAVA_HOME = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"


def exe(name):
    p = os.path.join(BT, name)
    for cand in (p, p + ".bat", p + ".exe"):
        if os.path.exists(cand):
            return cand
    raise SystemExit("[!] 找不到 " + name)


def run(cmd, **kw):
    print("  $", " ".join('"%s"' % c if " " in c else c for c in cmd[:4]), "…")
    r = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", **kw)
    if r.stdout.strip():
        print("    " + r.stdout.strip().replace("\n", "\n    ")[:3000])
    if r.returncode != 0:
        print("[!] 失败:", r.stderr[-3000:], file=sys.stderr)
        raise SystemExit(r.returncode)
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default="dist/xxt-qd.apk")
    ap.add_argument("--app", default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--keystore", default=None, help="不传则自动生成 release.keystore")
    ap.add_argument("--kspass", default="xby2026pass")
    ap.add_argument("--alias", default="xxtrelease")
    ap.add_argument("--vcode", default="31")
    ap.add_argument("--vname", default="3.1")
    args = ap.parse_args()

    app = os.path.abspath(args.app)
    work = os.path.join(app, "build")
    if os.path.exists(work):
        shutil.rmtree(work)
    os.makedirs(work)
    os.makedirs(os.path.join(work, "classes"), exist_ok=True)
    os.makedirs(os.path.join(work, "gen"), exist_ok=True)
    os.makedirs(os.path.join(work, "dex"), exist_ok=True)

    javac = os.path.join(JAVA_HOME, "bin", "javac.exe")
    keytool = os.path.join(JAVA_HOME, "bin", "keytool.exe")

    # ---------- 1. aapt2 link：清单 + assets -> 带资源的 APK ----------
    print("[1/5] aapt2 link")
    base = os.path.join(work, "base.apk")
    aapt_cmd = [exe("aapt2"), "link",
                "-o", base,
                "-I", PLATFORM,
                "--manifest", os.path.join(app, "AndroidManifest.xml"),
                "-A", os.path.join(app, "assets"),
                "--java", os.path.join(work, "gen"),
                "--min-sdk-version", "21",
                "--target-sdk-version", "34",
                "--version-code", str(args.vcode), "--version-name", args.vname]
    res_dir = os.path.join(app, "res")
    if os.path.isdir(res_dir):
        # aapt2 要求资源先编译成 .flata（zip），再以 -R 参与 link
        flat = os.path.join(work, "res.flata")
        run([exe("aapt2"), "compile", "--dir", res_dir, "-o", flat])
        aapt_cmd += ["--auto-add-overlay", "-R", flat]
        print("    含资源目录 res/（已编译为 flata）")
    run(aapt_cmd)

    # ---------- 2. javac ----------
    print("[2/5] javac")
    java_files = []
    for root, _, files in os.walk(os.path.join(app, "java")):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    for root, _, files in os.walk(os.path.join(work, "gen")):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    print("    源文件:", len(java_files))
    run([javac, "-encoding", "UTF-8", "--release", "8",
         "-classpath", PLATFORM,
         "-d", os.path.join(work, "classes")] + java_files)

    # ---------- 3. d8 ----------
    print("[3/5] d8")
    classes = []
    for root, _, files in os.walk(os.path.join(work, "classes")):
        for f in files:
            if f.endswith(".class"):
                classes.append(os.path.join(root, f))
    print("    class 文件:", len(classes))
    java = os.path.join(JAVA_HOME, "bin", "java.exe")
    run([java, "-cp", os.path.join(BT, "lib", "d8.jar"),
         "com.android.tools.r8.D8", "--lib", PLATFORM, "--min-api", "21",
         "--output", os.path.join(work, "dex")] + classes)

    # ---------- 4. 把 classes.dex 塞进 APK ----------
    print("[4/5] 打包 dex")
    unsigned = os.path.join(work, "unsigned.apk")
    zin = zipfile.ZipFile(base, "r")
    zout = zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zin.close()
    for dx in glob.glob(os.path.join(work, "dex", "*.dex")):
        zout.write(dx, os.path.basename(dx))
        print("    +", os.path.basename(dx))
    zout.close()

    # ---------- 5. zipalign + 签名 ----------
    print("[5/5] zipalign + 签名")
    aligned = os.path.join(work, "aligned.apk")
    run([exe("zipalign"), "-p", "-f", "4", unsigned, aligned])

    ks = args.keystore or os.path.join(app, "..", "tools", "xxt.keystore")
    ks = os.path.abspath(ks)
    if not os.path.exists(ks):
        print("    生成签名证书:", ks)
        run([keytool, "-genkeypair", "-keystore", ks, "-alias", args.alias,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", args.kspass, "-keypass", args.kspass,
             "-dname", "CN=XXT SignIn Helper, OU=Release, O=Local, C=CN"])

    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    java = os.path.join(JAVA_HOME, "bin", "java.exe")
    run([java, "-jar", os.path.join(BT, "lib", "apksigner.jar"), "sign",
         "--ks", ks, "--ks-key-alias", args.alias,
         "--ks-pass", "pass:" + args.kspass, "--key-pass", "pass:" + args.kspass,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true",
         "--out", out, aligned])

    print()
    print("[=] 已生成:", out, os.path.getsize(out), "bytes")
    r = subprocess.run([java, "-jar", os.path.join(BT, "lib", "apksigner.jar"),
                        "verify", "--verbose", out],
                       capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    print("    验签:", "OK" if r.returncode == 0 else "FAILED")
    print(r.stdout.strip()[:600])
    return 0


if __name__ == "__main__":
    sys.exit(main())
