"""Erzeugt den Claude-Abo-Schlüssel mit `claude setup-token` und speichert ihn direkt
in der Windows-Anmeldeinformationsverwaltung. Der Schlüssel wird nie angezeigt und nie
in eine Datei geschrieben, man muss nichts kopieren.
"""
import os
import re
import subprocess
import sys

import keyring

from braininger.claude_aufruf import CLAUDE_EXE, KEYRING_DIENST, KEYRING_NAME, ClaudeFehler, frage

SCHLUESSEL_MUSTER = re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}")


def verdecken(text: str) -> str:
    return SCHLUESSEL_MUSTER.sub("[Schlüssel erkannt, wird sicher gespeichert]", text)


def main():
    print("Gleich öffnet sich der Browser. Melde dich bei Claude an und klicke auf 'Autorisieren'.\n")
    prozess = subprocess.Popen([str(CLAUDE_EXE), "setup-token"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    gesamt = ""
    offen = ""  # angefangene Zeile, wird erst mit ihrem Zeilenende ausgegeben
    while True:
        stueck = os.read(prozess.stdout.fileno(), 4096)
        if not stueck:
            break
        text = stueck.decode("utf-8", errors="replace")
        gesamt += text
        *fertige, offen = (offen + text).split("\n")
        for zeile in fertige:
            print(verdecken(zeile), flush=True)
    prozess.wait()
    if offen:
        print(verdecken(offen))

    treffer = SCHLUESSEL_MUSTER.findall(gesamt)
    if not treffer:
        print("\nIch habe keinen Schlüssel gefunden. Bitte sag Claude im Chat Bescheid, was oben steht.")
        raise SystemExit(1)

    keyring.set_password(KEYRING_DIENST, KEYRING_NAME, treffer[-1])
    print("\nSchlüssel sicher gespeichert. Ich teste die Verbindung ...")
    try:
        print("Claude sagt:", frage("Antworte nur mit: Braininger ist verbunden.").text)
        print("\nAlles fertig. Du kannst dieses Fenster schließen.")
    except ClaudeFehler as fehler:
        print("Test fehlgeschlagen:", fehler)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
