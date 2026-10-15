"""Text → Notiz(en): Claude wählt die Vorlage(n) und schreibt die Notizen."""
import json
from pathlib import Path

from . import claude_aufruf, woerterbuch
from .einstellungen import CLAUDE_ARBEITSORDNER, PERSOENLICH
from .texterkennung import Text
from datetime import datetime

from .vorlagen import KENNUNGEN, NACH_KENNUNG, STANDARD_GEDANKE, VORLAGEN

SYSTEM_PROMPT = """Du bist der Auswerter von „Braininger“, einem persönlichen Sprachaufnahme-Assistenten.
{wer} nimmt mit dem Handy Gedanken und Gespräche auf. Ein Spracherkenner am Laptop
hat daraus einen Text mit Zeitmarken gemacht. Du schreibst daraus Notizen nach festen Vorlagen.

Regeln:
- Modus „gedanke“: Nimm immer die Vorlage „gedanken“ und schreib genau eine Notiz. Nennt {name} am Anfang selbst
  eine Vorlage (z. B. „Gig-Anfrage:“ oder „Song-Idee:“), schreibst du die Notiz stattdessen nach dieser.
  Passt eine andere Vorlage eindeutig besser, weil die ganze Aufnahme z. B. eine Gig-Anfrage oder eine Song-Idee ist,
  bleibst du trotzdem bei „gedanken“ und nennst die bessere in „vorlage_hinweis“ (mit kurzem Grund). Sonst bleibt
  „vorlage_hinweis.vorlage“ leer.
- Modus „gespraech“: Wähle selbst die passende Vorlage (nicht „gedanken“). Behandelt das Gespräch klar getrennte
  Themen, die zu verschiedenen Vorlagen gehören, schreib mehrere Notizen. „vorlage_hinweis.vorlage“ bleibt leer.
- Besteht eine Aufnahme nur aus einer kurzen Erinnerung oder Aufgabe (ein, zwei Sätze wie „Steuernummer
  raussuchen“), schreib keine Notiz („notizen“ leer). Die Aufgabe steht dann nur in „aufgaben“.
- Halte dich an die Anweisung der Vorlage. Lass Abschnitte weg, zu denen die Aufnahme nichts sagt. Erfinde nichts.
- Der Spracherkenner macht Hörfehler. Korrigiere offensichtliche Fehler (Namen, Fachwörter, Songtitel) stillschweigend,
  wenn der Zusammenhang eindeutig ist. Unsicheres markierst du mit (?).
- Schlüsse, die über das Gesagte hinausgehen (z. B. wer oder was mit einem Wort gemeint ist), darfst du ziehen,
  aber immer erkennbar als Vermutung („vermutlich …“) und getrennt vom Gesagten. Das Gesagte selbst gibst du
  unverändert wieder.
- Schreib auf Deutsch, knapp und konkret, in Markdown. Daten, Uhrzeiten, Beträge, Namen und Orte genau übernehmen.
- Gesprächspartner heißen im Text S1, S2 usw. Wenn aus dem Gespräch klar hervorgeht, wer wer ist, nenne die Namen.
  Bei Aufnahmen im Modus „gedanke“ spricht {name} allein.
- Perspektive: Im Modus „gedanke“ schreibst du in der Ich-Form, als hätte {name} die Notiz selbst geschrieben
  („Ich bin müde …“, „Ich will morgen …“). Im Modus „gespraech“ schreibst du in der dritten Person und nennst
  {name} beim Namen, damit klar ist, wer was gesagt hat („{name} bietet an …, S2 fragt …“).
- „[Pause …]“ im Text steht für eine lange Stille. Danach beginnt oft ein neues Thema.
- Sprachmarke „Wichtig“: Sagt {name} „wichtig“ als Ansage, um etwas hervorzuheben („Wichtig: …“, „Das ist wichtig: …“,
  „Ganz wichtig …“), ist der Gedanke direkt danach markiert. Nicht gemeint ist „wichtig“ als gewöhnliches Wort im Satz
  („das war mir wichtig“). Jede markierte Stelle kommt in der passenden Notiz ganz nach oben unter „## Wichtig“
  und zusätzlich ins Feld „wichtig“ (mit Zeit in Sekunden). Ohne Sprachmarke bleibt „wichtig“ leer.
- Markier-Knopf: Stehen unter „Markierungen“ Zeitpunkte, hat {name} während der Aufnahme auf „Markieren“ getippt.
  Jede Markierung zählt wie die Sprachmarke „Wichtig“. Welcher Gedanke gemeint ist, entscheidest du aus dem Text:
  meist der gerade davor gesagte (bis etwa 30 Sekunden), manchmal der direkt danach angekündigte. Liegen mehrere
  Markierungen beim selben Gedanken, nimm ihn nur einmal. Im Notiztext erwähnst du Markierungen, Zeiten und deine
  Abwägung nicht; die Zeit steht nur im Feld „wichtig“.
- Fotos: Stehen unter „Fotos“ Dateien, hat {name} während der Aufnahme fotografiert; die Bilder liegen dieser
  Nachricht in derselben Reihenfolge bei. Lies, was darauf zu sehen ist (z. B. Setlist, Zettel, Rechnung, Ort),
  und nimm das Wichtige in die passende Notiz auf. Setz das Foto dort als eigene Zeile „![Foto](<Dateiname>)“ ein,
  wo es inhaltlich hingehört. Ist auf einem Foto nichts erkennbar, setz nur die Zeile.
- Aufgaben: In die Notizen schreibst du keine Checklisten. Aufgaben schlägst du im Feld „aufgaben“ vor; {name}
  bestätigt sie in der App, danach landen sie in seiner Aufgabenliste mit Erinnerung. Deshalb streng:
  - Nur Aufgaben, die {name} selbst erledigen muss. Was andere tun sollen oder zugesagt haben, schlägst du nicht vor.
  - Bei „wir“, „man“, „jemand müsste“ ohne klaren Namen: vorschlagen, aber mit „unklar_wer“ = true.
  - Nicht vorschlagen: vage Wünsche („ich sollte mal mehr Sport machen“), was gleich erledigt wird oder schon
    erledigt ist („mach ich jetzt gleich“), alltägliche Routinen ohne Besonderheit (Gassi gehen, Kinder abholen,
    normaler Einkauf) und Überlegungen ohne Entscheidung („vielleicht sollte ich …“). Lieber eine zu wenig als Müll.
  - „text“: kurz und ohne die Notiz verständlich, mit Namen statt Pronomen („Annas neue Steuernummer raussuchen“).
  - Unten stehen {name}s offene Aufgaben mit Kennung. Ist eine davon gemeint, schlag sie nicht neu vor. Bringt die
    Aufnahme eine neue Frist oder Information dazu: „art“ = „ergaenzen“, „bezug“ = Kennung, vollständiger neuer Text
    und Frist. Sagt {name}, dass sie erledigt ist: „art“ = „erledigt“, „bezug“ = Kennung. Neue Aufgaben: „art“ =
    „neu“, „bezug“ leer.
  - „frist“ als „JJJJ-MM-TTTHH:MM“ (Ortszeit), gerechnet ab dem Aufnahmezeitpunkt. Genannte Uhrzeit genau
    übernehmen. Sonst: früh/morgens 08:00, vormittags 10:00, mittags 12:00, nachmittags 15:00, abends 19:00,
    spät/nachts 21:00, nur ein Tag ohne Tageszeit 08:00, „diese Woche“ Freitag 10:00, „nächste Woche“ Montag
    10:00, „am Wochenende“ Samstag 10:00. Ohne jede Zeitangabe bleibt „frist“ leer.
- Ist die Aufnahme leer, unverständlich oder nur Rauschen, schreib eine einzige kurze Notiz mit Vorlage „tagebuch“
  und dem Titel „Kein verwertbarer Inhalt“.
"""

SCHEMA = {
    "type": "object",
    "properties": {
        "kurz": {"type": "string", "description": "Ein Satz, worum es in der Aufnahme geht (für die Liste in der App)."},
        "notizen": {
            "type": "array",
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
        "wichtig": {
            "type": "array",
            "description": "Stellen, die der Nutzer mit der Sprachmarke „wichtig“ oder dem Markier-Knopf hervorgehoben hat.",
            "items": {
                "type": "object",
                "properties": {"zeit_s": {"type": "number"}, "text": {"type": "string", "description": "kurz, ein Satz"}},
                "required": ["zeit_s", "text"],
                "additionalProperties": False,
            },
        },
        "aufgaben": {
            "type": "array",
            "description": "Aufgaben-Vorschläge für den Nutzer (siehe Regeln).",
            "items": {
                "type": "object",
                "properties": {
                    "art": {"type": "string", "enum": ["neu", "ergaenzen", "erledigt"]},
                    "text": {"type": "string"},
                    "frist": {"type": "string", "description": "JJJJ-MM-TTTHH:MM oder leer"},
                    "unklar_wer": {"type": "boolean"},
                    "bezug": {"type": "string", "description": "Kennung einer offenen Aufgabe oder leer"},
                },
                "required": ["art", "text", "frist", "unklar_wer", "bezug"],
                "additionalProperties": False,
            },
        },
        "vorlage_hinweis": {
            "type": "object",
            "properties": {
                "vorlage": {"type": "string", "enum": [""] + KENNUNGEN},
                "grund": {"type": "string"},
            },
            "required": ["vorlage", "grund"],
            "additionalProperties": False,
        },
    },
    "required": ["kurz", "notizen", "sprecher", "wichtig", "aufgaben", "vorlage_hinweis"],
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
        teile.append(f"### {v.kennung} – {v.name}\nWann: {v.wann}\nAnweisung:\n{v.anweisung}")
    return "\n\n".join(teile)


def eigener_name() -> str:
    try:
        return json.loads(PERSOENLICH.read_text(encoding="utf-8")).get("name") or "ich"
    except (OSError, ValueError):
        return "ich"


def _woerterbuch_text() -> str:
    b = woerterbuch.begriffe()
    if not b:
        return ""
    return ("\n\n# Wörterbuch\n\nSo werden diese Namen und Begriffe geschrieben. Klingt etwas im Text ähnlich "
            "und passt der Zusammenhang, ist dieser Begriff gemeint:\n" + ", ".join(b))


def _wochentag(start: str) -> str:
    try:
        return " (" + ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"][
            datetime.fromisoformat(start).weekday()] + ")"
    except ValueError:
        return ""


def prompt_bauen(text: Text, modus: str, start: str, dauer_s: float, hinweise: list[str],
                 namen: dict[str, str] | None = None, markierungen: list[float] | None = None,
                 fotos: list[tuple[str, float]] | None = None, offene_aufgaben: list[dict] | None = None) -> str:
    """namen: {"S1": "Jan"} – im Text werden die Kennungen durch die Namen ersetzt."""
    kopf = [
        f"Aufnahme vom {start}{_wochentag(start)}, Dauer {dauer_s / 60:.1f} min, Modus „{modus}“, "
        f"erkannte Sprache „{text.sprache}“.",
    ]
    if text.sprecher_anzahl is not None:
        kopf.append(f"Erkannte Sprecher: {text.sprecher_anzahl} (S1 spricht zuerst).")
    if namen:
        kopf.append("Namen der Sprecher (an der Stimme erkannt oder vom Nutzer festgelegt), im Text schon eingesetzt: "
                    + ", ".join(f"{k} = {v}" for k, v in sorted(namen.items())) + ".")
    if hinweise:
        kopf.append("Hinweise: " + " ".join(hinweise))
    if markierungen:
        kopf.append("Markierungen (Markier-Knopf) bei: "
                    + ", ".join(f"{int(m) // 60}:{int(m) % 60:02d} ({m:.0f} s)" for m in sorted(markierungen)) + ".")
    if fotos:
        kopf.append("Fotos (in dieser Reihenfolge beigefügt): "
                    + ", ".join(f"{d} bei {int(s) // 60}:{int(s) % 60:02d}" for d, s in fotos) + ".")
    offen = "\n".join(
        f"- {a['id']}: {a['text']}" + (f" (Frist {a['frist']})" if a.get("frist") else "") for a in offene_aufgaben or []
    ) or "(keine)"
    return (
        "# Vorlagen\n\n" + _vorlagen_text() + _woerterbuch_text()
        + "\n\n# Offene Aufgaben\n\n" + offen
        + "\n\n# Aufnahme\n\n" + "\n".join(kopf)
        + "\n\n# Text\n\n" + (text.als_zeilen(namen) or "(kein Text erkannt)")
        + "\n\nSchreib jetzt die Notiz(en)."
    )


def auswerten(text: Text, modus: str, start: str, dauer_s: float, hinweise: list[str],
              namen: dict[str, str] | None = None, markierungen: list[float] | None = None,
              fotos: list[Path] | None = None, foto_zeiten: list[float] | None = None,
              offene_aufgaben: list[dict] | None = None) -> dict:
    """Wirft claude_aufruf.ClaudeLimitErreicht, wenn das Abo-Limit erreicht ist."""
    antwort = claude_aufruf.frage(
        prompt_bauen(text, modus, start, dauer_s, hinweise, namen, markierungen,
                     [(f.name, z) for f, z in zip(fotos or [], foto_zeiten or [])], offene_aufgaben),
        zeitlimit_s=900,
        system_prompt=_system_prompt(),
        json_schema=SCHEMA,
        arbeitsordner=CLAUDE_ARBEITSORDNER,
        bilder=fotos or None,
    )
    daten = antwort.daten
    # Zur Sicherheit selbst prüfen, falls Claude doch vom Schema abweicht
    notizen = [n for n in daten.get("notizen", []) if n.get("vorlage") in KENNUNGEN and n.get("inhalt")]
    if modus == "gedanke" and notizen and notizen[0]["vorlage"] == STANDARD_GEDANKE:
        notizen = notizen[:1]  # Standard-Vorlage: eine Notiz je Aufnahme
    offen = {a["id"] for a in offene_aufgaben or []}
    aufgaben = []
    for a in daten.get("aufgaben", []):
        text = str(a.get("text", "")).strip()
        if not text or a.get("art") not in ("neu", "ergaenzen", "erledigt"):
            continue
        if a["art"] != "neu" and a.get("bezug") not in offen:
            if a["art"] == "erledigt":
                continue  # unbekannte Aufgabe kann nicht abgehakt werden
            a = dict(a, art="neu", bezug="")  # Bezug unbekannt: lieber als neue Aufgabe vorschlagen
        try:
            frist = datetime.fromisoformat(a["frist"]).strftime("%Y-%m-%dT%H:%M") if a.get("frist") else ""
        except ValueError:
            frist = ""
        aufgaben.append({"art": a["art"], "text": text, "frist": frist, "unklar_wer": bool(a.get("unklar_wer")),
                         "bezug": a.get("bezug", "") if a["art"] != "neu" else ""})
    daten["notizen"] = notizen
    daten["aufgaben"] = aufgaben
    hinweis = daten.get("vorlage_hinweis") or {}
    daten["vorlage_hinweis"] = hinweis if hinweis.get("vorlage") in KENNUNGEN and notizen \
        and hinweis["vorlage"] != notizen[0]["vorlage"] else None
    daten["dauer_ms"] = antwort.dauer_ms
    return daten


def vorlage_aendern(text: Text, modus: str, start: str, notiz: dict, vorlage: str,
                   namen: dict[str, str] | None = None) -> dict:
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
        "# Vorlagen\n\n" + _vorlagen_text() + _woerterbuch_text()
        + f"\n\n# Aufgabe\n\nDer Nutzer hat in der App festgelegt: Diese Notiz soll nach der Vorlage „{v.kennung}“ "
        f"({v.name}) geschrieben werden. Schreib sie nach dieser Vorlage neu. Setz „vorlage“ auf „{v.kennung}“.\n\n"
        f"Bisherige Notiz („{notiz.get('vorlage')}“): {notiz.get('titel')}\n\n"
        f"# Aufnahme\n\nAufnahme vom {start}, Modus „{modus}“.\n\n# Text\n\n" + auszug.als_zeilen(namen)
    )
    antwort = claude_aufruf.frage(prompt, zeitlimit_s=600, system_prompt=_system_prompt(), json_schema=schema,
                                  arbeitsordner=CLAUDE_ARBEITSORDNER)
    neu = dict(antwort.daten)
    neu["vorlage"] = vorlage  # die Wahl des Nutzers gilt in jedem Fall
    if not neu.get("inhalt"):
        raise claude_aufruf.ClaudeFehler("Claude hat keine Notiz geliefert.")
    return neu
