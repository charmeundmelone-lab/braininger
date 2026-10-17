"""Archiv und Papierkorb aus der Handy-App.

Die App schreibt Braininger/Ordnung/ordnung.json ({version, aufnahmen: {<id>: {zustand: archiv | papierkorb | weg, seit}}}),
der Laptop liest sie nur. Archiv betrifft nur die Liste in der App. Aufnahmen im Papierkorb zählen nirgends mehr mit:
keine Auswertung, keine Fragen, keine Tagesübersicht, keine Notizdateien. Holt Jan sie zurück, legt
notizdateien.nachholen() die Dateien neu an und die Tagesübersicht wird neu geschrieben.

Nach 30 Tagen löscht die App alles im Drive endgültig und schreibt "weg". Dann löscht der Laptop seine Kopie
(daten/aufnahmen/<id>) und Fragen-Antworten, die sich darauf stützen, und merkt sich die ID in
daten/endgueltig_geloescht.json, damit er sie nie neu auswertet. Den Eingang fasst er nie über G: an
(sonst landet es im Google-Papierkorb).
"""
import json
import logging
import shutil

from . import einstellungen as E
from .notizdateien import LISTE, _liste_lesen

log = logging.getLogger("braininger")

DATEI = E.EINGANG.parent / "Ordnung" / "ordnung.json"
WEG_LISTE = E.DATEN / "endgueltig_geloescht.json"
FRAGEN_VERLAUF = E.DATEN / "fragen"

# (Änderungszeit der Datei, IDs im Papierkorb, IDs endgültig weg)
_stand: tuple[float, set[str], set[str]] | None = None


def _lesen() -> tuple[set[str], set[str]]:
    """Ist die Datei gerade nicht lesbar (Drive synchronisiert), gilt der letzte Stand."""
    global _stand
    try:
        geaendert = DATEI.stat().st_mtime
    except OSError:
        return (_stand[1], _stand[2]) if _stand else (set(), set())
    if _stand and _stand[0] == geaendert:
        return _stand[1], _stand[2]
    try:
        aufnahmen = json.loads(DATEI.read_text(encoding="utf-8")).get("aufnahmen") or {}
    except (OSError, ValueError):
        return (_stand[1], _stand[2]) if _stand else (set(), set())

    def mit(zustand: str) -> set[str]:
        return {i for i, e in aufnahmen.items() if isinstance(e, dict) and e.get("zustand") == zustand}

    ids, weg = mit("papierkorb"), mit("weg")
    if _stand is None or ids != _stand[1]:
        log.info("Papierkorb: %d Aufnahme(n)", len(ids))
    _stand = (geaendert, ids, weg)
    return ids, weg


def papierkorb() -> set[str]:
    """IDs der Aufnahmen im Papierkorb."""
    return _lesen()[0]


def _weg_liste() -> set[str]:
    try:
        return set(json.loads(WEG_LISTE.read_text(encoding="utf-8")))
    except (OSError, ValueError):
        return set()


def geloescht() -> set[str]:
    """Alles, was nicht (mehr) ausgewertet werden darf: Papierkorb und endgültig gelöscht."""
    ids, weg = _lesen()
    return ids | weg | _weg_liste()


def _notizdateien_entfernen(aufnahme_id: str) -> bool:
    lokal = E.AUFNAHMEN_LOKAL / aufnahme_id
    if not (lokal / LISTE).exists():
        return False
    for datei in _liste_lesen(lokal):
        (E.NOTIZEN / datei).unlink(missing_ok=True)
    (lokal / LISTE).unlink(missing_ok=True)
    return True


def _fragen_entfernen(aufnahme_id: str) -> int:
    anzahl = 0
    for datei in FRAGEN_VERLAUF.glob("*.json"):
        try:
            quellen = json.loads(datei.read_text(encoding="utf-8")).get("quellen") or []
        except (OSError, ValueError, AttributeError):
            continue
        if any(isinstance(q, dict) and q.get("id") == aufnahme_id for q in quellen):
            datei.unlink(missing_ok=True)
            anzahl += 1
    return anzahl


def aufraeumen() -> int:
    """Papierkorb: Notizdateien und Foto-Kopien entfernen, Text und Ergebnis bleiben in daten/aufnahmen.
    Endgültig weg: alles Lokale zur Aufnahme löschen."""
    anzahl = 0
    ids, weg = _lesen()
    for aufnahme_id in ids:
        if _notizdateien_entfernen(aufnahme_id):
            log.info("%s: im Papierkorb, Notizdateien entfernt", aufnahme_id)
            anzahl += 1

    bekannt = _weg_liste()
    neu = weg - bekannt
    for aufnahme_id in sorted(neu):
        _notizdateien_entfernen(aufnahme_id)
        shutil.rmtree(E.AUFNAHMEN_LOKAL / aufnahme_id, ignore_errors=True)
        fragen = _fragen_entfernen(aufnahme_id)
        log.info("%s: endgültig gelöscht (%d Fragen-Antwort(en) entfernt)", aufnahme_id, fragen)
        anzahl += 1
    if neu:
        WEG_LISTE.write_text(json.dumps(sorted(bekannt | neu), ensure_ascii=False, indent=1), encoding="utf-8")
    return anzahl
