"""Braininger Lite (Mudita Kompakt): Einrichten per adb. Der Empfang selbst läuft im Laptop-Programm mit
(braininger/kompakt.py), seit 6.11.2026.

Aufruf:
  python kompakt_empfang.py einrichten   Schlüssel erzeugen (falls nötig), an den Kompakt geben, Kabelweg öffnen
  python kompakt_empfang.py              Empfang allein starten (nur zum Testen, wenn das Laptop-Programm nicht läuft)
"""
import logging
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from braininger import kompakt  # noqa: E402

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    if len(sys.argv) > 1 and sys.argv[1] == "einrichten":
        print("Kompakt:", kompakt.einrichten())
    elif kompakt.im_hintergrund_starten():
        while True:
            time.sleep(3600)
