#!/usr/bin/env python3
"""
End-to-end test of Sync Music: the real PC app (Linux build, running under Xvfb)
and the real Android app on an emulator, driven through adb + uiautomator.

Writes screenshots, timings and a PASS/FAIL report to e2e-out/.
The emulator reaches the PC app at 10.0.2.2 (UDP discovery does not cross the
emulator NAT, so the manual-address path is used for pairing).
"""
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "e2e-out"
WORK = Path("/tmp/e2e")
APPDATA = WORK / "appdata"
DATA = APPDATA / "SyncMusic"
MUSIC = WORK / "music"
APP_BIN = ROOT / "desktopApp/build/compose/binaries/main/app/SyncMusic/bin/SyncMusic"
APK = ROOT / "apk/syncmusic.apk"
OLD_APK = ROOT / "apk-old/syncmusic-old.apk"  # previous release, to test the upgrade path
CAMERA_PC = WORK / "pc-camera"                 # phone -> PC folder on the PC
CAMERA_PHONE = "/sdcard/DCIM/TestCam"
DOCS = WORK / "docs"                           # second PC -> phone folder
DOCS_PHONE = "/sdcard/Sync Music/docs"
PKG = "biz.fesh.syncmusic"
ACTIVITY = f"{PKG}/.ui.MainActivity"
PHONE_ROOT = "/sdcard/Music/Sync Music"
DISPLAY = ":99"
JUNK = {"thumbs.db", "desktop.ini", "ehthumbs.db"}


def syncable(name: str) -> bool:
    return bool(name) and not name.startswith(".") and not name.startswith("~$") and name.lower() not in JUNK

results: list[tuple[str, str, str]] = []
timings: list[tuple[str, str]] = []
pc_proc = None
xvfb_proc = None
using_old = False


# ----------------------------------------------------------------- reporting

def check(name, ok, detail=""):
    status = "PASS" if ok else "FAIL"
    results.append((status, name, str(detail)))
    print(f"[{status}] {name} {detail}", flush=True)
    write_report()
    return bool(ok)


def skip(name, detail=""):
    results.append(("SKIP", name, str(detail)))
    print(f"[SKIP] {name} {detail}", flush=True)
    write_report()


def timing(name, value):
    timings.append((name, value))
    print(f"[TIME] {name}: {value}", flush=True)
    write_report()


def write_report():
    lines = ["# Sync Music e2e", ""]
    for status, name, detail in results:
        lines.append(f"- **{status}** {name}" + (f" — {detail}" if detail else ""))
    lines += ["", "## Timings", ""]
    for name, value in timings:
        lines.append(f"- {name}: {value}")
    (OUT / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


# ----------------------------------------------------------------- shell helpers

def run(cmd, timeout=300, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)


def adb(*args, timeout=300):
    return run(["adb", *args], timeout=timeout)


def sh(cmd, timeout=300):
    return adb("shell", cmd, timeout=timeout).stdout


def q(path):
    """Quote a path for the device shell."""
    return "'" + path.replace("'", "'\\''") + "'"


def shot(name):
    try:
        data = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
        (OUT / f"phone-{name}.png").write_bytes(data)
    except Exception as e:  # noqa: BLE001
        print("phone screenshot failed", e)
    try:
        run(["import", "-display", DISPLAY, "-window", "root", str(OUT / f"pc-{name}.png")], timeout=60)
    except Exception as e:  # noqa: BLE001
        print("pc screenshot failed", e)


# ----------------------------------------------------------------- UI automation

_screen = None


def screen_size():
    global _screen
    if _screen is None:
        m = re.search(r"(\d+)x(\d+)", sh("wm size"))
        _screen = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    return _screen


def ui_nodes():
    for _ in range(8):
        raw = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", timeout=60)
        start = raw.find("<?xml")
        if start < 0:
            start = raw.find("<hierarchy")
        if start >= 0:
            try:
                nodes = list(ET.fromstring(raw[start:]).iter("node"))
            except ET.ParseError:
                nodes = None
            if nodes is not None:
                if dismiss_system_dialog(nodes):
                    continue
                return nodes
        time.sleep(1)
    return []


def dismiss_system_dialog(nodes):
    """The slow emulator sometimes shows "<system app> isn't responding"; it is not ours, close it."""
    texts = [node_text(n) for n in nodes]
    if not any("isn't responding" in t or "keeps stopping" in t for t in texts):
        return False
    if any(PKG in (n.get("package") or "") and "isn't responding" in node_text(n) for n in nodes):
        check("No 'not responding' dialog for Sync Music", False, " | ".join(t for t in texts if t)[:200])
    for label in ("Wait", "Close app", "OK"):
        for n in nodes:
            if node_text(n) == label:
                x, y = center(n)
                sh(f"input tap {x} {y}")
                time.sleep(1.5)
                return True
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)
    return True


def node_text(n):
    return ((n.get("text") or "") + " " + (n.get("content-desc") or "")).strip()


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text=None, cls=None, nodes=None):
    for n in nodes if nodes is not None else ui_nodes():
        if cls and n.get("class") != cls:
            continue
        if text is None or text in node_text(n):
            return n
    return None


def swipe(up=True):
    w, h = screen_size()
    if up:
        sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.30)} 300")
    else:
        sh(f"input swipe {w // 2} {int(h * 0.30)} {w // 2} {int(h * 0.80)} 300")
    time.sleep(0.6)


def to_top():
    for _ in range(6):
        swipe(up=False)


def wait_for(text, timeout=30, scroll=True):
    end = time.time() + timeout
    swipes = 0
    while time.time() < end:
        n = find(text)
        if n is not None:
            return n
        if scroll:
            if swipes < 5:
                swipe(up=True)
                swipes += 1
            else:
                to_top()
                swipes = 0
        time.sleep(0.5)
    return None


def tap(n):
    x, y = center(n)
    sh(f"input tap {x} {y}")
    time.sleep(0.8)


def tap_text(text, timeout=30):
    n = wait_for(text, timeout)
    if n is None:
        return False
    # Make sure the target is really on screen (a lazy list may report clipped nodes).
    _, h = screen_size()
    for _ in range(4):
        _, y = center(n)
        if int(h * 0.08) < y < int(h * 0.92):
            break
        swipe(up=y >= int(h * 0.92))
        n = find(text)
        if n is None:
            return False
    tap(n)
    return True


def type_into(edit_index, value):
    nodes = [n for n in ui_nodes() if n.get("class") == "android.widget.EditText"]
    if len(nodes) <= edit_index:
        return False
    tap(nodes[edit_index])
    sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(12):
        sh("input keyevent KEYCODE_DEL")
    sh(f"input text {value}")
    time.sleep(0.5)
    if "mInputShown=true" in sh("dumpsys input_method"):
        sh("input keyevent KEYCODE_BACK")  # hide the keyboard (only when it is shown)
        time.sleep(0.5)
    return True


def cold_start(label):
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    out = adb("shell", "am", "start", "-W", "-n", ACTIVITY).stdout
    m = re.search(r"TotalTime:\s*(\d+)", out)
    value = f"{m.group(1)} ms" if m else out.strip()[:200]
    timing(f"Phone app cold start ({label})", value)
    time.sleep(2)
    return int(m.group(1)) if m else None


# ----------------------------------------------------------------- PC side

def status():
    try:
        return json.loads((DATA / "status.json").read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def device_progress():
    devices = status().get("devices") or []
    return (devices[0].get("progress") if devices else None) or {}


def pc_command(cmd):
    (DATA / "command.txt").write_text(cmd, encoding="utf-8")
    time.sleep(1.5)


def write_file(path: Path, size: int, seed=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(seed if seed is not None else str(path))
    path.write_bytes(rnd.randbytes(size))


def md5(path: Path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def expected_files(root: Path = MUSIC):
    files = {}
    for p in root.rglob("*"):
        if p.is_file() and not any(part.startswith(".") for part in p.relative_to(root).parent.parts):
            if syncable(p.name):
                files[p.relative_to(root).as_posix()] = p
    return files


def prepare_library():
    if WORK.exists():
        shutil.rmtree(WORK)
    names = [
        ("Океан Ельзи", "Найкраще", ["Обійми", "Без бою", "Я так хочу", "Друг"]),
        ("Kalush", "Album 1", ["Stefania", "Dodomu", "Shtomber"]),
        ("Артист з пробілами", "Альбом №1", ["Пісня's одна", "Пісня (live)", "Третя & остання"]),
        ("Jazz", "Night Session", ["Track 01", "Track 02", "Track 03", "Track 04", "Track 05"]),
        ("Rock", "1999 [Remaster]", ["Intro", "Song A", "Song B"]),
    ]
    exts = ["mp3", "flac", "m4a", "ogg", "opus"]
    i = 0
    for artist, album, tracks in names:
        for n, title in enumerate(tracks, 1):
            write_file(MUSIC / artist / album / f"{n:02d} {title}.{exts[i % len(exts)]}", 60_000 + (i * 37_123) % 400_000)
            i += 1
        write_file(MUSIC / artist / album / "cover.jpg", 20_000)  # must not be synced
    (MUSIC / "Плейлист.m3u").write_text("#EXTM3U\nОкеан Ельзи/Найкраще/01 Обійми.mp3\n", encoding="utf-8")
    (MUSIC / "Kalush" / "Album 1" / "01 Stefania.lrc").write_text("[00:01.00]Stefania\n", encoding="utf-8")
    (MUSIC / "notes.txt").write_text("not music", encoding="utf-8")
    DATA.mkdir(parents=True, exist_ok=True)
    (DATA / "settings.json").write_text(
        json.dumps({"musicFolder": str(MUSIC), "autostart": False, "firstRunDone": True, "trayHintShown": True}),
        encoding="utf-8",
    )


def start_pc():
    global pc_proc, xvfb_proc
    xvfb_proc = subprocess.Popen(["Xvfb", DISPLAY, "-screen", "0", "1280x900x24"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(2)
    env = dict(os.environ, DISPLAY=DISPLAY, APPDATA=str(APPDATA), LANG="C.UTF-8", LC_ALL="C.UTF-8")
    log = open(OUT / "pc-stdout.log", "w")
    t0 = time.time()
    pc_proc = subprocess.Popen([str(APP_BIN), "--status"], env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(120):
        st = status()
        if st.get("pin") and "Running" in st.get("server", "") and st.get("libraryFiles", 0) > 0:
            break
        time.sleep(0.5)
    st = status()
    timing("PC app ready (Linux, Xvfb)", f"{time.time() - t0:.1f} s")
    check("PC app starts, server running, library scanned", st.get("pin") and st.get("libraryFiles") == len(expected_files()),
          f"server={st.get('server')} files={st.get('libraryFiles')} expected={len(expected_files())}")
    time.sleep(4)
    shot("00-pc-started")


def stop_pc():
    for p in (pc_proc, xvfb_proc):
        if p:
            try:
                p.terminate()
                p.wait(timeout=10)
            except Exception:  # noqa: BLE001
                p.kill()


# ----------------------------------------------------------------- phone state

def phone_files(root=PHONE_ROOT):
    out = sh(f"cd {q(root)} 2>/dev/null && find . -type f ! -path './.syncmusic/*' -exec md5sum {{}} +", timeout=600)
    files = {}
    for line in out.splitlines():
        parts = line.split("  ", 1)
        if len(parts) == 2 and parts[1].startswith("./"):
            files[parts[1][2:]] = parts[0]
    return files


def phone_count(root=PHONE_ROOT):
    out = sh(f"find {q(root)} -type f ! -path '*/.syncmusic/*' 2>/dev/null | wc -l", timeout=600).strip()
    return int(out) if out.isdigit() else -1


def verify_phone(label, root=PHONE_ROOT, pc_root: Path = MUSIC):
    expected = expected_files(pc_root)
    actual = phone_files(root)
    want = {rel: md5(p) for rel, p in expected.items()}
    missing = sorted(set(want) - set(actual))
    extra = sorted(set(actual) - set(want))
    wrong = sorted(k for k in want if k in actual and actual[k] != want[k])
    return check(
        f"Phone matches PC ({label})",
        not missing and not extra and not wrong,
        f"{len(actual)}/{len(want)} files; missing={missing[:3]} extra={extra[:3]} wrong={wrong[:3]}",
    )


def trigger_sync():
    to_top()
    if not tap_text("Синхронізувати зараз", timeout=20):
        # A sync may be running already (button shows a different label).
        return find("Триває синхронізація") is not None
    return True


def wait_sync_done(label, expected_total, since, timeout=240):
    """expected_total=None: only require that everything that should be synced was synced."""
    end = time.time() + timeout
    last = {}
    while time.time() < end:
        p = device_progress()
        last = p
        if p.get("timestamp", 0) / 1000 >= since and p.get("phase") in ("DONE", "ERROR"):
            break
        time.sleep(1)
    took = time.time() - since
    totals_ok = last.get("syncedFiles") == last.get("totalFiles") if expected_total is None else \
        last.get("totalFiles") == expected_total and last.get("syncedFiles") == expected_total
    ok = last.get("phase") == "DONE" and totals_ok
    check(f"Sync finished: {label}", ok,
          f"phase={last.get('phase')} synced={last.get('syncedFiles')}/{last.get('totalFiles')} expected={expected_total} "
          f"downloaded={last.get('downloadedThisRun')} deleted={last.get('deletedThisRun')} msg={last.get('message')}")
    timing(f"Sync '{label}'", f"{took:.1f} s")
    return ok


# ----------------------------------------------------------------- scenarios

def device_setup():
    adb("wait-for-device")
    sh("svc wifi enable")
    sh("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
    global using_old
    using_old = OLD_APK.is_file()
    apk = OLD_APK if using_old else APK
    r = adb("install", "-r", "-g", str(apk))
    check("APK installs" + (" (previous release, to test the upgrade)" if using_old else ""), "Success" in r.stdout,
          r.stdout.strip()[-200:] + r.stderr.strip()[-200:])
    sh(f"appops set --uid {PKG} MANAGE_EXTERNAL_STORAGE allow")
    sh(f"dumpsys deviceidle whitelist +{PKG}")
    print(sh("dumpsys connectivity | grep -i 'NetworkAgentInfo' | head -5"))


def pair():
    cold_start("first launch")
    shot("01-first-launch")
    check("Pairing screen shown", wait_for("Підключення до комп'ютера", 20) is not None)
    # Automatic UDP search runs first and cannot reach the host through the emulator NAT.
    wait_for("Шукати знову", 25)
    to_top()
    if wait_for("192.168.1.10", 10) is None:
        swipe(up=True)
    edits = [n for n in ui_nodes() if n.get("class") == "android.widget.EditText"]
    check("Manual address field present", len(edits) >= 1, f"{len(edits)} fields")
    type_into(0, "10.0.2.2")
    tap_text("ОК", 10)
    found = wait_for("Комп'ютер «", 20, scroll=False)
    check("PC found by manual address", found is not None)
    shot("02-pc-found")

    # Wrong code first.
    type_into(0, "000000")
    tap_text("Підключити", 10)
    wrong = wait_for("Невірний код", 15, scroll=False)
    check("Wrong code rejected", wrong is not None)
    shot("03-wrong-code")

    pin = status().get("pin")
    type_into(0, pin)
    since = time.time()
    tap_text("Підключити", 10)
    paired = wait_for("Синхронізувати зараз", 30)
    if paired is None:
        paired = wait_for("Триває синхронізація", 5)
    check("Paired with correct code", paired is not None, f"pin={pin}")
    shot("04-paired")
    return since


def scenario_initial(since):
    expected = expected_files()
    wait_sync_done("initial", len(expected), since)
    shot("05-initial-synced")
    verify_phone("initial sync")
    if using_old:
        scenario_upgrade()
    phone = phone_files()
    check("All file types are synced (covers, text files)", "notes.txt" in phone and any(k.endswith("cover.jpg") for k in phone))


def scenario_upgrade():
    r = adb("install", "-r", "-g", str(APK))
    check("New version installs over the previous release", "Success" in r.stdout, r.stdout.strip()[-200:])
    sh(f"appops set --uid {PKG} MANAGE_EXTERNAL_STORAGE allow")
    since = time.time()
    cold_start("after upgrade")
    wait_sync_done("after upgrade", len(expected_files()), since)
    p = device_progress()
    check("Upgrade keeps the synced music (nothing downloaded again)", p.get("downloadedThisRun") == 0,
          f"downloaded={p.get('downloadedThisRun')}")
    verify_phone("after upgrade")
    check("Folder list shown after upgrade", wait_for("Папки", 20) is not None)
    shot("05b-after-upgrade")


def pc_folder_files(name):
    for folder in status().get("folders") or []:
        if folder.get("name") == name:
            return folder.get("files")
    return None


def pc_files(root: Path):
    return {rel: md5(p) for rel, p in expected_files(root).items()}


def scenario_camera():
    """Phone -> PC folder: choose the phone folder, upload, the delete switch, resume."""
    src = WORK / "cam-src"
    names = ["IMG_0001.jpg", "IMG_0002.jpg", "Відео з відпустки.mp4", "2026/Квітень/IMG_0100.jpg", "2026/notes.txt"]
    for i, name in enumerate(names):
        write_file(src / name, 40_000 + i * 51_000)
    r = adb("push", str(src), CAMERA_PHONE, timeout=600)
    check("Test photos placed on the phone", phone_count(CAMERA_PHONE) == len(names), r.stdout.strip()[-120:])
    CAMERA_PC.mkdir(parents=True, exist_ok=True)
    pc_command(f"add-folder|{CAMERA_PC}|TO_PC")
    check("PC added a phone -> PC folder", pc_folder_files(CAMERA_PC.name) is not None)

    since = time.time()
    trigger_sync()
    wait_sync_done("folder list with a new phone -> PC folder", None, since)
    shot("15-camera-folder-offered")
    check("Phone asks to choose a folder for it", tap_text("Вибрати папку", 30))
    check("Folder picker opened", wait_for("Вибрати цю папку", 15, scroll=False) is not None)
    shot("16-picker")
    tap_text("DCIM", 20)
    tap_text("TestCam", 20)
    shot("17-picker-testcam")
    since = time.time()
    tap_text("Вибрати цю папку", 10)
    wait_sync_done("phone -> PC upload", None, since, timeout=300)
    want = {rel: md5(p) for rel, p in expected_files(src).items()}
    got = pc_files(CAMERA_PC)
    check("Phone files arrived on the PC", got == want, f"{len(got)}/{len(want)}")
    shot("18-camera-uploaded")

    # The delete switch is off by default for phone -> PC: the PC keeps a copy.
    sh(f"rm {q(CAMERA_PHONE + '/IMG_0001.jpg')}")
    since = time.time()
    trigger_sync()
    wait_sync_done("phone deleted a photo (delete off)", None, since)
    check("PC keeps the file deleted on the phone (delete off)", (CAMERA_PC / "IMG_0001.jpg").is_file())

    pc_command(f"set-delete|{CAMERA_PC.name}|true")
    sh(f"rm {q(CAMERA_PHONE + '/IMG_0002.jpg')}")
    since = time.time()
    trigger_sync()
    wait_sync_done("phone deleted a photo (delete on)", None, since)
    check("PC deletes the file deleted on the phone (delete on)", not (CAMERA_PC / "IMG_0002.jpg").exists())
    check("Earlier kept file stays on the PC", (CAMERA_PC / "IMG_0001.jpg").is_file())

    # Resume of an interrupted upload.
    big = WORK / "big-upload.mp4"
    write_file(big, 200 * 1024 * 1024, seed="big-upload")
    adb("push", str(big), CAMERA_PHONE + "/big.mp4", timeout=600)
    since = time.time()
    trigger_sync()
    part_size = 0
    t0 = time.time()
    while time.time() - t0 < 180:
        parts = list((CAMERA_PC / ".syncmusic").glob("*.part")) if (CAMERA_PC / ".syncmusic").is_dir() else []
        if parts and parts[0].stat().st_size > 20 * 1024 * 1024:
            part_size = parts[0].stat().st_size
            break
        time.sleep(0.3)
    shot("19-big-upload")
    if not part_size:
        skip("Upload resume after interruption", "upload finished before it could be interrupted")
        wait_sync_done("big upload", None, since, timeout=300)
    else:
        adb("shell", "am", "force-stop", PKG)
        time.sleep(2)
        check("PC keeps the partial upload", part_size > 0, f"{part_size // 1048576} MB of 200 MB")
        since = time.time()
        adb("shell", "am", "start", "-n", ACTIVITY)
        wait_sync_done("big upload after resume", None, since, timeout=400)
        log = (DATA / "syncmusic.log").read_text(encoding="utf-8", errors="replace")
        check("Upload resumed from the interruption point", "Upload resume big.mp4 from" in log)
    ok = (CAMERA_PC / "big.mp4").is_file() and md5(CAMERA_PC / "big.mp4") == md5(big)
    check("Big file identical on the PC", ok)
    sh(f"rm {q(CAMERA_PHONE + '/big.mp4')}")


def scenario_docs():
    """A second PC -> phone folder lands in the default place; hidden files and junk are skipped."""
    write_file(DOCS / "Звіт за рік.pdf", 300_000)
    write_file(DOCS / "notes" / "план.txt", 2_000)
    write_file(DOCS / "Thumbs.db", 5_000)
    write_file(DOCS / ".hidden", 100)
    write_file(DOCS / "~$Звіт.docx", 160)
    pc_command(f"add-folder|{DOCS}|TO_PHONE")
    since = time.time()
    trigger_sync()
    wait_sync_done("second PC -> phone folder", None, since)
    verify_phone("second folder", root=DOCS_PHONE, pc_root=DOCS)
    phone = phone_files(DOCS_PHONE)
    check("Hidden files and system junk are not synced", not any(k in phone for k in ("Thumbs.db", ".hidden", "~$Звіт.docx")))
    shot("20-docs-folder")


def scenario_pc_delete():
    files = expected_files()
    victims = sorted(files)[:2]
    before = status().get("libraryFiles")
    for rel in victims:
        files[rel].unlink()
    # The folder watcher must update the counter without any button press.
    t0 = time.time()
    while time.time() - t0 < 15 and status().get("libraryFiles") == before:
        time.sleep(0.5)
    after = status().get("libraryFiles")
    check("PC counter updates after deleting 2 files", after == before - 2, f"{before} -> {after} in {time.time() - t0:.1f} s")
    shot("06-pc-after-delete")
    # A whole album removed: empty folders must disappear on the phone too.
    shutil.rmtree(MUSIC / "Rock")
    time.sleep(3)
    since = time.time()
    trigger_sync()
    wait_sync_done("after PC deletions", len(expected_files()), since)
    verify_phone("after PC deletions")
    leftover = sh(f"ls {q(PHONE_ROOT + '/Rock')} 2>&1")
    check("Empty album folder removed on phone", "No such file" in leftover, leftover.strip()[:100])
    shot("07-after-pc-delete-sync")


def scenario_phone_delete():
    rel = sorted(expected_files())[0]
    sh(f"rm {q(PHONE_ROOT + '/' + rel)}")
    check("File deleted on phone", phone_count() == len(expected_files()) - 1)
    since = time.time()
    trigger_sync()
    wait_sync_done("restore deleted on phone", len(expected_files()), since)
    verify_phone("file deleted on phone is restored")


def scenario_new_and_changed():
    write_file(MUSIC / "Нове" / "Свіжий альбом" / "01 Нова пісня.mp3", 150_000)
    write_file(MUSIC / "Нове" / "Свіжий альбом" / "02 Ще одна.flac", 210_000)
    changed = sorted(expected_files())[3]
    p = expected_files()[changed]
    write_file(p, p.stat().st_size + 1234, seed="changed")  # new tags -> new size and content
    time.sleep(3)
    since = time.time()
    trigger_sync()
    wait_sync_done("new and changed files", len(expected_files()), since)
    verify_phone("new and changed files")


def scenario_resume():
    big = MUSIC / "Big" / "huge.flac"
    write_file(big, 250 * 1024 * 1024, seed="big")
    time.sleep(3)
    pc_command("rescan")
    since = time.time()
    trigger_sync()
    part_size = 0
    t0 = time.time()
    while time.time() - t0 < 120:
        out = sh(f"ls -l {q(PHONE_ROOT + '/.syncmusic')} 2>/dev/null | grep part")
        m = re.search(r"\s(\d{6,})\s", out)
        if m and int(m.group(1)) > 20 * 1024 * 1024:
            part_size = int(m.group(1))
            break
        time.sleep(0.3)
    shot("08-big-file-downloading")
    if not part_size:
        skip("Resume after interruption", "download finished before it could be interrupted")
        wait_sync_done("big file", len(expected_files()), since)
    else:
        adb("shell", "am", "force-stop", PKG)
        time.sleep(2)
        out = sh(f"ls -l {q(PHONE_ROOT + '/.syncmusic')} | grep part")
        m = re.search(r"\s(\d{6,})\s", out)
        kept = int(m.group(1)) if m else 0
        check("Partial file kept after the app was killed", kept > 0, f"{kept // 1048576} MB of 250 MB")
        since = time.time()
        adb("shell", "am", "start", "-n", ACTIVITY)  # opening the app resumes the sync
        wait_sync_done("big file after resume", len(expected_files()), since, timeout=300)
        log = (DATA / "syncmusic.log").read_text(encoding="utf-8", errors="replace")
        check("Download resumed from the interruption point (HTTP Range)", "Resume Big/huge.flac from bytes=" in log)
    verify_phone("big file")
    big.unlink()
    shutil.rmtree(MUSIC / "Big")
    time.sleep(3)
    since = time.time()
    trigger_sync()
    wait_sync_done("big file removed", len(expected_files()), since)


def scenario_scale():
    n_dirs, per_dir = 2800, 10
    scale = WORK / "scale-staging"
    t0 = time.time()
    for d in range(n_dirs):
        folder = scale / f"Artist {d // 20:03d}" / f"Album {d:04d}"
        folder.mkdir(parents=True, exist_ok=True)
        for t in range(per_dir):
            (folder / f"{t:02d} Track.mp3").write_bytes(b"x" * (512 + t))
    total_scale = n_dirs * per_dir
    # Copy to the phone "by cable" first (adb push), then let the PC see the same files.
    r = adb("push", str(scale), PHONE_ROOT + "/_scale", timeout=1800)
    timing("adb push of 28 000 files (cable copy)", f"{time.time() - t0:.0f} s")
    check("Files copied to phone by cable", phone_count(PHONE_ROOT + "/_scale") == total_scale, r.stdout.strip()[-120:])
    shutil.move(str(scale), str(MUSIC / "_scale"))
    pc_command("rescan")
    t0 = time.time()
    while time.time() - t0 < 60 and pc_folder_files(MUSIC.name) != len(expected_files()):
        time.sleep(0.5)
    timing("PC rescan with ~28 000 files", f"{time.time() - t0:.1f} s")
    since = time.time()
    trigger_sync()
    ok = wait_sync_done("28 000 files copied by cable (adopted, nothing downloaded)", None, since, timeout=900)
    p = device_progress()
    check("Cable-copied files were adopted, not downloaded", ok and p.get("downloadedThisRun") == 0, f"downloaded={p.get('downloadedThisRun')}")
    shot("09-scale-synced")
    since = time.time()
    trigger_sync()
    wait_sync_done("28 000 files, nothing changed", None, since, timeout=600)
    cold_start("with 28 000 synced files")
    shot("10-scale-cold-start")


def scenario_scale_cleanup():
    shutil.rmtree(MUSIC / "_scale")
    pc_command("rescan")
    time.sleep(3)
    since = time.time()
    trigger_sync()
    wait_sync_done("28 000 files deleted on PC", None, since, timeout=900)
    check("Phone deleted the 28 000 files", phone_count() == len(expected_files()), f"{phone_count()} files left")


def scenario_sd_card():
    sh("sm set-virtual-disk true")
    disk = None
    for _ in range(20):
        m = re.search(r"(disk:\S+)", sh("sm list-disks"))
        if m:
            disk = m.group(1)
            break
        time.sleep(1)
    if not disk:
        skip("SD card", "emulator did not provide a virtual SD card")
        return
    sh(f"sm partition {disk} public", timeout=300)
    uuid = None
    for _ in range(40):
        for line in sh("sm list-volumes public").splitlines():
            parts = line.split()
            if len(parts) >= 3 and parts[1] == "mounted" and parts[2] != "null":
                uuid = parts[2]
        if uuid:
            break
        time.sleep(1)
    if not uuid:
        skip("SD card", "virtual SD card did not mount: " + sh("sm list-volumes all"))
        return
    sd_music = f"/storage/{uuid}/Music"
    cold_start("SD card inserted")
    time.sleep(3)
    # The first "Змінити" belongs to the first folder (the music folder).
    check("Change-folder button", tap_text("Змінити", 30))
    check("Folder picker opened", wait_for("Вибрати цю папку", 15, scroll=False) is not None)
    check("SD card offered in the picker", tap_text("SD-карта", 15))
    shot("11-picker-sd")
    tap_text("Нова папка", 10)
    type_into(0, "Music")
    tap_text("Створити", 10)
    shot("12-picker-sd-music")
    tap_text("Вибрати цю папку", 10)
    check("Move dialog", wait_for("Перенести", 10, scroll=False) is not None)
    shot("12b-move-dialog")
    since = time.time()
    tap_text("Видалити", 10)
    wait_sync_done("music moved to SD card", None, since, timeout=300)
    verify_phone("on SD card", root=sd_music)
    check("Old copy in phone memory removed", phone_count() == 0, f"{phone_count()} files left in phone memory")
    p = device_progress()
    music = next((f for f in p.get("folders") or [] if f.get("name") == MUSIC.name), {})
    check("PC sees the phone folder on the SD card", "SD-карта" in (music.get("localPath") or ""), music.get("localPath"))
    shot("13-on-sd-card")


def scenario_revoke():
    pc_command("remove-devices")
    trigger_sync()
    n = wait_for("відключив цей телефон", 60)
    check("Phone notices it was disconnected on the PC", n is not None)
    shot("14-revoked")


def main():
    OUT.mkdir(exist_ok=True)
    try:
        prepare_library()
        start_pc()
        device_setup()
        since = pair()
        scenario_initial(since)
        for scenario in (scenario_pc_delete, scenario_phone_delete, scenario_new_and_changed, scenario_resume,
                         scenario_camera, scenario_docs, scenario_scale, scenario_scale_cleanup, scenario_sd_card,
                         scenario_revoke):
            try:
                scenario()
            except Exception as e:  # noqa: BLE001
                check(f"{scenario.__name__} crashed", False, repr(e))
                shot(f"crash-{scenario.__name__}")
    except Exception as e:  # noqa: BLE001
        check("e2e crashed", False, repr(e))
        shot("crash")
    finally:
        try:
            for name in ("syncmusic.log", "status.json", "settings.json"):
                if (DATA / name).exists():
                    shutil.copy(DATA / name, OUT / f"pc-{name}")
            (OUT / "phone-logcat.txt").write_text(
                adb("logcat", "-d", "-t", "3000", timeout=120).stdout, encoding="utf-8", errors="replace")
        except Exception as e:  # noqa: BLE001
            print("collecting logs failed", e)
        stop_pc()
        write_report()
    failed = [r for r in results if r[0] == "FAIL"]
    print(f"{len(results)} checks, {len(failed)} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
