"""Fragen an alle Aufnahmen: die App stellt eine Frage, Claude antwortet aus allen Notizen.

Ablage im Drive: Braininger/Fragen/frage.json (App schreibt: nummer, frage) und antwort.json. Beide legt die App
an (drive.file, siehe Frühtest a), der Laptop überschreibt antwort.json nur an Ort und Stelle.
antwort.json: version, nummer, frage, status (in_arbeit | wartet | fertig | fehler), antwort (Markdown),
quellen [{id, datum, kurz}], meldung, aktualisiert.

Claude bekommt alle Notizen und dazu den ganzen Text der Aufnahmen, in denen Wörter der Frage vorkommen
(für Details, die nicht in den Notizen stehen). Werden die Notizen zu lang (ab ca. 140 Aufnahmen), kommen die
neuesten und die zur Frage passenden älteren mit, der Rest fällt weg (siehe _notizen_auswaehlen).
"""
import json
import logging
import re
from datetime import datetime, timedelta

from . import claude_aufruf, ergebnis, ordnung
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
    geloescht = ordnung.papierkorb()
    for o in sorted(E.AUFNAHMEN_LOKAL.glob("*"), reverse=True):  # neueste zuerst
        if o.name in geloescht:
            continue
        try:
            e = json.loads((o / "ergebnis.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if e.get("status") == "fertig" and e.get("notizen"):
            aus.append((o.name, e))
    return aus


def _suchwoerter(frage: str) -> set[str]:
    """Wörter der Frage ohne Füllwörter, lange auf 6 Buchstaben gekürzt (findet so auch „Schuh“ zu „Schuhe“)."""
    return {w[:6] for w in re.findall(r"\w{3,}", frage.casefold()) if w not in FUELLWOERTER}


def _notizblock(aufnahme_id: str, e: dict) -> str:
    namen = ", ".join(f"{s['kennung']} = {s['name']}" for s in e.get("sprecher") or [])
    block = [f"## Aufnahme {aufnahme_id} · {_datum(aufnahme_id)} · {aufnahme_id.rsplit('_', 1)[-1]}"
             + (f" · Sprecher: {namen}" if namen else ""), f"Kurz: {e.get('kurz', '')}"]
    for n in e["notizen"]:
        v = NACH_KENNUNG.get(n.get("vorlage"))
        block.append(f"### {n.get('titel', '')} ({v.name if v else n.get('vorlage')})\n{n.get('inhalt', '').strip()}")
    return "\n\n".join(block)


def _text(aufnahme_id: str) -> str:
    try:
        return (E.AUFNAHMEN_LOKAL / aufnahme_id / "text.txt").read_text(encoding="utf-8")
    except OSError:
        return ""


def _notizen_auswaehlen(bloecke: list[tuple[str, str]], treffer: dict[str, int], grenze: int) -> set[str]:
    """Welche Notizblöcke Claude bekommt. Passt alles, dann alle. Sonst: die neuesten bis zur halben Grenze,
    dann ältere mit Wörtern der Frage (meiste Treffer zuerst), mit dem Rest wieder die neuesten."""
    if sum(len(b) for _, b in bloecke) <= grenze:
        return {i for i, _ in bloecke}
    laenge, gewaehlt = 0, set()

    def nehmen(aufnahme_id: str, block: str, bis: int) -> None:
        nonlocal laenge
        if aufnahme_id not in gewaehlt and laenge + len(block) <= bis:
            gewaehlt.add(aufnahme_id)
            laenge += len(block)

    for i, b in bloecke:  # neueste zuerst
        if laenge + len(b) > grenze // 2:
            break
        nehmen(i, b, grenze // 2)
    passende = sorted((-treffer.get(i, 0), n, i, b) for n, (i, b) in enumerate(bloecke) if treffer.get(i))
    for _, _, i, b in passende:
        nehmen(i, b, grenze)
    for i, b in bloecke:
        nehmen(i, b, grenze)
    return gewaehlt


def kontext(frage: str, grenze: int = MAX_NOTIZEN_ZEICHEN) -> str:
    fertige = _fertige()
    woerter = _suchwoerter(frage)
    bloecke = [(i, _notizblock(i, e)) for i, e in fertige]
    texte = {i: _text(i) for i, _ in fertige} if woerter else {}
    treffer = {}
    for i, b in bloecke:
        if woerter:
            inhalt = (b + "\n" + texte.get(i, "")).casefold()
            treffer[i] = sum(inhalt.count(w) for w in woerter)
    gewaehlt = _notizen_auswaehlen(bloecke, treffer, grenze)

    teile = ["# Notizen aller Aufnahmen (neueste zuerst)"]
    teile += [b for i, b in bloecke if i in gewaehlt]
    if len(gewaehlt) < len(bloecke):
        teile.append(f"({len(bloecke) - len(gewaehlt)} weitere Aufnahmen ohne Bezug zur Frage sind aus Platzgründen "
                     "weggelassen.)")
        log.info("Fragen: %d von %d Aufnahmen im Kontext", len(gewaehlt), len(bloecke))

    # ganze Texte der Aufnahmen, in denen Wörter der Frage vorkommen (meiste Treffer zuerst)
    if woerter:
        kandidaten = []
        for aufnahme_id, _ in fertige:
            t = texte.get(aufnahme_id, "")
            n = sum(t.casefold().count(w) for w in woerter)
            if n:
                kandidaten.append((n, aufnahme_id, t))
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
