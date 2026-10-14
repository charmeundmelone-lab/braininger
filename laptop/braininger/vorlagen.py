"""Die 8 Vorlagen aus dem Grill-Ergebnis. Kennung = feste Schreibweise für ergebnis.json und die App."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Vorlage:
    kennung: str
    name: str
    wann: str  # woran Claude erkennt, dass die Vorlage passt
    aufbau: str  # Gliederung der Notiz in Markdown


VORLAGEN = [
    Vorlage(
        "tagebuch", "Tagebuch",
        "Persönliche Gedanken, Erlebnisse, Stimmung, Rückblick auf den Tag. Auch die Vorlage für die Tagesübersicht.",
        "## Was war\n## Gedanken und Gefühle\n## Was ich mir merken will",
    ),
    Vorlage(
        "kundengespraech", "Kundengespräch / Gig-Anfrage",
        "Gespräch oder Notiz zu einer Anfrage für einen Auftritt oder Auftrag: Anlass, Datum, Ort, Wünsche, Preis.",
        "## Kunde und Anlass\n## Eckdaten (Datum, Uhrzeit, Ort, Dauer, Besetzung)\n## Wünsche (Musik, Ablauf, Technik)\n"
        "## Preis und Konditionen\n## Offene Fragen\n## Nächste Schritte",
    ),
    Vorlage(
        "besprechung", "Besprechungsprotokoll",
        "Besprechung mit mehreren Personen (Band, Team, Verein), bei der Themen beraten und Dinge beschlossen werden.",
        "## Teilnehmer\n## Themen\n## Beschlüsse\n## Aufgaben (wer, was, bis wann)\n## Offene Punkte",
    ),
    Vorlage(
        "songidee", "Song- / Ideen-Notiz",
        "Musikalische oder kreative Idee: Textzeilen, Melodie, Akkorde, Arrangement, Titelidee, Projektidee.",
        "## Idee in einem Satz\n## Text / Zeilen (wörtlich)\n## Musik (Tonart, Akkorde, Tempo, Stil)\n## Weiterentwickeln",
    ),
    Vorlage(
        "aufgaben", "Aufgabenliste",
        "Hauptsächlich Dinge, die zu erledigen sind, ohne größeren Zusammenhang.",
        "## Aufgaben\n- [ ] Aufgabe (Frist, falls genannt)\n## Notizen",
    ),
    Vorlage(
        "gig_nachbereitung", "Gig-Nachbereitung",
        "Rückblick nach einem Auftritt: wie es lief, Publikum, Technik, Setlist, Abrechnung, was nächstes Mal anders.",
        "## Auftritt (wann, wo, für wen)\n## Wie es lief\n## Technik und Ablauf\n## Abrechnung / Rechnung\n"
        "## Fürs nächste Mal\n## Aufgaben",
    ),
    Vorlage(
        "telefonat", "Aktennotiz Telefonat",
        "Kurze Notiz über ein Telefonat oder kurzes Gespräch mit einer Person (Behörde, Firma, Dienstleister).",
        "## Mit wem, wann\n## Worum es ging\n## Ergebnis / Vereinbarung\n## Nächste Schritte",
    ),
    Vorlage(
        "haushalt", "Haushalt & Familie",
        "Familie, Kinder, Haushalt, Einkauf, Termine im Privatleben, Organisation zu Hause.",
        "## Worum es geht\n## Termine\n## Einkauf / Besorgungen\n## Aufgaben",
    ),
]

NACH_KENNUNG = {v.kennung: v for v in VORLAGEN}
KENNUNGEN = [v.kennung for v in VORLAGEN]
