r"""Claude-API-Schlüssel für die Handy-App: am Laptop sicher ablegen und per adb aufs Handy schicken.

Der Schlüssel liegt nur in der Windows-Anmeldeinformationsverwaltung („Braininger / claude_api_schluessel“),
nie in einer Datei, nie im Projekt, nie im Protokoll. Dieses Skript gibt ihn nie aus, auch nicht teilweise.

  pythonw api_schluessel.py eintragen   kleines Fenster: Schlüssel einfügen (Strg+V) und speichern
  python  api_schluessel.py handy       Schlüssel an die Braininger-App schicken (Handy per WLAN-adb verbunden)

Die App nimmt den Schlüssel nur von adb an: Ihr Empfänger verlangt die Berechtigung WRITE_SECURE_SETTINGS,
die außer dem System nur die adb-Shell hat (und Braininger selbst). Andere Apps können also keinen fremden
Schlüssel unterschieben.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

import keyring

DIENST = "Braininger"
NAME = "claude_api_schluessel"
MUSTER = re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}")
ADB = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
AKTION = "de.braininger.SCHLUESSEL_SETZEN"
EMPFAENGER = "de.braininger.app/.SchluesselEmpfaenger"


def eintragen() -> None:
    import tkinter as tk

    fenster = tk.Tk()
    fenster.title("Braininger – Claude-Schlüssel")
    fenster.geometry("520x210")
    fenster.attributes("-topmost", True)
    tk.Label(fenster, text="Claude-API-Schlüssel hier einfügen (Strg+V), dann „Speichern“:",
             font=("Segoe UI", 11)).pack(pady=(18, 8))
    feld = tk.Entry(fenster, show="•", width=52, font=("Segoe UI", 11))
    feld.pack()
    feld.focus_set()
    meldung = tk.Label(fenster, text="", font=("Segoe UI", 10))
    meldung.pack(pady=6)

    def speichern(_=None) -> None:
        wert = feld.get().strip()
        if not MUSTER.fullmatch(wert):
            meldung.config(text="Das sieht nicht wie ein Claude-Schlüssel aus (beginnt mit sk-ant-).", fg="#b00020")
            return
        keyring.set_password(DIENST, NAME, wert)
        feld.delete(0, tk.END)
        meldung.config(text="Gespeichert. Du kannst das Fenster schließen.", fg="#1b7f3b")

    feld.bind("<Return>", speichern)
    tk.Button(fenster, text="Speichern", command=speichern, font=("Segoe UI", 11), width=14).pack(pady=4)
    fenster.mainloop()


def _adb(*args: str, zeit: int = 20) -> subprocess.CompletedProcess:
    return subprocess.run([str(ADB), *args], capture_output=True, text=True, timeout=zeit,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def _handy() -> str | None:
    """Seriennummer des verbundenen Handys (sucht per mdns und verbindet, wenn nötig)."""
    def verbunden() -> str | None:
        for zeile in _adb("devices").stdout.splitlines()[1:]:
            teile = zeile.split()
            if len(teile) == 2 and teile[1] == "device" and not teile[0].startswith("emulator"):
                return teile[0]
        return None

    s = verbunden()
    if s:
        return s
    for zeile in _adb("mdns", "services").stdout.splitlines():
        if "_adb-tls-connect" in zeile:
            _adb("connect", zeile.split()[-1])
            s = verbunden()
            if s:
                return s
    return None


def handy() -> int:
    wert = keyring.get_password(DIENST, NAME)
    if not wert:
        print("Kein Schlüssel gespeichert. Erst „eintragen“ ausführen.")
        return 1
    serie = _handy()
    if not serie:
        print("Handy nicht gefunden. Ist „Kabelloses Debugging“ an und das Handy im selben WLAN?")
        return 1
    # Der Schlüssel geht nur als Argument an adb (lokal, nicht im Protokoll); ausgegeben wird nur das Ergebnis
    lauf = _adb("-s", serie, "shell", "am", "broadcast", "-a", AKTION, "-n", EMPFAENGER, "--es", "schluessel", wert)
    del wert
    ok = lauf.returncode == 0 and "result=1" in lauf.stdout
    print("Schlüssel an Braininger übertragen." if ok else "Übertragen hat nicht geklappt (App 0.1.41 oder neuer installiert?).")
    return 0 if ok else 1


if __name__ == "__main__":
    was = sys.argv[1] if len(sys.argv) > 1 else "eintragen"
    if was == "handy":
        sys.exit(handy())
    eintragen()
