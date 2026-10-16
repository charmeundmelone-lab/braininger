"""Archiv und Papierkorb aus der Handy-App.

Die App schreibt Braininger/Ordnung/ordnung.json ({version, aufnahmen: {<id>: {zustand: archiv | papierkorb, seit}}}),
der Laptop liest sie nur. Archiv betrifft nur die Liste in der App. Aufnahmen im Papierkorb zählen nirgends mehr mit:
keine Auswertung, keine Fragen, keine Tagesübersicht, keine Notizdateien. Holt Jan sie zurück, legt
notizdateien.nachholen() die Dateien neu an und die Tagesübersicht wird neu geschrieben.
"""
import json
import logging

from . import einstellungen as E
from .notizdateien import LISTE, _liste_lesen

log = logging.getLogger("braininger")

DATEI = E.EINGANG.parent / "Ordnung" / "ordnung.json"

_stand: tuple[float, set[str]] | None = None  # (Änderungszeit der Datei, IDs im Papierkorb)


def papierkorb() -> set[str]:
    """IDs der Aufnahmen im Papierkorb. Ist die Datei gerade nicht lesbar (Drive synchronisiert), gilt der letzte Stand."""
    global _stand
    try:
        geaendert = DATEI.stat().st_mtime
    except OSError:
        return _stand[1] if _stand else set()
    if _stand and _stand[0] == geaendert:
        return _stand[1]
    try:
        aufnahmen = json.loads(DATEI.read_text(encoding="utf-8")).get("aufnahmen") or {}
    except (OSError, ValueError):
        return _stand[1] if _stand else set()
    ids = {i for i, e in aufnahmen.items() if isinstance(e, dict) and e.get("zustand") == "papierkorb"}
    if _stand is None or ids != _stand[1]:
        log.info("Papierkorb: %d Aufnahme(n)", len(ids))
    _stand = (geaendert, ids)
    return ids


def aufraeumen() -> int:
    """Notizdateien und Foto-Kopien gelöschter Aufnahmen entfernen. Text und Ergebnis bleiben in daten/aufnahmen."""
    anzahl = 0
    for aufnahme_id in papierkorb():
        lokal = E.AUFNAHMEN_LOKAL / aufnahme_id
        if not (lokal / LISTE).exists():
            continue
        for datei in _liste_lesen(lokal):
            (E.NOTIZEN / datei).unlink(missing_ok=True)
        (lokal / LISTE).unlink(missing_ok=True)
        log.info("%s: im Papierkorb, Notizdateien entfernt", aufnahme_id)
        anzahl += 1
    return anzahl
