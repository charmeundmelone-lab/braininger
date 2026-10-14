"""Frühtest a, Laptop-Seite: schreibt über Google Drive für Desktop (G:) in den Testordner der Handy-App.

1. wartet auf G:\\Meine Ablage\\Braininger\\Test\\vorab_angelegt.txt (legt die App mit „1 VORBEREITEN“ an)
2. überschreibt diese Datei an Ort und Stelle (Weg 2) und legt vom_laptop.txt neu an (Weg 1 ohne API)
3. wartet auf bericht.txt (schreibt die App mit „2 PRÜFEN“) und gibt ihn aus
"""
import sys
import time
from datetime import datetime
from pathlib import Path

ORDNER = Path(r"G:\Meine Ablage\Braininger\Test")
WARTEZEIT_S = 45 * 60


def warten_auf(datei: Path, neuer_als: float = 0) -> bool:
    ende = time.time() + WARTEZEIT_S
    while time.time() < ende:
        try:
            if datei.exists() and datei.stat().st_mtime > neuer_als:
                return True
        except OSError:
            pass
        time.sleep(5)
    return False


def main() -> None:
    vorab = ORDNER / "vorab_angelegt.txt"
    print(f"Warte auf {vorab} …", flush=True)
    if not warten_auf(vorab):
        sys.exit("Testordner nicht aufgetaucht.")
    print("Gefunden. Inhalt vom Handy:", vorab.read_text(encoding="utf-8").strip(), flush=True)

    stempel = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    # In-place überschreiben (gleiche Datei, keine neue), damit die Drive-ID erhalten bleibt.
    with open(vorab, "r+", encoding="utf-8") as f:
        f.seek(0)
        f.write(f"LAPTOP hat diese Datei überschrieben {stempel}\n")
        f.truncate()
    (ORDNER / "vom_laptop.txt").write_text(f"LAPTOP hat diese Datei neu angelegt {stempel}\n", encoding="utf-8")
    geschrieben = time.time()
    print(f"Laptop hat geschrieben ({stempel}). Warte auf bericht.txt vom Handy …", flush=True)

    bericht = ORDNER / "bericht.txt"
    if not warten_auf(bericht, neuer_als=geschrieben):
        sys.exit("Kein neuer Bericht vom Handy gekommen.")
    time.sleep(5)
    print("\n" + bericht.read_text(encoding="utf-8"), flush=True)


if __name__ == "__main__":
    main()
