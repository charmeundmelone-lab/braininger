"""Notizen und Tagesübersichten als Markdown-Dateien in „Claude Projekte\\Braininger Notizen“.

Zum Lesen am Laptop und damit Claude später in allen Notizen suchen kann.
Aufbau: <Monat>\\<Datum Uhrzeit> <Titel>.md, Tagesübersichten als <Monat>\\<Datum> Tagesübersicht.md.
Welche Dateien zu einer Aufnahme gehören, steht in daten/aufnahmen/<id>/notizdateien.json. So werden
beim Ändern der Vorlage die alten Dateien ersetzt, ohne fremde Dateien im Ordner anzufassen.
"""
import json
import logging
import re
from datetime import datetime
from pathlib import Path

from . import ergebnis
from . import einstellungen as E
from .vorlagen import NACH_KENNUNG

log = logging.getLogger("braininger")

LISTE = "notizdateien.json"
LEER_TITEL = "Kein verwertbarer Inhalt"  # so nennt auswertung.py leere Aufnahmen
_VERBOTEN = re.compile(r'[<>:"/\\|?*\x00-\x1f]')


def dateiname(text: str, laenge: int = 80) -> str:
    """Macht aus einem Titel einen gültigen Windows-Dateinamen."""
    name = _VERBOTEN.sub(" ", text)
    name = re.sub(r"\s+", " ", name).strip()[:laenge].rstrip(" .")
    return name or "Ohne Titel"


def zeit_aus_id(aufnahme_id: str) -> datetime:
    """„2026-10-15_090034_gedanke“ → 15.10.2026 09:00:34"""
    return datetime.strptime(aufnahme_id[:17], "%Y-%m-%d_%H%M%S")


def _liste_lesen(lokal: Path) -> list[str]:
    try:
        return json.loads((lokal / LISTE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []


def ablegen(aufnahme_id: str, modus: str, notizen: list[dict], lokal: Path) -> list[Path]:
    """Schreibt alle Notizen einer Aufnahme und ersetzt ihre früheren Dateien."""
    zeit = zeit_aus_id(aufnahme_id)
    ordner = E.NOTIZEN / f"{zeit:%Y-%m}"
    for alt in _liste_lesen(lokal):
        (E.NOTIZEN / alt).unlink(missing_ok=True)

    neu: list[Path] = []
    for n in notizen:
        if n.get("titel") == LEER_TITEL:
            continue  # leere Aufnahme: in der App sichtbar, im Notizordner nur Rauschen
        vorlage = NACH_KENNUNG.get(n.get("vorlage"))
        vorlage_name = vorlage.name if vorlage else str(n.get("vorlage"))
        basis = f"{zeit:%Y-%m-%d %H.%M} {dateiname(n.get('titel', ''))}"
        datei = ordner / f"{basis}.md"
        nummer = 2
        while datei in neu:  # zwei Notizen mit gleichem Titel aus derselben Aufnahme
            datei = ordner / f"{basis} ({nummer}).md"
            nummer += 1
        kopf = (
            "---\n"
            f"datum: {zeit:%Y-%m-%d %H:%M}\n"
            f"vorlage: {vorlage_name}\n"
            f"modus: {modus}\n"
            f"aufnahme: {aufnahme_id}\n"
            "---\n\n"
            f"# {n.get('titel', '')}\n\n"
        )
        ergebnis.lokal_schreiben(datei, kopf + str(n.get("inhalt", "")).strip() + "\n")
        neu.append(datei)

    ergebnis.lokal_schreiben(lokal / LISTE, json.dumps(
        [str(p.relative_to(E.NOTIZEN)) for p in neu], ensure_ascii=False, indent=1))
    return neu


def uebersicht_ablegen(tag: str, titel: str, inhalt: str) -> Path:
    """Tagesübersicht eines Tages (JJJJ-MM-TT). Wird bei jeder neuen Fassung überschrieben."""
    datei = E.NOTIZEN / tag[:7] / f"{tag} Tagesübersicht.md"
    kopf = f"---\ndatum: {tag}\nvorlage: Tagesübersicht\n---\n\n# {titel}\n\n"
    ergebnis.lokal_schreiben(datei, kopf + inhalt.strip() + "\n")
    return datei


def nachholen() -> int:
    """Legt Dateien für fertige Aufnahmen an, die noch keine haben (z. B. aus der Zeit vor dieser Funktion)."""
    anzahl = 0
    try:
        ordner = sorted(p for p in E.AUFNAHMEN_LOKAL.iterdir() if p.is_dir())
    except OSError:
        return 0
    for lokal in ordner:
        if (lokal / LISTE).exists():
            continue
        try:
            daten = json.loads((lokal / "ergebnis.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if daten.get("status") != "fertig" or not daten.get("notizen"):
            continue
        modus = lokal.name.rsplit("_", 1)[-1]
        try:
            ablegen(lokal.name, modus, daten["notizen"], lokal)
            anzahl += 1
        except (OSError, ValueError):
            log.exception("%s: Notizdateien ließen sich nicht anlegen", lokal.name)

    for j in sorted((E.DATEN / "uebersichten").glob("*.json")):
        tag = j.stem
        if (E.NOTIZEN / tag[:7] / f"{tag} Tagesübersicht.md").exists():
            continue
        try:
            u = json.loads(j.read_text(encoding="utf-8"))
            if u.get("status") == "fertig" and u.get("aufnahmen") and u.get("inhalt"):
                uebersicht_ablegen(tag, u.get("titel") or "Tagesübersicht", u["inhalt"])
                anzahl += 1
        except (OSError, ValueError):
            log.exception("Tagesübersicht %s: Datei ließ sich nicht anlegen", tag)
    return anzahl
