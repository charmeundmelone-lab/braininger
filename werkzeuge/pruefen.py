"""Prüft alle Aufnahmen im Drive-Eingang auf Verlust.

Für jede Aufnahme: Sind alle Stücke aus info.json da? Passt die Summe der Stückdauern
zu dauer_s? Gibt es Hinweise? Welchen Status hat ergebnis.json?

Aufruf (braucht PyAV, z. B. aus der tempotest-venv):
    python pruefen.py ["G:\\Meine Ablage\\Braininger\\Eingang"] [--nur NAMENSTEIL]
"""
import json
import sys
from pathlib import Path

try:
    import av
except ImportError:
    av = None

EINGANG = Path(r"G:\Meine Ablage\Braininger\Eingang")


def dauer(datei: Path) -> float | None:
    if av is None:
        return None
    try:
        with av.open(str(datei)) as c:
            s = c.streams.audio[0]
            if s.duration is not None and s.time_base is not None:
                return float(s.duration * s.time_base)
            return c.duration / 1_000_000 if c.duration else None
    except Exception:
        return None


def json_lesen(datei: Path) -> dict | None:
    try:
        return json.loads(datei.read_text(encoding="utf-8"))
    except Exception:
        return None


def pruefen(ordner: Path) -> tuple[bool, str]:
    info = json_lesen(ordner / "info.json")
    ergebnis = json_lesen(ordner / "ergebnis.json")
    status = (ergebnis or {}).get("status", "keine ergebnis.json")
    if info is None:
        vorhanden = sorted(p.name for p in ordner.glob("teil_*.m4a"))
        return False, f"info.json fehlt (noch im Upload?), {len(vorhanden)} Stücke da, Ergebnis: {status}"

    stuecke = info.get("stuecke", [])
    fehlend = [s for s in stuecke if not (ordner / s).exists()]
    hinweise = info.get("hinweise", [])
    soll = info.get("dauer_s")

    teile = [f"{info.get('modus')}, {len(stuecke)} Stücke, dauer_s {soll}, Status {info.get('status')}"]
    ok = info.get("status") == "fertig"
    if fehlend:
        # Nach 3 Tagen löscht die App das Audio absichtlich. Das ist kein Verlust.
        if status == "fertig" and len(fehlend) == len(stuecke):
            teile.append("Audio gelöscht (nach Text fertig)")
        else:
            ok = False
            teile.append(f"FEHLEN: {', '.join(fehlend)}")
    else:
        dauern = [dauer(ordner / s) for s in stuecke]
        if all(d is not None for d in dauern):
            summe = sum(dauern)
            teile.append(f"Summe Stücke {summe:.1f} s")
            if soll is not None and abs(summe - float(soll)) > 2.0:
                ok = False
                teile.append(f"ABWEICHUNG {summe - float(soll):+.1f} s")
    if hinweise:
        teile.append("Hinweise: " + " | ".join(hinweise))
    if status != "fertig":
        ok = False
    teile.append(f"Ergebnis: {status}")
    return ok, "; ".join(teile)


def main() -> None:
    args = [a for a in sys.argv[1:]]
    nur = None
    if "--nur" in args:
        i = args.index("--nur")
        nur = args[i + 1]
        del args[i:i + 2]
    eingang = Path(args[0]) if args else EINGANG
    if av is None:
        print("Hinweis: PyAV fehlt, Stückdauern werden nicht geprüft.")
    gut = 0
    alle = 0
    for ordner in sorted(p for p in eingang.iterdir() if p.is_dir()):
        if nur and nur not in ordner.name:
            continue
        ok, text = pruefen(ordner)
        alle += 1
        gut += ok
        print(f"{'OK  ' if ok else 'PRÜF'} {ordner.name}: {text}")
    print(f"\n{gut} von {alle} vollständig und ausgewertet.")


if __name__ == "__main__":
    main()
