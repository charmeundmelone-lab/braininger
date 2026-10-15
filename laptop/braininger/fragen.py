"""Fragen an alle Aufnahmen: die App stellt eine Frage, Claude antwortet aus allen Notizen.

Ablage im Drive: Braininger/Fragen/frage.json (App schreibt: nummer, frage) und antwort.json. Beide legt die App
an (drive.file, siehe Frühtest a), der Laptop überschreibt antwort.json nur an Ort und Stelle.
antwort.json: version, nummer, frage, status (in_arbeit | wartet | fertig | fehler), antwort (Markdown),
quellen [{id, datum, kurz}], meldung, aktualisiert.

Claude bekommt alle Notizen (neueste zuerst, bis zur Grenze) und dazu den ganzen Text der Aufnahmen,
in denen Wörter der Frage vorkommen (für Details, die nicht in den Notizen stehen).
"""
import json
import logging
import re
from datetime import datetime, timedelta

from . import claude_aufruf, ergebnis
from . import einstellungen as E
from .auswertung import _system_prompt
from .vorlagen import NACH_KENNUNG

log = logging.getLogger("braininger")

ORDNER = E.EINGANG.parent / "Fragen"
ZUSTAND = E.DATEN / "fragen_zustand.json"
VERLAUF = E.DATEN / "fragen"
LIMIT_PAUSE = timedelta(minutes=30)
MAX_NOTIZEN_ZEICHEN = 150_000
MAX_TEXT_ZEICHEN = 60_000
FUELLWOERTER = {"was", "wer", "wie", "wann", "wo", "warum", "welche", "welcher", "welches", "hat", "habe", "haben",
                "ist", "sind", "war", "waren", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen",
                "und", "oder", "mit", "von", "zum", "zur", "über", "für", "auf", "bei", "ich", "mir", "mich", "mein",
                "meine", "letzte", "letzten", "woche", "heute", "gestern", "gesagt", "denn", "noch", "schon", "alle"}

SCHEMA = {
    "type": "object",
    "properties": {
        "antwort": {"type": "string", "description": "Die Antwort in Markdown, knapp und konkret."},
        "quellen": {"type": "array", "items": {"type": "string"},
                    "description": "IDs der Aufnahmen, auf die sich die Antwort stützt (z. B. 2026-10-15_120815_gespraech)."},
    },
    "required": ["antwort", "quellen"],
    "additionalProperties": False,
}

AUFGABE = """# Aufgabe

Beantworte die Frage des Nutzers aus seinen Aufnahmen (Notizen und Texte unten). Heute ist {heute}.
- Stütze dich nur auf die Aufnahmen. Steht die Antwort nicht drin, sag das ehrlich und kurz.
- Nenne Datum und Uhrzeit, wenn es hilft („am Do. 15.10. bei der Kita-Abholung …“).
- Gib in „quellen“ die IDs der Aufnahmen an, aus denen die Antwort stammt.
- Deutsch, knapp, Markdown. Sprich den Nutzer mit „du“ an.

# Frage

{frage}
"""


def _zustand() -> dict:
    try:
        return json.loads(ZUSTAND.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _datum(aufnahme_id: str) -> str:
    try:
        d = datetime.strptime(aufnahme_id[:17], "%Y-%m-%d_%H%M%S")
    except ValueError:
        return aufnahme_id
    return f"{['Mo.', 'Di.', 'Mi.', 'Do.', 'Fr.', 'Sa.', 'So.'][d.weekday()]} {d:%d.%m.%Y %H:%M}"


def _fertige() -> list[tuple[str, dict]]:
    aus = []
    for o in sorted(E.AUFNAHMEN_LOKAL.glob("*"), reverse=True):  # neueste zuerst
        try:
            e = json.loads((o / "ergebnis.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if e.get("status") == "fertig" and e.get("notizen"):
            aus.append((o.name, e))
    return aus


def kontext(frage: str) -> str:
    teile, laenge = ["# Notizen aller Aufnahmen (neueste zuerst)"], 0
    fertige = _fertige()
    for aufnahme_id, e in fertige:
        namen = ", ".join(f"{s['kennung']} = {s['name']}" for s in e.get("sprecher") or [])
        block = [f"## Aufnahme {aufnahme_id} · {_datum(aufnahme_id)} · {aufnahme_id.rsplit('_', 1)[-1]}"
                 + (f" · Sprecher: {namen}" if namen else ""), f"Kurz: {e.get('kurz', '')}"]
        for n in e["notizen"]:
            v = NACH_KENNUNG.get(n.get("vorlage"))
            block.append(f"### {n.get('titel', '')} ({v.name if v else n.get('vorlage')})\n{n.get('inhalt', '').strip()}")
        text = "\n\n".join(block)
        if laenge + len(text) > MAX_NOTIZEN_ZEICHEN:
            teile.append("(Ältere Aufnahmen sind aus Platzgründen weggelassen.)")
            break
        teile.append(text)
        laenge += len(text)

    # ganze Texte der Aufnahmen, in denen Wörter der Frage vorkommen (meiste Treffer zuerst)
    woerter = {w for w in re.findall(r"\w{3,}", frage.casefold()) if w not in FUELLWOERTER}
    if woerter:
        kandidaten = []
        for aufnahme_id, e in fertige:
            try:
                t = (E.AUFNAHMEN_LOKAL / aufnahme_id / "text.txt").read_text(encoding="utf-8")
            except OSError:
                continue
            treffer = sum(t.casefold().count(w) for w in woerter)
            if treffer:
                kandidaten.append((treffer, aufnahme_id, t))
        laenge = 0
        for treffer, aufnahme_id, t in sorted(kandidaten, reverse=True):
            if laenge + len(t) > MAX_TEXT_ZEICHEN:
                break
            if laenge == 0:
                teile.append("# Ganze Texte passender Aufnahmen (Spracherkennung, kann Hörfehler enthalten)")
            teile.append(f"## Text {aufnahme_id} · {_datum(aufnahme_id)}\n{t}")
            laenge += len(t)
    return "\n\n".join(teile)


def _schreiben(daten: dict) -> bool:
    daten["aktualisiert"] = ergebnis.jetzt_iso()
    inhalt = json.dumps(daten, ensure_ascii=False, indent=1)
    try:
        return ergebnis.an_ort_und_stelle_schreiben(ORDNER / "antwort.json", inhalt)
    except OSError:
        return False


def pruefen() -> None:
    try:
        f = json.loads((ORDNER / "frage.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return
    z = _zustand()
    nummer = int(f.get("nummer", 0))
    if nummer <= z.get("erledigt", 0):
        return
    weiter = z.get("weiter_ab")
    if weiter and datetime.now().astimezone() < datetime.fromisoformat(weiter):
        return
    frage = str(f.get("frage", "")).strip()[:2000]
    daten = {"version": 1, "nummer": nummer, "frage": frage, "status": "in_arbeit", "antwort": None,
             "quellen": [], "meldung": None}
    if not (ORDNER / "antwort.json").exists():
        return  # legt die App gleich mit an; nächster Durchgang
    log.info("Frage %d: %s", nummer, frage[:80])
    _schreiben(daten)
    try:
        antwort = claude_aufruf.frage(
            AUFGABE.format(heute=_datum(datetime.now().strftime("%Y-%m-%d_%H%M%S")), frage=frage)
            + "\n\n" + kontext(frage),
            zeitlimit_s=600, system_prompt=_system_prompt(), json_schema=SCHEMA, arbeitsordner=E.CLAUDE_ARBEITSORDNER,
        )
        bekannte = {a for a, _ in _fertige()}
        quellen = []
        for q in antwort.daten.get("quellen", []):
            if q in bekannte:
                e = json.loads((E.AUFNAHMEN_LOKAL / q / "ergebnis.json").read_text(encoding="utf-8"))
                quellen.append({"id": q, "datum": _datum(q), "kurz": e.get("kurz")})
        daten.update(status="fertig", antwort=antwort.daten.get("antwort", ""), quellen=quellen)
        z.pop("weiter_ab", None)
        log.info("Frage %d beantwortet (%d Quellen)", nummer, len(quellen))
    except claude_aufruf.ClaudeLimitErreicht as e:
        w = datetime.now().astimezone() + LIMIT_PAUSE
        z["weiter_ab"] = w.isoformat(timespec="seconds")
        daten.update(status="wartet", meldung=f"Claude-Limit erreicht, Antwort ab {w:%H:%M}.")
        _schreiben(daten)
        ergebnis.lokal_schreiben(ZUSTAND, json.dumps(z))
        log.warning("Frage %d: Claude-Limit (%s)", nummer, e)
        return
    except Exception as e:
        log.exception("Frage %d fehlgeschlagen", nummer)
        daten.update(status="fehler", meldung=f"Das hat nicht geklappt: {e}")
    _schreiben(daten)
    z["erledigt"] = nummer
    ergebnis.lokal_schreiben(ZUSTAND, json.dumps(z))
    ergebnis.lokal_schreiben(VERLAUF / f"{nummer:04d}.json", json.dumps(daten, ensure_ascii=False, indent=1))
