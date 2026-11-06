"""Empfang für Braininger Lite (Mudita Kompakt).

Der Kompakt hat keine Google-Dienste und kann deshalb nicht selbst ins Drive hochladen. Stattdessen schickt er
fertige Aufnahmen an diesen kleinen Empfang auf dem Laptop (WLAN oder USB-Kabel). Der Empfang legt sie in den
Drive-Eingang (gleiches Format wie die große App, ohne „auswertung“: „handy“), und das normale Laptop-Programm
wertet sie ganz selbst aus (Whisper am Laptop + Claude). Das Ergebnis holt sich der Kompakt hier wieder ab.

Aufruf:
  python kompakt_empfang.py              Empfang starten (nur für das USB-Kabel, 127.0.0.1)
  python kompakt_empfang.py --wlan       Empfang auch im WLAN (Windows fragt beim ersten Mal nach der Firewall)
  python kompakt_empfang.py einrichten   Schlüssel erzeugen und per adb an den Kompakt geben, USB-Weg öffnen

Schutz: Jede Anfrage braucht den Schlüssel (Windows-Tresor „Braininger/kompakt_schluessel“). Namen von Aufnahmen
und Dateien werden streng geprüft, damit niemand außerhalb des Eingangs schreiben kann.
"""
import hmac
import json
import logging
import os
import re
import secrets
import socket
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import keyring

sys.path.insert(0, str(Path(__file__).parent))
from braininger.einstellungen import EINGANG  # noqa: E402

PORT = 8765
TRESOR = ("Braininger", "kompakt_schluessel")
ADB = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
KOMPAKT = "LD20241004967"
ID_MUSTER = re.compile(r"\d{4}-\d{2}-\d{2}_\d{6}_gedanke")
DATEI_MUSTER = re.compile(r"teil_\d{3}\.m4a|info\.json")
GROESSTE_DATEI = 600 * 1024 * 1024  # 5-Minuten-Stücke sind ca. 2,5 MB, das hier ist reichlich

PROTOKOLL = Path(__file__).parent.parent.parent / "daten" / "kompakt_empfang.log"
PROTOKOLL.parent.mkdir(parents=True, exist_ok=True)
logging.basicConfig(filename=PROTOKOLL, level=logging.INFO, format="%(asctime)s %(message)s", encoding="utf-8")
log = logging.getLogger("kompakt")


def schluessel_holen(neu_wenn_fehlt: bool = False) -> str | None:
    s = keyring.get_password(*TRESOR)
    if not s and neu_wenn_fehlt:
        s = secrets.token_urlsafe(32)
        keyring.set_password(*TRESOR, s)
    return s


def wlan_adresse() -> str | None:
    """Eigene Adresse im Heimnetz (über welche Schnittstelle ginge es ins Internet?)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))  # Testadresse, es wird nichts gesendet
            return s.getsockname()[0]
    except OSError:
        return None


class Empfang(BaseHTTPRequestHandler):
    schluessel = ""
    server_version = "BrainingerLite/1"

    def log_message(self, fmt, *args):  # nicht auf die Konsole, ins Protokoll
        log.info("%s %s", self.client_address[0], fmt % args)

    def _antwort(self, code: int, text: str = "", art: str = "application/json") -> None:
        daten = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", art + "; charset=utf-8")
        self.send_header("Content-Length", str(len(daten)))
        self.end_headers()
        self.wfile.write(daten)

    def _erlaubt(self) -> bool:
        gegeben = self.headers.get("X-Braininger-Schluessel", "")
        if self.schluessel and hmac.compare_digest(gegeben.encode(), self.schluessel.encode()):
            return True
        self._antwort(403, '{"fehler":"Schlüssel falsch"}')
        return False

    def _teile(self) -> list[str]:
        return [t for t in self.path.split("?")[0].split("/") if t]

    def do_GET(self):
        if not self._erlaubt():
            return
        t = self._teile()
        if t == ["lite", "hallo"]:
            return self._antwort(200, '{"ok":true}')
        if len(t) == 3 and t[:2] == ["lite", "ergebnis"] and ID_MUSTER.fullmatch(t[2]):
            datei = EINGANG / t[2] / "ergebnis.json"
            try:
                text = datei.read_text(encoding="utf-8")
            except OSError:
                text = ""
            if not text.strip():
                return self._antwort(404, "{}")
            return self._antwort(200, text)
        self._antwort(404, "{}")

    def do_PUT(self):
        if not self._erlaubt():
            return
        t = self._teile()
        if not (len(t) == 4 and t[:2] == ["lite", "aufnahme"] and ID_MUSTER.fullmatch(t[2])
                and DATEI_MUSTER.fullmatch(t[3])):
            return self._antwort(400, '{"fehler":"Name nicht erlaubt"}')
        laenge = int(self.headers.get("Content-Length", "0") or 0)
        if laenge <= 0 or laenge > GROESSTE_DATEI:
            return self._antwort(400, '{"fehler":"Größe nicht erlaubt"}')
        daten = self.rfile.read(laenge)
        if len(daten) != laenge:
            return self._antwort(400, '{"fehler":"unvollständig"}')
        ordner = EINGANG / t[2]
        ordner.mkdir(parents=True, exist_ok=True)
        if t[3] == "info.json":
            try:
                info = json.loads(daten.decode("utf-8"))
            except ValueError:
                return self._antwort(400, '{"fehler":"info.json kaputt"}')
            info.pop("auswertung", None)  # Laptop wertet ganz selbst aus
            fehlen = [s for s in info.get("stuecke") or [] if not (ordner / s).exists()]
            if fehlen:
                return self._antwort(409, json.dumps({"fehler": "Stücke fehlen", "stuecke": fehlen}))
            # Der Laptop überschreibt ergebnis.json nur an Ort und Stelle, er legt sie nie selbst an.
            erg = ordner / "ergebnis.json"
            if not erg.exists():
                erg.write_bytes(b"")
            daten = json.dumps(info, ensure_ascii=False, indent=1).encode("utf-8")
        ziel = ordner / t[3]
        tmp = ordner / (t[3] + ".teil")
        tmp.write_bytes(daten)
        os.replace(tmp, ziel)
        log.info("empfangen %s/%s (%d Bytes)", t[2], t[3], len(daten))
        self._antwort(200, '{"ok":true}')


def starten(wlan: bool) -> None:
    s = schluessel_holen()
    if not s:
        print("Noch kein Schlüssel. Erst: python kompakt_empfang.py einrichten")
        sys.exit(1)
    Empfang.schluessel = s
    adresse = "0.0.0.0" if wlan else "127.0.0.1"
    server = ThreadingHTTPServer((adresse, PORT), Empfang)
    log.info("Empfang läuft auf %s:%d, Eingang %s", adresse, PORT, EINGANG)
    print(f"Empfang läuft auf {adresse}:{PORT}")
    server.serve_forever()


def adb(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), "-s", KOMPAKT, *args], capture_output=True, text=True, timeout=30)


def einrichten() -> None:
    s = schluessel_holen(neu_wenn_fehlt=True)
    r = adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    print("USB-Weg:", "offen" if r.returncode == 0 else r.stderr.strip())
    wlan = wlan_adresse()
    befehl = ["shell", "am", "broadcast", "-a", "de.braininger.lite.EINRICHTEN", "-n",
              "de.braininger.lite/.EinrichtungEmpfaenger", "--es", "schluessel", s]
    if wlan:
        befehl += ["--es", "adresse", f"http://{wlan}:{PORT}"]
    r = adb(*befehl)
    print("Kompakt:", "eingerichtet" if "result=1" in r.stdout else (r.stdout + r.stderr).strip())


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "einrichten":
        einrichten()
    else:
        starten(wlan="--wlan" in sys.argv)
