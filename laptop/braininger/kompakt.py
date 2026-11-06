"""Empfang für Braininger Lite (Mudita Kompakt), läuft im Laptop-Programm mit (ab 6.11.2026).

Der Kompakt hat keine Google-Dienste und kann nicht selbst ins Drive hochladen. Er schickt fertige Aufnahmen an diesen
Empfang. Der legt sie in den Drive-Eingang (Format wie die große App, ohne „auswertung“: „handy“), und das normale
Laptop-Programm wertet sie ganz selbst aus (Whisper + Claude). Das Ergebnis holt der Kompakt hier ab.

Weg: USB-Kabel. Die Kabelwache öffnet per „adb reverse“ den Port 8765 des Kompakt zum Laptop, sobald er angesteckt
ist, und stößt dann (und jede Minute) den Abgleich in der App an. Jan muss nur das Kabel einstecken.
Der Empfang hört nur auf 127.0.0.1, ist also aus dem WLAN nicht erreichbar.

Schutz: Jede Anfrage braucht den Schlüssel (Windows-Tresor „Braininger/kompakt_schluessel“). Namen von Aufnahmen und
Dateien werden streng geprüft, damit niemand außerhalb des Eingangs schreiben kann.
"""
import hmac
import json
import logging
import os
import re
import secrets
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import keyring

from .einstellungen import EINGANG

PORT = 8765
TRESOR = ("Braininger", "kompakt_schluessel")
ADB = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
KOMPAKT = "LD20241004967"
PAKET = "de.braininger.lite"
ID_MUSTER = re.compile(r"\d{4}-\d{2}-\d{2}_\d{6}_gedanke")
DATEI_MUSTER = re.compile(r"teil_\d{3}\.m4a|info\.json")
GROESSTE_DATEI = 600 * 1024 * 1024  # 5-Minuten-Stücke sind ca. 2,5 MB, das hier ist reichlich
KABEL_PRUEFEN_S = 10
ABGLEICH_ANSTOSSEN_S = 60

log = logging.getLogger("braininger.kompakt")


def schluessel_holen(neu_wenn_fehlt: bool = False) -> str | None:
    s = keyring.get_password(*TRESOR)
    if not s and neu_wenn_fehlt:
        s = secrets.token_urlsafe(32)
        keyring.set_password(*TRESOR, s)
    return s


class Empfang(BaseHTTPRequestHandler):
    schluessel = ""
    server_version = "BrainingerLite/1"

    def log_message(self, fmt, *args):
        log.debug("%s %s", self.client_address[0], fmt % args)

    def _antwort(self, code: int, text: str = "") -> None:
        daten = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(daten)))
        self.end_headers()
        self.wfile.write(daten)

    def _erlaubt(self) -> bool:
        gegeben = self.headers.get("X-Braininger-Schluessel", "")
        if self.schluessel and hmac.compare_digest(gegeben.encode(), self.schluessel.encode()):
            return True
        log.warning("Kompakt-Empfang: Anfrage mit falschem Schlüssel von %s", self.client_address[0])
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
            try:
                text = (EINGANG / t[2] / "ergebnis.json").read_text(encoding="utf-8")
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
        tmp = ordner / (t[3] + ".teil")
        tmp.write_bytes(daten)
        os.replace(tmp, ordner / t[3])
        if t[3] == "info.json":
            log.info("%s: Aufnahme vom Kompakt empfangen", t[2])
        self._antwort(200, '{"ok":true}')


def adb(*args: str, geraet: bool = True) -> subprocess.CompletedProcess:
    befehl = [str(ADB), *(("-s", KOMPAKT) if geraet else ()), *args]
    return subprocess.run(befehl, capture_output=True, text=True, timeout=30,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def kompakt_angesteckt() -> bool:
    try:
        r = adb("devices", geraet=False)
    except (OSError, subprocess.SubprocessError):
        return False
    return any(z.split()[:2] == [KOMPAKT, "device"] for z in r.stdout.splitlines())


def abgleich_anstossen() -> None:
    adb("shell", "am", "broadcast", "-a", f"{PAKET}.ABGLEICHEN", "-n", f"{PAKET}/.EinrichtungEmpfaenger")


def einrichten() -> str:
    """Schlüssel (falls nötig) erzeugen und an die App geben, Kabelweg öffnen."""
    s = schluessel_holen(neu_wenn_fehlt=True)
    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    r = adb("shell", "am", "broadcast", "-a", f"{PAKET}.EINRICHTEN", "-n", f"{PAKET}/.EinrichtungEmpfaenger",
            "--es", "schluessel", s)
    return "eingerichtet" if "result=1" in r.stdout else (r.stdout + r.stderr).strip()


def _kabelwache() -> None:
    """Sobald der Kompakt am Kabel hängt: Port öffnen und den Abgleich anstoßen, dann jede Minute erneut."""
    verbunden = False
    letzter_anstoss = 0.0
    while True:
        try:
            jetzt_verbunden = ADB.exists() and kompakt_angesteckt()
            if jetzt_verbunden:
                if f"tcp:{PORT}" not in adb("reverse", "--list").stdout:
                    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
                if not verbunden:
                    log.info("Kompakt am Kabel erkannt, Abgleich angestoßen")
                if not verbunden or time.monotonic() - letzter_anstoss >= ABGLEICH_ANSTOSSEN_S:
                    abgleich_anstossen()
                    letzter_anstoss = time.monotonic()
            elif verbunden:
                log.info("Kompakt vom Kabel getrennt")
            verbunden = jetzt_verbunden
        except Exception:  # die Wache darf nie das Laptop-Programm stören
            log.debug("Kabelwache: Fehler", exc_info=True)
        time.sleep(KABEL_PRUEFEN_S)


def im_hintergrund_starten() -> bool:
    """Empfang (nur 127.0.0.1) und Kabelwache als Hintergrund-Fäden starten. False, wenn kein Schlüssel da ist."""
    s = schluessel_holen()
    if not s:
        log.info("Kompakt-Empfang aus: noch kein Schlüssel (python kompakt_empfang.py einrichten)")
        return False
    Empfang.schluessel = s
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Empfang)
    except OSError as e:
        log.warning("Kompakt-Empfang: Port %d belegt (%s)", PORT, e)
        return False
    threading.Thread(target=server.serve_forever, name="kompakt-empfang", daemon=True).start()
    threading.Thread(target=_kabelwache, name="kompakt-kabelwache", daemon=True).start()
    log.info("Kompakt-Empfang läuft (Kabel, Port %d)", PORT)
    return True


