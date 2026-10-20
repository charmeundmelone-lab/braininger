r"""Schreibt Anweisungen, Vorlagen und Antwort-Schema der Auswertung für die Handy-App nach
android/app/src/main/assets/auswertung.json. So werten Handy und Laptop nach denselben Regeln aus.

Name und Beschreibung des Nutzers kommen nicht hinein (sie stehen nur lokal in daten/persoenlich.json bzw. in der App).
Nach jeder Änderung an auswertung.py oder vorlagen.py neu ausführen:  .venv\Scripts\python werkzeuge\auswertung_exportieren.py
"""
import json
import sys
from pathlib import Path

LAPTOP = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(LAPTOP))

from braininger import auswertung  # noqa: E402
from braininger.vorlagen import STANDARD_GEDANKE, VORLAGEN  # noqa: E402

ZIEL = LAPTOP.parent / "android" / "app" / "src" / "main" / "assets" / "auswertung.json"

daten = {
    "version": 1,
    "system_prompt": auswertung.SYSTEM_PROMPT.replace("Ein Spracherkenner am Laptop", "Ein Spracherkenner am Handy"),
    "vorlagen_text": auswertung._vorlagen_text(),
    "schema": auswertung.SCHEMA,
    "kennungen": [v.kennung for v in VORLAGEN],
    "standard_gedanke": STANDARD_GEDANKE,
}
ZIEL.parent.mkdir(parents=True, exist_ok=True)
ZIEL.write_text(json.dumps(daten, ensure_ascii=False, indent=1), encoding="utf-8")
print(f"{ZIEL} geschrieben ({ZIEL.stat().st_size} Bytes)")
