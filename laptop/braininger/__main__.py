"""Braininger am Laptop. Start: python -m braininger

Schaut alle 30 Sekunden in den Drive-Eingang und bearbeitet jede abgeschlossene Aufnahme nacheinander.

  --einmal          nur einen Durchgang machen und dann beenden (für Tests)
  --ordner PFAD     nur diese eine Aufnahme bearbeiten (Ordner mit info.json, z. B. eine Testaufnahme)
"""
import argparse
import logging
import msvcrt
import shutil
import sys
import time
from pathlib import Path

from . import aufnahme as aufnahme_modul
from . import einstellungen as E
from .claude_aufruf import schluessel_vorhanden
from .verarbeitung import Bearbeitung, drive_nachtragen

log = logging.getLogger("braininger")


def protokoll_einrichten() -> None:
    E.DATEN.mkdir(parents=True, exist_ok=True)
    form = logging.Formatter("%(asctime)s %(levelname)-7s %(message)s", "%Y-%m-%d %H:%M:%S")
    datei = logging.FileHandler(E.PROTOKOLL, encoding="utf-8")
    datei.setFormatter(form)
    konsole = logging.StreamHandler(sys.stdout)
    konsole.setFormatter(form)
    log.addHandler(datei)
    log.addHandler(konsole)
    log.setLevel(logging.INFO)


def nur_einmal_laufen():
    """Verhindert, dass zwei Braininger gleichzeitig dieselben Aufnahmen bearbeiten."""
    sperre = open(E.DATEN / "laeuft.sperre", "a+")
    try:
        msvcrt.locking(sperre.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        sys.exit("Braininger läuft schon.")
    return sperre  # offen halten, solange das Programm läuft


_letzte_platzwarnung = 0.0


def platz_pruefen() -> None:
    global _letzte_platzwarnung
    frei_gb = shutil.disk_usage("C:\\").free / 1e9
    if frei_gb < E.WARNGRENZE_FREI_GB and time.time() - _letzte_platzwarnung > 3600:
        _letzte_platzwarnung = time.time()
        log.warning("Auf C: sind nur noch %.1f GB frei (Warngrenze %d GB).", frei_gb, E.WARNGRENZE_FREI_GB)


def bereite_aufnahmen() -> list[aufnahme_modul.Aufnahme]:
    try:
        ordner = sorted(p for p in E.EINGANG.iterdir() if p.is_dir())
    except OSError:
        return []  # Drive für Desktop noch nicht gestartet oder offline
    fertig = []
    for o in ordner:
        a = aufnahme_modul.lesen(o)
        if a and a.abgeschlossen:
            fertig.append(a)
    return fertig


def durchgang() -> int:
    """Bearbeitet alles, was bereit ist. Rückgabe: Zahl der bearbeiteten Aufnahmen."""
    platz_pruefen()
    anzahl = 0
    for a in bereite_aufnahmen():
        b = Bearbeitung(a)
        if b.zustand.status == "fertig":
            auftrag = b.offener_auftrag()
            if auftrag:
                b.auftrag_ausfuehren(auftrag)
                anzahl += 1
            else:
                drive_nachtragen(a)
            continue
        if b.zustand.status == "fehler":
            drive_nachtragen(a)
            continue
        if not b.zustand.bereit():
            continue
        fehlend = a.fehlende_stuecke()
        if fehlend and time.time() - b.zustand.zuerst_gesehen < E.SYNC_GEDULD_S:
            b.zustand.speichern(b.lokal)  # merkt sich, seit wann wir warten
            continue
        log.info("%s: Bearbeitung startet (Modus %s, %d Stücke, Status %s)", a.id, a.modus, len(a.stuecke), a.status)
        b.ausfuehren()
        anzahl += 1
    return anzahl


def main() -> None:
    p = argparse.ArgumentParser(prog="braininger")
    p.add_argument("--einmal", action="store_true")
    p.add_argument("--ordner", type=Path)
    args = p.parse_args()

    protokoll_einrichten()
    sperre = nur_einmal_laufen()
    if not schluessel_vorhanden():
        log.error("Kein Claude-Schlüssel hinterlegt. Bitte 2_Claude_Schluessel_eintragen.bat ausführen.")

    if args.ordner:
        a = aufnahme_modul.lesen(args.ordner)
        if not a:
            sys.exit(f"Keine lesbare info.json in {args.ordner}")
        Bearbeitung(a).ausfuehren()
        return

    log.info("Braininger %s gestartet. Eingang: %s", E.LAPTOP_VERSION, E.EINGANG)
    while True:
        durchgang()
        if args.einmal:
            break
        time.sleep(E.PRUEFABSTAND_S)
    sperre.close()


if __name__ == "__main__":
    main()
