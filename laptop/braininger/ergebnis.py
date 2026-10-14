"""ergebnis.json: was der Laptop der Handy-App über eine Aufnahme mitteilt.

Die Handy-App legt die Datei beim Hochladen leer an. Nur so kann sie die Datei später lesen
(Berechtigung drive.file, siehe Frühtest a im Bauplan). Der Laptop überschreibt sie deshalb immer an Ort
und Stelle und legt sie nie selbst neu an. Eine lokale Kopie liegt zusätzlich in daten/aufnahmen/<id>/.

Felder (version 1):
  status        "in_arbeit" | "wartet" | "fertig" | "fehler"
  schritt       "sprecher" | "text" | "auswertung" | null   (nur bei in_arbeit)
  fortschritt   0..1 innerhalb des Schritts
  meldung       Text für die App (z. B. Grund fürs Warten oder der Fehler)
  weiter_ab     ISO-Zeit, ab der es nach einem Claude-Limit weitergeht
  text_fertig   true, sobald der Text gesichert ist. Erst dann darf die App das Audio löschen.
  kurz, notizen[], sprecher[], abschnitte[], sprache, hinweise[], dauer_audio_s
  auftrag_erledigt  Nummer des zuletzt erledigten Auftrags aus auftrag.json (legt die App an)
"""
import json
import os
from datetime import datetime
from pathlib import Path

from .einstellungen import LAPTOP_VERSION


def jetzt_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def neu(aufnahme_id: str) -> dict:
    return {
        "version": 1,
        "id": aufnahme_id,
        "status": "in_arbeit",
        "schritt": None,
        "fortschritt": 0.0,
        "meldung": None,
        "weiter_ab": None,
        "text_fertig": False,
        "kurz": None,
        "notizen": [],
        "sprecher": [],
        "abschnitte": [],
        "sprache": None,
        "hinweise": [],
        "dauer_audio_s": None,
        "auftrag_erledigt": 0,
        "aktualisiert": None,
        "laptop_version": LAPTOP_VERSION,
    }


def an_ort_und_stelle_schreiben(datei: Path, inhalt: str) -> bool:
    """Überschreibt eine vorhandene Datei, ohne sie neu anzulegen (Drive-ID bleibt gleich).
    False, wenn die Datei (noch) nicht da ist."""
    try:
        with open(datei, "r+", encoding="utf-8", newline="\n") as f:
            f.seek(0)
            f.write(inhalt)
            f.truncate()
            f.flush()
            os.fsync(f.fileno())
        return True
    except FileNotFoundError:
        return False


def lokal_schreiben(datei: Path, inhalt: str) -> None:
    datei.parent.mkdir(parents=True, exist_ok=True)
    tmp = datei.with_suffix(".tmp")
    tmp.write_text(inhalt, encoding="utf-8", newline="\n")
    os.replace(tmp, datei)


def speichern(daten: dict, drive_datei: Path, lokale_datei: Path) -> bool:
    """Schreibt lokal und (falls von der App angelegt) ins Drive. Rückgabe: ob das Drive erreicht wurde."""
    daten["aktualisiert"] = jetzt_iso()
    inhalt = json.dumps(daten, ensure_ascii=False, indent=1)
    lokal_schreiben(lokale_datei, inhalt)
    try:
        return an_ort_und_stelle_schreiben(drive_datei, inhalt)
    except OSError:
        return False  # Drive gerade nicht erreichbar; lokal ist es gesichert, nächster Versuch beim nächsten Speichern
