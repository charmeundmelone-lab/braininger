"""Text → Notiz(en): Claude wählt die Vorlage(n) und schreibt die Notizen."""
import json

from . import claude_aufruf
from .einstellungen import CLAUDE_ARBEITSORDNER, PERSOENLICH
from .texterkennung import Text
from .vorlagen import KENNUNGEN, NACH_KENNUNG, VORLAGEN

SYSTEM_PROMPT = """Du bist der Auswerter von „Braininger“, einem persönlichen Sprachaufnahme-Assistenten.
{wer} nimmt mit dem Handy Gedanken und Gespräche auf. Ein Spracherkenner am Laptop
hat daraus einen Text mit Zeitmarken gemacht. Du schreibst daraus Notizen nach festen Vorlagen.

Regeln:
- Wähle für jede Notiz die passende Vorlage. Behandelt die Aufnahme klar getrennte Themen, die zu verschiedenen
  Vorlagen gehören, schreib mehrere Notizen (eine je Thema). Sonst genau eine Notiz.
- Nennt {name} am Anfang selbst eine Vorlage (z. B. „Gig-Nachbereitung:“ oder „Aufgabenliste“), hat das Vorrang.
- Halte dich an den Aufbau der Vorlage. Lass Abschnitte weg, zu denen die Aufnahme nichts sagt. Erfinde nichts.
- Der Spracherkenner macht Hörfehler. Korrigiere offensichtliche Fehler (Namen, Fachwörter, Songtitel) stillschweigend,
  wenn der Zusammenhang eindeutig ist. Unsicheres markierst du mit (?).
- Schreib auf Deutsch, knapp und konkret, in Markdown. Daten, Uhrzeiten, Beträge, Namen und Orte genau übernehmen.
- Gesprächspartner heißen im Text S1, S2 usw. Wenn aus dem Gespräch klar hervorgeht, wer wer ist, nenne die Namen.
  Bei Aufnahmen im Modus „gedanke“ spricht {name} allein.
- Aufgaben schreibst du als Markdown-Checkliste „- [ ] …“ mit Frist, falls genannt.
- Ist die Aufnahme leer, unverständlich oder nur Rauschen, schreib eine einzige kurze Notiz mit Vorlage „tagebuch“
  und dem Titel „Kein verwertbarer Inhalt“.
"""

SCHEMA = {
    "type": "object",
    "properties": {
        "kurz": {"type": "string", "description": "Ein Satz, worum es in der Aufnahme geht (für die Liste in der App)."},
        "notizen": {
            "type": "array",
            "minItems": 1,
            "items": {
                "type": "object",
                "properties": {
                    "vorlage": {"type": "string", "enum": KENNUNGEN},
                    "titel": {"type": "string"},
                    "inhalt": {"type": "string", "description": "Die Notiz in Markdown, ohne Titelzeile."},
                    "von_s": {"type": "number", "description": "Beginn des zugehörigen Teils der Aufnahme in Sekunden."},
                    "bis_s": {"type": "number"},
                    "warum": {"type": "string", "description": "Kurz, warum diese Vorlage."},
                },
                "required": ["vorlage", "titel", "inhalt", "von_s", "bis_s", "warum"],
                "additionalProperties": False,
            },
        },
        "sprecher": {
            "type": "array",
            "description": "Nur Sprecher, deren Name aus dem Gespräch eindeutig hervorgeht.",
            "items": {
                "type": "object",
                "properties": {"kennung": {"type": "string"}, "name": {"type": "string"}},
                "required": ["kennung", "name"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["kurz", "notizen", "sprecher"],
    "additionalProperties": False,
}


def _system_prompt() -> str:
    """Name und Beruf stehen nur lokal in daten/persoenlich.json, nie im öffentlichen Projekt."""
    try:
        p = json.loads(PERSOENLICH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        p = {}
    name = p.get("name") or "Der Nutzer"
    wer = f"{name}, {p['beschreibung']}," if p.get("beschreibung") else name
    return SYSTEM_PROMPT.format(name=name, wer=wer)


def _vorlagen_text() -> str:
    teile = []
    for v in VORLAGEN:
        teile.append(f"### {v.kennung} – {v.name}\nWann: {v.wann}\nAufbau:\n{v.aufbau}")
    return "\n\n".join(teile)


def prompt_bauen(text: Text, modus: str, start: str, dauer_s: float, hinweise: list[str]) -> str:
    kopf = [
        f"Aufnahme vom {start}, Dauer {dauer_s / 60:.1f} min, Modus „{modus}“, erkannte Sprache „{text.sprache}“.",
    ]
    if text.sprecher_anzahl is not None:
        kopf.append(f"Erkannte Sprecher: {text.sprecher_anzahl} (S1 spricht zuerst).")
    if hinweise:
        kopf.append("Hinweise: " + " ".join(hinweise))
    return (
        "# Vorlagen\n\n" + _vorlagen_text()
        + "\n\n# Aufnahme\n\n" + "\n".join(kopf)
        + "\n\n# Text\n\n" + (text.als_zeilen() or "(kein Text erkannt)")
        + "\n\nSchreib jetzt die Notiz(en)."
    )


def auswerten(text: Text, modus: str, start: str, dauer_s: float, hinweise: list[str]) -> dict:
    """Wirft claude_aufruf.ClaudeLimitErreicht, wenn das Abo-Limit erreicht ist."""
    antwort = claude_aufruf.frage(
        prompt_bauen(text, modus, start, dauer_s, hinweise),
        zeitlimit_s=900,
        system_prompt=_system_prompt(),
        json_schema=SCHEMA,
        arbeitsordner=CLAUDE_ARBEITSORDNER,
    )
    daten = antwort.daten
    # Zur Sicherheit selbst prüfen, falls Claude doch vom Schema abweicht
    notizen = [n for n in daten.get("notizen", []) if n.get("vorlage") in KENNUNGEN and n.get("inhalt")]
    if not notizen:
        raise claude_aufruf.ClaudeFehler("Claude hat keine gültige Notiz geliefert.")
    daten["notizen"] = notizen
    daten["dauer_ms"] = antwort.dauer_ms
    return daten


def vorlage_aendern(text: Text, modus: str, start: str, notiz: dict, vorlage: str) -> dict:
    """Schreibt eine einzelne Notiz mit einer vom Nutzer gewählten Vorlage neu (Tipp in der App)."""
    von, bis = float(notiz.get("von_s", 0)), float(notiz.get("bis_s", 0))
    teil = [a for a in text.abschnitte if bis <= von or (a.ende > von - 1 and a.start < bis + 1)]
    auszug = Text(teil or text.abschnitte, text.sprache, text.sprache_sicherheit, text.sprecher_anzahl)
    v = NACH_KENNUNG[vorlage]
    schema = {
        "type": "object",
        "properties": SCHEMA["properties"]["notizen"]["items"]["properties"],
        "required": ["titel", "inhalt", "von_s", "bis_s", "vorlage", "warum"],
        "additionalProperties": False,
    }
    prompt = (
        "# Vorlagen\n\n" + _vorlagen_text()
        + f"\n\n# Aufgabe\n\nDer Nutzer hat in der App festgelegt: Diese Notiz soll nach der Vorlage „{v.kennung}“ "
        f"({v.name}) geschrieben werden. Schreib sie nach dieser Vorlage neu. Setz „vorlage“ auf „{v.kennung}“.\n\n"
        f"Bisherige Notiz („{notiz.get('vorlage')}“): {notiz.get('titel')}\n\n"
        f"# Aufnahme\n\nAufnahme vom {start}, Modus „{modus}“.\n\n# Text\n\n" + auszug.als_zeilen()
    )
    antwort = claude_aufruf.frage(prompt, zeitlimit_s=600, system_prompt=_system_prompt(), json_schema=schema,
                                  arbeitsordner=CLAUDE_ARBEITSORDNER)
    neu = dict(antwort.daten)
    neu["vorlage"] = vorlage  # die Wahl des Nutzers gilt in jedem Fall
    if not neu.get("inhalt"):
        raise claude_aufruf.ClaudeFehler("Claude hat keine Notiz geliefert.")
    return neu
