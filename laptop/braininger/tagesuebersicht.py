"""Tagesübersicht: fasst die Notizen eines Tages zusammen.

Ablage im Drive: Braininger/Uebersichten/<JJJJ-MM-TT>.json. Die Handy-App legt diese Datei vorab an, sobald
sie eine Aufnahme dieses Tages hochlädt (gleicher Grund wie bei ergebnis.json: drive.file). Der Laptop füllt sie
- automatisch, sobald der Tag vorbei ist und alle Aufnahmen des Tages ausgewertet sind (also am nächsten Morgen,
  wenn der Laptop an ist), und noch einmal, falls später weitere Aufnahmen des Tages dazukommen,
- sofort, wenn die App per Uebersichten/auftrag.json darum bittet („Tagesübersicht jetzt“).

Felder: version, datum, status (offen | in_arbeit | wartet | fertig | fehler), titel, inhalt (Markdown),
aufnahmen (berücksichtigte IDs), meldung, aktualisiert, auftrag_erledigt.
"""
import json
import logging
from datetime import date, datetime, timedelta
from pathlib import Path

from . import claude_aufruf, ergebnis
from . import einstellungen as E
from .auswertung import _system_prompt
from .vorlagen import NACH_KENNUNG

log = logging.getLogger("braininger")

UEBERSICHTEN = E.EINGANG.parent / "Uebersichten"
ZUSTAND = E.DATEN / "uebersichten_zustand.json"
LIMIT_PAUSE = timedelta(minutes=30)

SCHEMA = {
    "type": "object",
    "properties": {
        "titel": {"type": "string", "description": "Kurzer Titel für den Tag."},
        "inhalt": {"type": "string", "description": "Die Tagesübersicht in Markdown, ohne Titelzeile."},
    },
    "required": ["titel", "inhalt"],
    "additionalProperties": False,
}

AUFTRAG = """# Aufgabe

Schreib die Tagesübersicht für {wochentag}, {datum}. Grundlage sind die Notizen aller Aufnahmen dieses Tages (unten).
Gliederung:
## Der Tag in Kürze
(3–6 Sätze: was war los, Stimmung, wichtigste Ereignisse)
## Gespräche und Termine
## Ideen
## Aufgaben
(alle Aufgaben des Tages als Checkliste „- [ ] …“, doppelte zusammenfassen, Fristen behalten)
## Offene Punkte

Lass Abschnitte weg, zu denen nichts vorliegt. Erfinde nichts. Schreib auf Deutsch, knapp und konkret.
"""

WOCHENTAGE = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]


def _zustand_lesen() -> dict:
    try:
        return json.loads(ZUSTAND.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _zustand_speichern(z: dict) -> None:
    ergebnis.lokal_schreiben(ZUSTAND, json.dumps(z, ensure_ascii=False, indent=1))


def _tag_von(aufnahme_id: str) -> str:
    return aufnahme_id[:10]


def _ergebnisse_des_tages(tag: str) -> tuple[list[dict], list[str]]:
    """(fertige Ergebnisse, IDs die noch nicht fertig sind) für einen Tag, aus Eingang und lokalen Daten."""
    fertig, offen = [], []
    try:
        ordner = [p for p in E.EINGANG.iterdir() if p.is_dir() and p.name.startswith(tag)]
    except OSError:
        ordner = []
    namen = {p.name for p in ordner}
    if E.AUFNAHMEN_LOKAL.exists():
        namen |= {p.name for p in E.AUFNAHMEN_LOKAL.iterdir() if p.is_dir() and p.name.startswith(tag)}
    for name in sorted(namen):
        try:
            j = json.loads((E.AUFNAHMEN_LOKAL / name / "ergebnis.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            j = None
        if j and j.get("status") == "fertig":
            fertig.append(j)
        elif j and j.get("status") == "fehler":
            continue  # kaputte Aufnahme hält die Übersicht nicht auf
        else:
            offen.append(name)
    return fertig, offen


def _prompt(tag: str, ergebnisse: list[dict], offen: list[str]) -> str:
    d = date.fromisoformat(tag)
    teile = [AUFTRAG.format(wochentag=WOCHENTAGE[d.weekday()], datum=d.strftime("%d.%m.%Y"))]
    if offen:
        teile.append(f"Hinweis: {len(offen)} Aufnahme(n) dieses Tages sind noch nicht ausgewertet und fehlen hier.")
    teile.append("# Notizen des Tages")
    for e in ergebnisse:
        uhrzeit = f"{e['id'][11:13]}:{e['id'][13:15]}"
        for n in e.get("notizen", []):
            vorlage = NACH_KENNUNG.get(n.get("vorlage"))
            teile.append(f"## {uhrzeit} Uhr · {vorlage.name if vorlage else n.get('vorlage')} · {n.get('titel')}\n\n"
                         f"{n.get('inhalt', '').strip()}")
    return "\n\n".join(teile)


def _schreiben(datei: Path, daten: dict, tag: str) -> bool:
    return ergebnis.speichern(daten, datei, E.DATEN / "uebersichten" / f"{tag}.json")


def erstellen(tag: str, auftrag_nummer: int | None = None) -> None:
    datei = UEBERSICHTEN / f"{tag}.json"
    z = _zustand_lesen()
    eintrag = z.setdefault(tag, {})
    fertig, offen = _ergebnisse_des_tages(tag)
    daten = {"version": 1, "datum": tag, "status": "in_arbeit", "titel": None, "inhalt": None,
             "aufnahmen": [e["id"] for e in fertig], "meldung": None,
             "auftrag_erledigt": z.get("_auftrag_erledigt", 0)}
    try:
        alt = json.loads((E.DATEN / "uebersichten" / f"{tag}.json").read_text(encoding="utf-8"))
        daten.update(titel=alt.get("titel"), inhalt=alt.get("inhalt"))
    except (OSError, ValueError):
        pass

    if not fertig:
        daten.update(status="fertig", titel="Keine Aufnahmen",
                     inhalt="An diesem Tag gibt es noch keine ausgewerteten Aufnahmen.")
    else:
        _schreiben(datei, daten, tag)
        log.info("Tagesübersicht %s: %d Aufnahme(n), %d noch offen", tag, len(fertig), len(offen))
        try:
            antwort = claude_aufruf.frage(_prompt(tag, fertig, offen), zeitlimit_s=900, system_prompt=_system_prompt(),
                                          json_schema=SCHEMA, arbeitsordner=E.CLAUDE_ARBEITSORDNER)
            daten.update(status="fertig", titel=antwort.daten["titel"], inhalt=antwort.daten["inhalt"],
                         meldung=f"{len(offen)} Aufnahme(n) fehlen noch." if offen else None)
            eintrag.pop("weiter_ab", None)
        except claude_aufruf.ClaudeLimitErreicht as e:
            weiter = datetime.now().astimezone() + LIMIT_PAUSE
            log.warning("Tagesübersicht %s: Claude-Limit, neuer Versuch ab %s (%s)", tag, f"{weiter:%H:%M}", e)
            eintrag["weiter_ab"] = weiter.isoformat(timespec="seconds")
            daten.update(status="wartet", meldung=f"Claude-Limit erreicht, neuer Versuch ab {weiter:%H:%M}.")
            _schreiben(datei, daten, tag)
            _zustand_speichern(z)
            return  # Auftrag bleibt offen und wird mit dem nächsten Versuch erledigt
        except Exception as e:
            log.exception("Tagesübersicht %s fehlgeschlagen", tag)
            daten.update(status="fehler", meldung=f"Tagesübersicht hat nicht geklappt: {e}")

    if auftrag_nummer is not None:
        z["_auftrag_erledigt"] = max(auftrag_nummer, z.get("_auftrag_erledigt", 0))
        daten["auftrag_erledigt"] = z["_auftrag_erledigt"]
    eintrag["aufnahmen"] = daten["aufnahmen"] if daten["status"] == "fertig" else eintrag.get("aufnahmen", [])
    eintrag["drive_aktuell"] = _schreiben(datei, daten, tag)
    _zustand_speichern(z)
    if daten["status"] == "fertig":
        log.info("Tagesübersicht %s fertig: %s", tag, daten["titel"])


def _wartet(eintrag: dict) -> bool:
    w = eintrag.get("weiter_ab")
    return bool(w) and datetime.now().astimezone() < datetime.fromisoformat(w)


def pruefen() -> None:
    """Einmal pro Durchgang: Aufträge aus der App und automatische Übersichten für vergangene Tage."""
    try:
        dateien = sorted(UEBERSICHTEN.glob("????-??-??.json"))
    except OSError:
        return
    if not dateien and not (UEBERSICHTEN / "auftrag.json").exists():
        return
    z = _zustand_lesen()

    # 1. Auftrag aus der App
    try:
        auftrag = json.loads((UEBERSICHTEN / "auftrag.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        auftrag = None
    if auftrag and int(auftrag.get("nummer", 0)) > z.get("_auftrag_erledigt", 0):
        tag = str(auftrag.get("datum", ""))[:10]
        try:
            date.fromisoformat(tag)
        except ValueError:
            tag = ""
        if not tag:
            z["_auftrag_erledigt"] = int(auftrag.get("nummer", 0))
            _zustand_speichern(z)
        elif not _wartet(z.get(tag, {})):
            log.info("Tagesübersicht %s: Auftrag %s aus der App", tag, auftrag.get("nummer"))
            erstellen(tag, int(auftrag["nummer"]))
            z = _zustand_lesen()

    # 2. Vergangene Tage automatisch, sobald alles ausgewertet ist
    heute = date.today().isoformat()
    for datei in dateien:
        tag = datei.stem
        if tag >= heute:
            continue
        eintrag = z.get(tag, {})
        if _wartet(eintrag):
            continue
        fertig, offen = _ergebnisse_des_tages(tag)
        if offen or not fertig:
            continue
        if sorted(e["id"] for e in fertig) != sorted(eintrag.get("aufnahmen", [])):
            erstellen(tag)
            z = _zustand_lesen()
        elif not eintrag.get("drive_aktuell", True):
            # fertig, aber das Drive war beim Schreiben nicht erreichbar
            try:
                lokal = (E.DATEN / "uebersichten" / f"{tag}.json").read_text(encoding="utf-8")
                if ergebnis.an_ort_und_stelle_schreiben(datei, lokal):
                    z.setdefault(tag, {})["drive_aktuell"] = True
                    _zustand_speichern(z)
            except OSError:
                pass
