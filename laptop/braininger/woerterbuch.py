"""Wörterbuch: Namen, Songtitel, Orte und Fachwörter, die richtig geschrieben werden sollen.

Datei „Braininger\\Wörterbuch.txt“ (privat, nicht im GitHub-Projekt), von Hand im Editor pflegbar:
ein Begriff je Zeile, Zeilen mit # sind Überschriften oder Kommentare.
Wirkt an zwei Stellen: Whisper bekommt die Begriffe als „hotwords“ (bevorzugt sie beim Hören),
Claude bekommt die Liste und korrigiert Hörfehler zu diesen Schreibweisen.
Namen, die der Nutzer in der App einem Sprecher gibt, kommen automatisch unter „# Personen (gelernt)“ dazu.
"""
import logging

from . import einstellungen as E

log = logging.getLogger("braininger")

DATEI = E.PROJEKT / "Wörterbuch.txt"
GELERNT = "# Personen (gelernt)"
HOTWORDS_MAX_ZEICHEN = 400  # Whisper hat nur Platz für ein kurzes Vorwissen (ca. 220 Token)

VORLAGE = """# Braininger-Wörterbuch
# Ein Begriff je Zeile, so geschrieben, wie er in den Notizen stehen soll.
# Zeilen mit # am Anfang sind Überschriften und werden ignoriert. Leerzeilen auch.
# Wichtigste Begriffe nach oben: Whisper bekommt nur die ersten paar Dutzend.

# Familie und Freunde

# Kunden und Veranstalter

# Songs

# Orte

# Sonstiges

""" + GELERNT + "\n"


def anlegen() -> None:
    if not DATEI.exists():
        DATEI.write_text(VORLAGE, encoding="utf-8")


def begriffe() -> list[str]:
    """Erst die Datei am Laptop, dann das Wörterbuch aus der App (Drive: Braininger/woerterbuch.txt, ab 22.10.2026)."""
    zeilen = []
    for datei in (DATEI, E.EINGANG.parent / "woerterbuch.txt"):
        try:
            zeilen += datei.read_text(encoding="utf-8-sig").splitlines()
        except OSError:
            pass
    gesehen: set[str] = set()
    aus = []
    for z in zeilen:
        b = z.strip()
        if b and not b.startswith("#") and b.casefold() not in gesehen:
            gesehen.add(b.casefold())
            aus.append(b)
    return aus


def hotwords() -> str | None:
    """Die ersten Begriffe, so viele in HOTWORDS_MAX_ZEICHEN passen."""
    teile, laenge = [], 0
    for b in begriffe():
        if laenge + len(b) + 2 > HOTWORDS_MAX_ZEICHEN:
            break
        teile.append(b)
        laenge += len(b) + 2
    return ", ".join(teile) or None


def hinzufuegen(begriff: str) -> None:
    """Neuen Begriff unter „Personen (gelernt)“ eintragen, falls er noch nicht drinsteht."""
    begriff = begriff.strip()
    if not begriff or begriff.casefold() in {b.casefold() for b in begriffe()}:
        return
    anlegen()
    text = DATEI.read_text(encoding="utf-8-sig")
    if GELERNT not in text:
        text = text.rstrip("\n") + "\n\n" + GELERNT + "\n"
    text = text.rstrip("\n") + "\n" + begriff + "\n"
    DATEI.write_text(text, encoding="utf-8")
    log.info("Wörterbuch: „%s“ ergänzt", begriff)
