#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_apk.py — 纯命令行构建「学习通签到助手」APK（v3.0）
只用 Android SDK 的 aapt2 / d8 / zipalign / apksigner + JDK 的 javac
不需要 Gradle。

用法:
  python build_apk.py                          # 默认输出 dist/xxt-qd-v3.0.apk
  python build_apk.py -o dist/my.apk --version-code 31 --version-name 3.1

签名:
  默认使用 ../tools/xxt.keystore（不存在则自动生成）。
  口令优先取环境变量 XXT_KS_PASS。
"""
import argparse, os, shutil, subprocess, sys, glob, zipfile

SDK = os.environ.get("ANDROID_SDK", r"C:\Users\Administrator\android-sdk")
BT = os.path.join(SDK, "build-tools", "34.0.0")
PLATFORM = os.path.join(SDK, "platforms", "android-34", "android.jar")
JAVA_HOME = os.environ.get("JAVA_HOME", r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot")


def exe(name):
    p = os.path.join(BT, name)
    for cand in (p, p + ".bat", p + ".exe"):
        if os.path.exists(cand):
            return cand
    raise SystemExit("[!] 找不到 " + name + "（检查 ANDROID_SDK / build-tools 34.0.0）")


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


def stage_assets(app, work):
    """v3.0：把 assets 复制到 build/assets（过滤 *.bak* / 隐藏文件），避免备份文件进包"""
    src = os.path.join(app, "assets")
    dst = os.path.join(work, "assets")
    os.makedirs(dst, exist_ok=True)
    n = 0
    for f in os.listdir(src):
        fp = os.path.join(src, f)
        if not os.path.isfile(fp):
            continue
        if f.startswith(".") or ".bak" in f:
            print("    跳过备份/隐藏文件:", f)
            continue
        shutil.copy2(fp, os.path.join(dst, f))
        n += 1
    print("    assets 入包 %d 个文件" % n)
    return dst


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-o", "--out", default=None)
    ap.add_argument("--app", default=os.path.dirname(os.path.abspath(__file__)))
    ap.add_argument("--version-code", default="30")
    ap.add_argument("--version-name", default="3.0")
    ap.add_argument("--keystore", default=None, help="不传则用 ../tools/xxt.keystore（缺失自动生成）")
    ap.add_argument("--kspass", default=os.environ.get("XXT_KS_PASS", "xby2026pass"))
    ap.add_argument("--alias", default="xxtrelease")
    args = ap.parse_args()

    app = os.path.abspath(args.app)
    out_default = os.path.join(app, "..", "dist", "xxt-qd-v%s.apk" % args.version_name)
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
    print("[1/6] aapt2 link  (versionName=%s versionCode=%s)" % (args.version_name, args.version_code))
    assets_dir = stage_assets(app, work)
    base = os.path.join(work, "base.apk")
    aapt_cmd = [exe("aapt2"), "link",
                "-o", base,
                "-I", PLATFORM,
                "--manifest", os.path.join(app, "AndroidManifest.xml"),
                "-A", assets_dir,
                "--java", os.path.join(work, "gen"),
                "--min-sdk-version", "21",
                "--target-sdk-version", "34",
                "--version-code", args.version_code,
                "--version-name", args.version_name]
    res_dir = os.path.join(app, "res")
    if os.path.isdir(res_dir):
        # aapt2 要求资源先编译成 .flata（zip），再以 -R 参与 link
        flat = os.path.join(work, "res.flata")
        run([exe("aapt2"), "compile", "--dir", res_dir, "-o", flat])
        aapt_cmd += ["--auto-add-overlay", "-R", flat]
        print("    含资源目录 res/（已编译为 flata）")
    run(aapt_cmd)

    # ---------- 2. javac ----------
    print("[2/6] javac")
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
    print("[3/6] d8")
    classes = []
    for root, _, files in os.walk(os.path.join(work, "classes")):
        for f in files:
            if f.endswith(".class"):
                classes.append(os.path.join(root, f))
    print("    class 文件:", len(classes))
    java = os.path.join(JAVA_HOME, "bin", "java.exe")
    run([java, "-cp", os.path.join(BT, "lib", "d8.jar"),
         "com.android.tools.r8.D8", "--lib", PLATFORM, "--min-api", "21",
         "--release",
         "--output", os.path.join(work, "dex")] + classes)

    # ---------- 4. 把 classes.dex 塞进 APK ----------
    print("[4/6] 打包 dex")
    unsigned = os.path.join(work, "unsigned.apk")
    zin = zipfile.ZipFile(base, "r")
    zout = zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))   # 保留原压缩方式（resources.arsc 保持 STORED）
    zin.close()
    for dx in glob.glob(os.path.join(work, "dex", "*.dex")):
        zout.write(dx, os.path.basename(dx))
        print("    +", os.path.basename(dx))
    zout.close()

    # ---------- 5. zipalign + 签名 ----------
    print("[5/6] zipalign + 签名")
    aligned = os.path.join(work, "aligned.apk")
    run([exe("zipalign"), "-p", "-f", "4", unsigned, aligned])

    ks = args.keystore or os.path.join(app, "..", "tools", "xxt.keystore")
    ks = os.path.abspath(ks)
    if not os.path.exists(ks):
        os.makedirs(os.path.dirname(ks), exist_ok=True)
        print("    生成签名证书:", ks)
        run([keytool, "-genkeypair", "-keystore", ks, "-alias", args.alias,
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", args.kspass, "-keypass", args.kspass,
             "-dname", "CN=XXT SignIn Helper, OU=Release, O=Local, C=CN"])

    out = os.path.abspath(args.out or out_default)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    run([java, "-jar", os.path.join(BT, "lib", "apksigner.jar"), "sign",
         "--ks", ks, "--ks-key-alias", args.alias,
         "--ks-pass", "pass:" + args.kspass, "--key-pass", "pass:" + args.kspass,
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true",
         "--out", out, aligned])

    # ---------- 6. 验证 ----------
    print("[6/6] 验证")
    print()
    print("[=] 已生成:", out, os.path.getsize(out), "bytes")
    r = subprocess.run([java, "-jar", os.path.join(BT, "lib", "apksigner.jar"),
                        "verify", "--verbose", out],
                       capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    print("    验签:", "OK" if r.returncode == 0 else "FAILED")
    print(r.stdout.strip()[:600])
    if r.returncode != 0:
        print(r.stderr.strip()[:600], file=sys.stderr)
    badging = subprocess.run([exe("aapt2"), "dump", "badging", out],
                             capture_output=True, text=True,
                             encoding="utf-8", errors="replace")
    for line in badging.stdout.splitlines()[:4]:
        print("    " + line)
    return 0 if r.returncode == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
