"""Bringt Braininger die eigene Stimme aus vorhandenen „Gedanke“-Aufnahmen bei (einmalig, z. B. nach dem Einrichten).

Neue Gedanke-Aufnahmen lernt das Laptop-Programm von selbst. Aufruf mit der Laptop-venv:
    python stimme_lernen.py ID [ID ...]      (Aufnahme-Ordner im Drive-Eingang)
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "laptop"))

from braininger import einstellungen as E  # noqa: E402
from braininger import stimmen  # noqa: E402
from braininger.aufnahme import lesen, zusammenfuegen  # noqa: E402
from braininger.auswertung import eigener_name  # noqa: E402
from braininger import sprecher  # noqa: E402

name = eigener_name()
for aufnahme_id in sys.argv[1:]:
    a = lesen(E.EINGANG / aufnahme_id)
    if a is None or a.modus != "gedanke":
        print(f"{aufnahme_id}: übersprungen (keine Gedanke-Aufnahme)")
        continue
    stuecke, mitten = sprecher.trennen(zusammenfuegen(a).audio)
    anteile: dict[int, float] = {}
    for s in stuecke:
        anteile[s.speaker] = anteile.get(s.speaker, 0) + s.end - s.start
    if not anteile or max(anteile.values()) < 10:
        print(f"{aufnahme_id}: zu wenig Sprache")
        continue
    stimmen.lernen(name, [float(x) for x in mitten[max(anteile, key=anteile.get)]])
    print(f"{aufnahme_id}: Stimme von {name} gelernt ({max(anteile.values()):.0f} s)")
print("Gespeichert:", {k: len(v) for k, v in stimmen.laden().items()})
