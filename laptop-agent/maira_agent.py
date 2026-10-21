#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MYRA Laptop Agent — phone (MYRA app) se is Windows laptop ko control karta hai.

Dependencies (run_maira.bat khud install kar deta hai):
    pip install paho-mqtt pyautogui pillow

Chalana:
    python maira_agent.py      (ya run_maira.bat double-click karo)

Pehli dafa pairing code manga jayega — wahi code jo phone ki MYRA app ki
Settings > KEYS & DATA > Laptop Pairing Code mein dala hai. Code
maira_config.json mein save ho jata hai. Is code ko password ki tarah
rakho: jiske paas code hai, woh is broker par tumhare laptop ko command
bhej sakta hai.
"""

import base64
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import webbrowser
from io import BytesIO

try:
    import paho.mqtt.client as mqtt
except ImportError:
    print("paho-mqtt nahi mila. Chalao: pip install paho-mqtt pyautogui pillow")
    sys.exit(1)

try:
    import pyautogui
    pyautogui.FAILSAFE = False  # corner-abort band: commands phone se aate hain
except ImportError:
    pyautogui = None
    print("WARNING: pyautogui nahi mila — mouse/keyboard/volume wale kaam nahi honge.")

try:
    from PIL import ImageGrab
except ImportError:
    ImageGrab = None
    print("WARNING: Pillow nahi mila — capture_screen nahi chalega.")

BROKER = "broker.emqx.io"
PORT = 1883
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "maira_config.json")
HOME = os.path.expanduser("~")

NAMED_DIRS = {
    "desktop": os.path.join(HOME, "Desktop"),
    "documents": os.path.join(HOME, "Documents"),
    "downloads": os.path.join(HOME, "Downloads"),
}

PAIRING_CODE = ""
CMD_TOPIC = ""
RESULT_TOPIC = ""


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


# ---------------------------------------------------------------- config

def load_or_ask_code():
    code = ""
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                code = (json.load(f).get("pairing_code") or "").strip()
        except Exception:
            code = ""
    if not code:
        print("=" * 60)
        print("Pehli dafa setup: apna pairing code likho.")
        print("Wahi code jo phone ki MYRA app ki Settings mein dala hai")
        print("(KEYS & DATA > Laptop Pairing Code).")
        print("=" * 60)
        code = input("Apna pairing code likho (jo phone ki Settings main hai): ").strip()
        if not code:
            print("Code khaali hai — band ho raha hai.")
            sys.exit(1)
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump({"pairing_code": code}, f)
            print("Code save ho gaya (maira_config.json).")
        except Exception as e:
            print("Code save nahi ho saka:", e)
    return code


# ---------------------------------------------------------------- paths

def resolve_path(raw, default_key="desktop"):
    """User ki boli hui jagah ko asal path mein badlo.

    'desktop' / 'documents' / 'downloads' (ya un se shuru hone wala
    'Desktop/Test' jaisa path) ko ghar ke un folders par le jata hai.
    Poora (absolute) path waisa ka waisa. Sirf naam ho to default folder
    (Desktop) ke andar samjha jata hai.
    """
    if not raw:
        return NAMED_DIRS[default_key]
    raw = str(raw).strip().strip('"')
    if not raw:
        return NAMED_DIRS[default_key]
    if raw.lower() in NAMED_DIRS:
        return NAMED_DIRS[raw.lower()]
    parts = raw.replace("/", os.sep).split(os.sep)
    if parts and parts[0].lower() in NAMED_DIRS:
        return os.path.normpath(os.path.join(NAMED_DIRS[parts[0].lower()], *parts[1:]))
    if os.path.isabs(raw):
        return os.path.normpath(raw)
    return os.path.normpath(os.path.join(NAMED_DIRS[default_key], raw))


def is_allowed(path):
    """Delete/rename ki hifazat: sirf user ke home folder ke ANDAR wala
    path chalega. Drive root (C:\\), home folder khud, aur bahar ki har
    jagah (C:\\Windows waghera) mana hai."""
    try:
        ap = os.path.abspath(path)
        home = os.path.abspath(HOME)
        if ap == home:
            return False
        if os.path.splitdrive(ap)[1].strip("\\/") == "":
            return False  # drive root
        return os.path.commonpath([ap, home]) == home
    except Exception:
        return False


# ---------------------------------------------------------------- results

def publish_result(client, msg_id, ok, text=None, image_b64=None):
    if not msg_id:
        return
    payload = {"id": msg_id, "ok": bool(ok)}
    if text is not None:
        payload["text"] = text
    if image_b64 is not None:
        payload["image_b64"] = image_b64
    try:
        client.publish(RESULT_TOPIC, json.dumps(payload), qos=0)
    except Exception as e:
        log("Result bhejna fail: %s" % e)


# ---------------------------------------------------------------- handlers
# Har handler (client, msg) leta hai aur (ok, text) wapas karta hai —
# text sirf result/log ke liye hai.

def h_shutdown(client, msg):
    subprocess.Popen(["shutdown", "/s", "/t", "10"])
    return True, "Laptop 10 second mein band ho jayega."


def h_restart(client, msg):
    subprocess.Popen(["shutdown", "/r", "/t", "10"])
    return True, "Laptop 10 second mein restart ho jayega."


def h_sleep(client, msg):
    subprocess.Popen(["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"])
    return True, "Laptop sleep par ja raha hai."


def _amount(msg, default=5, cap=20):
    try:
        a = int(msg.get("amount") or default)
    except Exception:
        a = default
    return max(1, min(cap, a))


def h_volume_up(client, msg):
    for _ in range(_amount(msg)):
        pyautogui.press("volumeup")
    return True, "Volume barha diya."


def h_volume_down(client, msg):
    for _ in range(_amount(msg)):
        pyautogui.press("volumedown")
    return True, "Volume kam kar diya."


def h_mute(client, msg):
    pyautogui.press("volumemute")
    return True, "Mute toggle kar diya."


def h_create_folder(client, msg):
    name = (msg.get("name") or "").strip().strip("/\\")
    if name in (".", ".."):
        name = ""
    base = resolve_path(msg.get("path"), "desktop")
    target = os.path.join(base, name) if name else base
    os.makedirs(target, exist_ok=True)
    return True, "Folder ban gaya: %s" % target


def h_delete_file(client, msg):
    target = resolve_path(msg.get("path"), "desktop")
    if not os.path.exists(target):
        return False, "Ye path mila hi nahi: %s" % target
    if not is_allowed(target):
        return False, "MANA: ye jagah hifazati dairay se bahar hai (%s). Sirf Desktop, Documents, Downloads aur home folder ke andar delete ho sakta hai." % target
    if os.path.isdir(target):
        try:
            os.rmdir(target)  # khaali folder
        except OSError:
            shutil.rmtree(target)  # bhara folder — allowed roots ke andar hi
    else:
        os.remove(target)
    return True, "Delete ho gaya: %s" % target


def h_rename_file(client, msg):
    src = resolve_path(msg.get("path"), "desktop")
    new_name = (msg.get("new_name") or "").strip()
    if not new_name:
        return False, "new_name nahi diya gaya."
    if not os.path.exists(src):
        return False, "Ye path mila hi nahi: %s" % src
    dst = os.path.join(os.path.dirname(src), os.path.basename(new_name))
    if not is_allowed(src) or not is_allowed(dst):
        return False, "MANA: rename sirf home folder ke andar ho sakta hai."
    os.rename(src, dst)
    return True, "Naam badal diya: %s -> %s" % (src, dst)


def h_list_directory(client, msg):
    folder = resolve_path(msg.get("path"), "desktop")
    if not os.path.isdir(folder):
        return False, "Ye folder nahi mila: %s" % folder
    names = sorted(os.listdir(folder))
    lines = []
    for n in names[:200]:
        full = os.path.join(folder, n)
        lines.append(("[DIR]  " if os.path.isdir(full) else "       ") + n)
    if len(names) > 200:
        lines.append("... aur %d cheezein (list lambi hai, pehli 200 dikhayi)" % (len(names) - 200))
    if not lines:
        lines.append("(folder khaali hai)")
    return True, ("%s ki list:\n" % folder) + "\n".join(lines)


def _chrome_path():
    cands = [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        os.path.join(os.environ.get("LOCALAPPDATA", ""), r"Google\Chrome\Application\chrome.exe"),
    ]
    for c in cands:
        if c and os.path.exists(c):
            return c
    return None


def h_open_browser(client, msg):
    chrome = _chrome_path()
    if chrome:
        subprocess.Popen([chrome])
        return True, "Chrome khol diya."
    webbrowser.open("about:blank")
    return True, "Default browser khol diya."


def h_open_url(client, msg):
    url = (msg.get("url") or "").strip()
    if not url:
        return False, "URL nahi diya gaya."
    if not url.lower().startswith(("http://", "https://")):
        url = "https://" + url
    webbrowser.open(url)
    return True, "Khol diya: %s" % url


def h_scroll_up(client, msg):
    pyautogui.scroll(_amount(msg) * 100)
    return True, "Upar scroll kar diya."


def h_scroll_down(client, msg):
    pyautogui.scroll(-_amount(msg) * 100)
    return True, "Neeche scroll kar diya."


def h_switch_tab(client, msg):
    pyautogui.hotkey("ctrl", "tab")
    return True, "Aglay tab par gaya."


def h_close_tab(client, msg):
    pyautogui.hotkey("ctrl", "w")
    return True, "Tab band kar diya."


def _paste_text(text):
    """Unicode-safe type: clipboard (tkinter — stdlib) + Ctrl+V.
    Urdu/emoji bhi chalta hai. Fail ho to ASCII typewrite fallback."""
    try:
        import tkinter as tk
        root = tk.Tk()
        root.withdraw()
        root.clipboard_clear()
        root.clipboard_append(text)
        root.update()
        pyautogui.hotkey("ctrl", "v")
        root.destroy()
        return True
    except Exception:
        pass
    try:
        pyautogui.typewrite(text, interval=0.02)  # sirf ASCII ke liye theek hai
        return True
    except Exception:
        return False


def h_type_text(client, msg):
    text = msg.get("text") or ""
    if not text:
        return False, "Likhnay ke liye text nahi diya gaya."
    time.sleep(0.3)  # active window ko settle ہونے do
    if _paste_text(text):
        return True, "Likh diya (%d characters)." % len(text)
    return False, "Text type nahi ho saka."


_KEY_ALIASES = {"esc": "esc", "escape": "esc", "enter": "enter", "return": "enter",
                "win": "win", "windows": "win", "del": "delete", "space": "space",
                "ctrl": "ctrl", "control": "ctrl", "alt": "alt", "shift": "shift"}


def h_press_key(client, msg):
    key = (msg.get("key") or "").strip().lower()
    if not key:
        return False, "key nahi di gayi."
    parts = [_KEY_ALIASES.get(p.strip(), p.strip()) for p in key.split("+") if p.strip()]
    if not parts:
        return False, "key samajh nahi aayi."
    if len(parts) == 1:
        pyautogui.press(parts[0])
    else:
        pyautogui.hotkey(*parts)
    return True, "Key dabayi: %s" % key


def h_mouse_click(client, msg):
    if msg.get("x") is not None and msg.get("y") is not None:
        pyautogui.click(int(msg["x"]), int(msg["y"]))
        return True, "Click kar diya (%s, %s)." % (msg["x"], msg["y"])
    pyautogui.click()
    return True, "Click kar diya (jahan mouse tha)."


def h_capture_screen(client, msg):
    if ImageGrab is None:
        return False, "Pillow install nahi — screenshot nahi ho sakta."
    img = ImageGrab.grab()
    if img.mode != "RGB":
        img = img.convert("RGB")
    if img.width > 1280:
        h = int(img.height * 1280 / img.width)
        img = img.resize((1280, h))
    buf = BytesIO()
    img.save(buf, format="JPEG", quality=60)
    data = buf.getvalue()
    if len(data) > 1_500_000:  # MQTT par bohat bara na ho — quality aur kam
        buf = BytesIO()
        img.save(buf, format="JPEG", quality=45)
        data = buf.getvalue()
    publish_result(client, msg.get("id"), True,
                   text="Screenshot bhej diya (%dx%d)." % (img.width, img.height),
                   image_b64=base64.b64encode(data).decode("ascii"))
    return True, None  # result pehle hi bhej diya (tasveer ke saath)


def _launch_first(candidates):
    for c in candidates:
        if not c:
            continue
        for path in glob.glob(c):
            if os.path.exists(path):
                subprocess.Popen([path])
                return True
        if os.path.isabs(c) and os.path.exists(c):
            subprocess.Popen([c])
            return True
        if shutil.which(c):
            subprocess.Popen([c])
            return True
    return False


def h_open_app(client, msg):
    app = (msg.get("app") or "").strip()
    if not app:
        return False, "app ka naam nahi diya gaya."
    key = app.lower()
    la = os.environ.get("LOCALAPPDATA", "")
    pf = r"C:\Program Files"
    pfx = r"C:\Program Files (x86)"
    mapping = {
        "chrome": [os.path.join(pf, r"Google\Chrome\Application\chrome.exe"),
                   os.path.join(pfx, r"Google\Chrome\Application\chrome.exe"),
                   os.path.join(la, r"Google\Chrome\Application\chrome.exe"), "chrome"],
        "google chrome": None,  # neeche chrome par map hota hai
        "vscode": ["code"],
        "vs code": ["code"],
        "visual studio code": ["code"],
        "vlc": [os.path.join(pf, r"VideoLAN\VLC\vlc.exe"),
                os.path.join(pfx, r"VideoLAN\VLC\vlc.exe"), "vlc"],
        "notepad": ["notepad"],
        "premiere": [os.path.join(pf, r"Adobe\Adobe Premiere Pro *\Adobe Premiere Pro.exe"),
                     os.path.join(pf, r"Adobe\Adobe Premiere Pro*\Adobe Premiere Pro.exe")],
        "adobe premiere": None,
        "premiere pro": None,
        "explorer": ["explorer"],
        "file explorer": ["explorer"],
        "calculator": ["calc"],
        "paint": ["mspaint"],
        "word": ["winword"],
        "winword": ["winword"],
    }
    if key in ("google chrome",):
        key = "chrome"
    if key in ("adobe premiere", "premiere pro"):
        key = "premiere"
    cands = mapping.get(key)
    if cands and _launch_first(cands):
        return True, "%s khol diya." % app
    # Aakhri koshish: Windows Start se naam se kholo
    try:
        os.system('start "" "%s"' % app.replace('"', ""))
        return True, "%s kholne ki koshish ki (Start se)." % app
    except Exception as e:
        return False, "%s nahi khul saka: %s" % (app, e)


HANDLERS = {
    "shutdown": h_shutdown,
    "restart": h_restart,
    "sleep": h_sleep,
    "volume_up": h_volume_up,
    "volume_down": h_volume_down,
    "mute": h_mute,
    "create_folder": h_create_folder,
    "delete_file": h_delete_file,
    "rename_file": h_rename_file,
    "list_directory": h_list_directory,
    "open_browser": h_open_browser,
    "open_url": h_open_url,
    "scroll_up": h_scroll_up,
    "scroll_down": h_scroll_down,
    "switch_tab": h_switch_tab,
    "close_tab": h_close_tab,
    "type_text": h_type_text,
    "press_key": h_press_key,
    "mouse_click": h_mouse_click,
    "capture_screen": h_capture_screen,
    "open_app": h_open_app,
}

NEEDS_GUI = {"volume_up", "volume_down", "mute", "scroll_up", "scroll_down",
             "switch_tab", "close_tab", "type_text", "press_key", "mouse_click"}


# ---------------------------------------------------------------- mqtt

def on_connect(client, userdata, flags, rc):
    if rc == 0:
        client.subscribe(CMD_TOPIC, qos=1)
        log("Broker se connect ho gaya. Commands ka intezaar hai...")
        log("Topic: %s" % CMD_TOPIC)
    else:
        log("Connect fail, code: %s" % rc)


def on_disconnect(client, userdata, rc):
    log("Connection toota (code %s) — paho khud dobara koshish karega." % rc)


def on_message(client, userdata, message):
    try:
        msg = json.loads(message.payload.decode("utf-8"))
    except Exception:
        log("Samajh na aane wala message aaya — ignore.")
        return
    if msg.get("code") != PAIRING_CODE:
        log("Ghalat pairing code wala message ignore kiya.")
        return
    action = msg.get("action") or ""
    msg_id = msg.get("id")
    log("Command: %s" % action)
    handler = HANDLERS.get(action)
    if handler is None:
        publish_result(client, msg_id, False, "Ye action agent nahi janta: %s" % action)
        return
    if action in NEEDS_GUI and pyautogui is None:
        publish_result(client, msg_id, False, "pyautogui install nahi — ye kaam nahi ho sakta.")
        return
    try:
        ok, text = handler(client, msg)
        if text is not None:
            publish_result(client, msg_id, ok, text)
        log(("OK   " if ok else "FAIL ") + action + ((" — " + text) if text else ""))
    except Exception as e:
        log("ERROR %s: %s" % (action, e))
        publish_result(client, msg_id, False, "Agent par error: %s" % e)


def main():
    global PAIRING_CODE, CMD_TOPIC, RESULT_TOPIC
    PAIRING_CODE = load_or_ask_code()
    CMD_TOPIC = "myra/%s/cmd" % PAIRING_CODE
    RESULT_TOPIC = "myra/%s/result" % PAIRING_CODE

    client = mqtt.Client(client_id="myra-laptop-%d" % int(time.time()),
                         clean_session=True)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message
    client.reconnect_delay_set(min_delay=1, max_delay=30)

    print("=" * 60)
    print("MYRA Laptop Agent chal para hai.")
    print("Band karne ke liye ye window band kar do (ya Ctrl+C).")
    print("=" * 60)
    while True:
        try:
            client.connect(BROKER, PORT, keepalive=60)
            client.loop_forever()  # disconnect par khud reconnect karta hai
        except KeyboardInterrupt:
            print("Band ho raha hai...")
            break
        except Exception as e:
            log("Connection masla: %s — 5 second baad dobara koshish." % e)
            time.sleep(5)


if __name__ == "__main__":
    main()
