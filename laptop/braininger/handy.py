"""Aufnahmen, die die Handy-App selbst auswertet (info.json „auswertung“: „handy“, ab Grill 20.10.2026).

Die App erkennt den Text mit Whisper und schreibt die Notizen mit Claude über Jans API-Schlüssel. Der Laptop
- übernimmt fertige Handy-Ergebnisse in daten/aufnahmen/<id>/, damit Fragen sie finden,
- schreibt die Notizen selbst, wenn die App darum bittet (ergebnis.json „auswertung“: „laptop_bitte“, z. B. weil der
  Monatsdeckel erreicht ist). Den Text nimmt er dann vom Handy, statt ihn neu zu erkennen,
- wertet auf Wunsch neu aus (Auftrag „neu_auswerten“, siehe verarbeitung.py).
Solange die App noch arbeitet, fasst der Laptop die Aufnahme nicht an.
"""
import json
import logging
from pathlib import Path

from . import ergebnis
from .aufnahme import Aufnahme
from .texterkennung import Abschnitt, Text

log = logging.getLogger("braininger")


def _drive_ergebnis(a: Aufnahme) -> dict | None:
    try:
        return json.loads(a.ergebnis_datei.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def _text_sichern(lokal: Path, daten: dict) -> Text:
    """Text vom Handy als text.json/text.txt ablegen (gleiches Format wie nach der Erkennung am Laptop)."""
    abschnitte = [
        Abschnitt(float(x.get("start", 0)), float(x.get("ende", 0)), x.get("sprecher"), str(x.get("text", "")),
                  bool(x.get("pause", False)))
        for x in daten.get("abschnitte") or [] if isinstance(x, dict)
    ]
    text = Text(abschnitte, daten.get("sprache") or "de", 1.0, None, None)
    ergebnis.lokal_schreiben(lokal / "text.json", json.dumps({
        "abschnitte": [x.__dict__ for x in abschnitte],
        "sprache": text.sprache,
        "sprache_sicherheit": 1.0,
        "sprecher_anzahl": None,
        "stimmen": None,
        "dauer_audio_s": daten.get("dauer_audio_s"),
        "rechenzeit_s": 0,
        "quelle": "handy",
    }, ensure_ascii=False, indent=1))
    ergebnis.lokal_schreiben(lokal / "text.txt", text.als_zeilen())
    return text


def ganz_an_laptop(a: Aufnahme, lokal: Path) -> bool:
    """Die Spracherkennung am Handy ging nicht: der Laptop macht die Aufnahme ganz (Text aus dem Audio im Drive).
    Die Entscheidung wird lokal gemerkt, weil der Laptop die ergebnis.json danach selbst überschreibt."""
    merker = lokal / "laptop_ganz.txt"
    if merker.exists():
        return True
    d = _drive_ergebnis(a)
    if d and d.get("auswertung") == "laptop_ganz":
        ergebnis.lokal_schreiben(merker, "Spracherkennung am Handy fehlgeschlagen, Laptop übernimmt.\n")
        return True
    return False


def vorbereiten(a: Aufnahme, lokal: Path) -> str | None:
    """Rückgabe: "fertig" (Handy-Ergebnis übernommen), "laptop" (Text übernommen, der Laptop soll die Notizen
    schreiben) oder None (die App arbeitet noch)."""
    d = _drive_ergebnis(a)
    if not d or not d.get("text_fertig"):
        return None
    if d.get("status") == "fertig" and d.get("quelle") == "handy":
        _text_sichern(lokal, d)
        ergebnis.lokal_schreiben(lokal / "ergebnis.json", json.dumps(d, ensure_ascii=False, indent=1))
        log.info("%s: Ergebnis vom Handy übernommen (%d Notiz(en))", a.id, len(d.get("notizen") or []))
        return "fertig"
    if d.get("auswertung") == "laptop_bitte":
        _text_sichern(lokal, d)
        d.pop("auswertung", None)
        ergebnis.lokal_schreiben(lokal / "ergebnis.json", json.dumps(d, ensure_ascii=False, indent=1))
        log.info("%s: Handy bittet um die Notizen (%s)", a.id, d.get("meldung") or "")
        return "laptop"
    return None
