"""Jans Aufgabenliste. Führend ist die Handy-App; sie sichert die Liste in Braininger/Aufgaben/aufgaben.json.

Der Laptop liest sie nur: Claude bekommt die offenen Aufgaben (damit nichts doppelt vorgeschlagen wird und
„erledigt“ zugeordnet werden kann), und am Laptop entsteht daraus „Braininger Notizen\\Aufgaben.md“ zum Lesen.
"""
import json
import logging
from datetime import datetime

from . import ergebnis
from . import einstellungen as E

log = logging.getLogger("braininger")

DATEI = E.EINGANG.parent / "Aufgaben" / "aufgaben.json"
ANSICHT = E.NOTIZEN / "Aufgaben.md"

_zuletzt: float | None = None


def _lesen() -> list[dict]:
    try:
        j = json.loads(DATEI.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return [a for a in j.get("aufgaben", []) if isinstance(a, dict) and a.get("id") and a.get("text")]


def offene() -> list[dict]:
    return [{"id": a["id"], "text": a["text"], "frist": a.get("frist") or ""} for a in _lesen() if not a.get("erledigt")]


def _frist_text(frist: str) -> str:
    try:
        f = datetime.fromisoformat(frist)
    except ValueError:
        return ""
    tage = ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"]
    return f"{tage[f.weekday()]} {f:%d.%m. %H:%M}"


def ansicht_schreiben() -> None:
    """Schreibt Aufgaben.md neu, sobald sich die Liste im Drive geändert hat."""
    if not E.DATEIEN_AM_DESKTOP:
        return
    global _zuletzt
    try:
        stand = DATEI.stat().st_mtime
    except OSError:
        return
    if stand == _zuletzt:
        return
    _zuletzt = stand
    alle = _lesen()
    offen = sorted((a for a in alle if not a.get("erledigt")), key=lambda a: (not a.get("frist"), a.get("frist") or ""))
    erledigt = sorted((a for a in alle if a.get("erledigt")), key=lambda a: a["erledigt"], reverse=True)[:20]
    zeilen = ["# Aufgaben", "", f"*Nur zum Lesen. Abhaken in der Braininger-App. Stand {datetime.now():%d.%m.%Y %H:%M}*", ""]
    zeilen += [f"- [ ] {a['text']}" + (f" (bis {_frist_text(a['frist'])})" if a.get("frist") else "") for a in offen]
    if not offen:
        zeilen.append("Keine offenen Aufgaben.")
    if erledigt:
        zeilen += ["", "## Zuletzt erledigt", ""] + [f"- [x] {a['text']}" for a in erledigt]
    try:
        ergebnis.lokal_schreiben(ANSICHT, "\n".join(zeilen) + "\n")
    except OSError:
        log.exception("Aufgaben.md ließ sich nicht schreiben")
