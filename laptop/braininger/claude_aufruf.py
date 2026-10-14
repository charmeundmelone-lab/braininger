"""Ruft Claude Code ohne Fenster auf, angemeldet mit Jans Abo-Schlüssel aus der Windows-Anmeldeinformationsverwaltung.

Der Schlüssel wird nie in eine Datei oder eine dauerhafte Umgebungsvariable geschrieben,
sondern nur für den einzelnen Claude-Aufruf übergeben.
"""
import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

import keyring

KEYRING_DIENST = "Braininger"
KEYRING_NAME = "claude_oauth_token"
CLAUDE_EXE = Path.home() / ".local" / "bin" / "claude.exe"


class ClaudeFehler(Exception):
    pass


class ClaudeLimitErreicht(ClaudeFehler):
    """Nutzungslimit des Abos erreicht, später erneut versuchen."""


@dataclass
class ClaudeAntwort:
    text: str
    dauer_ms: int


def schluessel_vorhanden() -> bool:
    return keyring.get_password(KEYRING_DIENST, KEYRING_NAME) is not None


def frage(prompt: str, zeitlimit_s: int = 600) -> ClaudeAntwort:
    schluessel = keyring.get_password(KEYRING_DIENST, KEYRING_NAME)
    if not schluessel:
        raise ClaudeFehler("Kein Claude-Schlüssel hinterlegt. Bitte 2_Claude_Schluessel_eintragen.bat ausführen.")

    exe = str(CLAUDE_EXE) if CLAUDE_EXE.exists() else shutil.which("claude")
    if not exe:
        raise ClaudeFehler("Claude Code ist nicht installiert.")

    umgebung = os.environ.copy()
    for stoerer in ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"):
        umgebung.pop(stoerer, None)  # würden sonst Vorrang vor dem Abo haben
    umgebung["CLAUDE_CODE_OAUTH_TOKEN"] = schluessel

    lauf = subprocess.run(
        [exe, "-p", "--output-format", "json"],
        input=prompt,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env=umgebung,
        timeout=zeitlimit_s,
    )
    try:
        ergebnis = json.loads(lauf.stdout)
    except json.JSONDecodeError:
        raise ClaudeFehler(f"Unerwartete Ausgabe von Claude Code: {lauf.stdout[:300]} {lauf.stderr[:300]}")

    if ergebnis.get("is_error"):
        meldung = str(ergebnis.get("result", ""))
        if "limit" in meldung.lower():
            raise ClaudeLimitErreicht(meldung)
        raise ClaudeFehler(meldung)
    return ClaudeAntwort(text=ergebnis["result"], dauer_ms=ergebnis.get("duration_ms", 0))
