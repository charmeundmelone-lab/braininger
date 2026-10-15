"""Die Vorlagen. Kennung = feste Schreibweise für ergebnis.json und die App.

Seit dem 15.10.2026 (Grill „Vorlage, Aufgaben, Werkzeuge“) ist jede Vorlage ein Arbeitsauftrag an Claude,
nicht nur eine Liste von Überschriften: Plaud-Vorlagen wirken über ihre Anweisungen, nicht über die Gliederung.
„gedanken“ ist die feste Standard-Vorlage für alle Gedanke-Aufnahmen.
"""
from dataclasses import dataclass

STANDARD_GEDANKE = "gedanken"


@dataclass(frozen=True)
class Vorlage:
    kennung: str
    name: str
    wann: str  # woran Claude erkennt, dass die Vorlage passt
    anweisung: str  # wie Claude die Notiz schreibt (Aufbau und was hineingehört)


VORLAGEN = [
    Vorlage(
        STANDARD_GEDANKE, "Gedanken",
        "Standard für jede Aufnahme im Modus „gedanke“: gemischte Gedanken unterwegs (Gassi, Auto), "
        "Tagebuch, Familie, Musik, Ideen, Geld durcheinander.",
        "Ordne alles nach Lebensbereichen, immer in dieser Reihenfolge, jeder als eigene Überschrift:\n"
        "## Familie & Haushalt\n## Musik & Gigs\n## Ich selbst\n## Projekte & Ideen\n## Finanzen Geschäft\n"
        "Bereiche ohne Inhalt lässt du ganz weg. Was zu keinem Bereich passt, kommt zum nächstliegenden.\n"
        "„Ich selbst“ ist für Gefühle, Gesundheit, Stimmung und Gedanken über mein Leben; dort darf es ein kurzer "
        "Absatz wie im Tagebuch sein. Sonst Stichpunkte.\n"
        "So ausführlich wie nötig: Füllwörter, Wiederholungen, Selbstkorrekturen und Nebensächliches ohne Folgen "
        "fallen weg. Jede Information, jeder Name, jede Zahl, jedes Datum und jedes Gefühl bleibt. Typische "
        "Formulierungen und treffende Sätze von mir übernimmst du wörtlich in Anführungszeichen.\n"
        "Textzeilen, Melodien oder Akkorde einer Song-Idee stehen wörtlich unter „Projekte & Ideen“.",
    ),
    Vorlage(
        "tagebuch", "Tagebuch",
        "Rückblick auf den Tag oder ein Erlebnis, Stimmung. Auch die Vorlage für die Tagesübersicht.",
        "## Was war\n## Gedanken und Gefühle\n## Was ich mir merken will\n"
        "Erzählend, in ganzen Sätzen. Gefühle und Stimmung genau so stark wiedergeben, wie sie gesagt wurden.",
    ),
    Vorlage(
        "kundengespraech", "Kundengespräch / Gig-Anfrage",
        "Gespräch oder Notiz zu einer Anfrage für einen Auftritt oder Auftrag: Anlass, Datum, Ort, Wünsche, Preis.",
        "## Kunde und Anlass\n## Eckdaten (Datum, Uhrzeit, Ort, Dauer, Besetzung)\n## Wünsche (Musik, Ablauf, Technik)\n"
        "## Preis und Konditionen\n## Offene Fragen\n## Nächste Schritte\n"
        "Fehlt eine Angabe, die man für ein Angebot braucht (Datum, Ort, Dauer, Besetzung, Gage, Anfahrt), "
        "schreib sie unter „Offene Fragen“. Beträge, Daten und Kontaktdaten exakt übernehmen.",
    ),
    Vorlage(
        "besprechung", "Besprechungsprotokoll",
        "Besprechung mit mehreren Personen (Band, Team, Verein), bei der Themen beraten und Dinge beschlossen werden.",
        "## Teilnehmer\n## Themen\n## Beschlüsse\n## Wer macht was bis wann\n## Offene Punkte\n"
        "Beschlüsse klar von Meinungen trennen. Bei „Wer macht was“ immer den Namen nennen.",
    ),
    Vorlage(
        "songidee", "Song- / Ideen-Notiz",
        "Musikalische oder kreative Idee: Textzeilen, Melodie, Akkorde, Arrangement, Titelidee, Projektidee.",
        "## Idee in einem Satz\n## Text / Zeilen (wörtlich)\n## Musik (Tonart, Akkorde, Tempo, Stil)\n## Weiterentwickeln\n"
        "Gesungene oder gesprochene Zeilen nie glätten, sondern wörtlich übernehmen.",
    ),
    Vorlage(
        "aufgaben", "Aufgabenliste",
        "Nur auf ausdrückliche Ansage („Aufgabenliste: …“): eine Sammlung von Dingen, die zu erledigen sind.",
        "## Aufgaben\nJede Aufgabe als Stichpunkt mit Frist, falls genannt, und kurzem Zusammenhang.\n## Notizen",
    ),
    Vorlage(
        "gig_nachbereitung", "Gig-Nachbereitung",
        "Rückblick nach einem Auftritt: wie es lief, Publikum, Technik, Setlist, Abrechnung, was nächstes Mal anders.",
        "## Auftritt (wann, wo, für wen)\n## Wie es lief\n## Technik und Ablauf\n## Abrechnung / Rechnung\n"
        "## Fürs nächste Mal\nDie Setlist, falls genannt, als nummerierte Liste.",
    ),
    Vorlage(
        "telefonat", "Aktennotiz Telefonat",
        "Kurze Notiz über ein Telefonat oder kurzes Gespräch mit einer Person (Behörde, Firma, Dienstleister).",
        "## Mit wem, wann\n## Worum es ging\n## Ergebnis / Vereinbarung\n## Nächste Schritte\n"
        "Aktenzeichen, Nummern und Namen von Ansprechpartnern exakt übernehmen.",
    ),
    Vorlage(
        "haushalt", "Haushalt & Familie",
        "Gespräch über Familie, Kinder, Haushalt, Einkauf, Termine im Privatleben, Organisation zu Hause.",
        "## Worum es geht\n## Abmachungen (wer, was, wann)\n## Einkauf / Besorgungen",
    ),
]

NACH_KENNUNG = {v.kennung: v for v in VORLAGEN}
KENNUNGEN = [v.kennung for v in VORLAGEN]
