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
    daten: dict | None = None  # bei json_schema: die geprüfte, strukturierte Antwort


def schluessel_vorhanden() -> bool:
    return keyring.get_password(KEYRING_DIENST, KEYRING_NAME) is not None


def frage(
    prompt: str,
    zeitlimit_s: int = 600,
    system_prompt: str | None = None,
    json_schema: dict | None = None,
    arbeitsordner: Path | None = None,
) -> ClaudeAntwort:
    """Ohne system_prompt: normales Claude Code. Mit system_prompt: abgeschottet, ohne Werkzeuge,
    ohne Einstellungen/CLAUDE.md und ohne gespeicherte Sitzung (für die Auswertung der Aufnahmen)."""
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

    befehl = [exe, "-p", "--output-format", "json"]
    if system_prompt is not None:
        befehl += [
            "--system-prompt", system_prompt,
            "--tools", "",
            "--strict-mcp-config",
            "--setting-sources", "",
            "--no-session-persistence",
        ]
    if json_schema is not None:
        befehl += ["--json-schema", json.dumps(json_schema, ensure_ascii=False)]
    if arbeitsordner is not None:
        arbeitsordner.mkdir(parents=True, exist_ok=True)

    try:
        lauf = subprocess.run(
            befehl,
            input=prompt,
            capture_output=True,
            text=True,
            encoding="utf-8",
            env=umgebung,
            timeout=zeitlimit_s,
            cwd=arbeitsordner,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
    except subprocess.TimeoutExpired:
        raise ClaudeFehler(f"Claude hat nach {zeitlimit_s} s nicht geantwortet.")
    try:
        ergebnis = json.loads(lauf.stdout)
    except json.JSONDecodeError:
        raise ClaudeFehler(f"Unerwartete Ausgabe von Claude Code: {lauf.stdout[:300]} {lauf.stderr[:300]}")

    if ergebnis.get("is_error"):
        meldung = str(ergebnis.get("result", ""))
        if "limit" in meldung.lower() or ergebnis.get("api_error_status") == 429:
            raise ClaudeLimitErreicht(meldung)
        raise ClaudeFehler(meldung)
    daten = ergebnis.get("structured_output")
    if json_schema is not None and not isinstance(daten, dict):
        raise ClaudeFehler("Claude hat keine strukturierte Antwort geliefert.")
    return ClaudeAntwort(text=ergebnis.get("result", ""), dauer_ms=ergebnis.get("duration_ms", 0), daten=daten)
