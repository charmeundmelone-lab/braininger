"""Eine Aufnahme im Drive-Eingang: info.json lesen, Stücke prüfen und zu einem Audio zusammenfügen."""
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from faster_whisper.audio import decode_audio

from .einstellungen import ABTASTRATE


@dataclass
class Aufnahme:
    ordner: Path
    id: str
    modus: str  # "gedanke" oder "gespraech"
    start: str
    ende: str | None
    dauer_s: int | None
    stuecke: list[str]
    mikrofon: str
    status: str  # "laeuft", "fertig" oder "unterbrochen"
    hinweise: list[str] = field(default_factory=list)
    app_version: str = ""
    markierungen: list[float] = field(default_factory=list)  # Markier-Knopf, Sekunden ab Beginn
    fotos: list[tuple[str, float]] = field(default_factory=list)  # Foto-Knopf: (Datei, Sekunde)
    auswertung: str = "laptop"  # "handy": Text und Notizen macht die App selbst (ab Grill 20.10.2026)

    @property
    def abgeschlossen(self) -> bool:
        return self.status in ("fertig", "unterbrochen")

    @property
    def ergebnis_datei(self) -> Path:
        return self.ordner / "ergebnis.json"

    def fehlende_stuecke(self) -> list[str]:
        """Stücke, die Google Drive für Desktop (noch) nicht oder leer zeigt."""
        fehlend = []
        for name in self.stuecke + [f for f, _ in self.fotos]:
            try:
                if (self.ordner / name).stat().st_size == 0:
                    fehlend.append(name)
            except OSError:
                fehlend.append(name)
        return fehlend


def lesen(ordner: Path) -> Aufnahme | None:
    """None, wenn info.json fehlt oder (noch) nicht lesbar ist, z. B. weil sie gerade synchronisiert wird."""
    try:
        j = json.loads((ordner / "info.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return Aufnahme(
        ordner=ordner,
        id=j.get("id") or ordner.name,
        modus=j.get("modus", "gedanke"),
        start=j.get("start", ""),
        ende=j.get("ende"),
        dauer_s=j.get("dauer_s"),
        stuecke=list(j.get("stuecke") or []),
        mikrofon=j.get("mikrofon", ""),
        status=j.get("status", ""),
        hinweise=list(j.get("hinweise") or []),
        app_version=j.get("app_version", ""),
        markierungen=[float(m) for m in j.get("markierungen") or [] if isinstance(m, (int, float))],
        fotos=[(str(f["datei"]), float(f.get("zeit_s") or 0)) for f in j.get("fotos") or []
               if isinstance(f, dict) and re.fullmatch(r"foto_[\w.]+\.jpg", str(f.get("datei", "")))],
        auswertung=j.get("auswertung") or "laptop",
    )


@dataclass
class Zusammengefuegt:
    audio: np.ndarray  # 16 kHz mono float32
    stueck_beginn_s: list[tuple[str, float]]  # wo jedes Stück im Gesamtaudio anfängt
    hinweise: list[str]

    @property
    def dauer_s(self) -> float:
        return len(self.audio) / ABTASTRATE


def zusammenfuegen(aufnahme: Aufnahme) -> Zusammengefuegt:
    """Hängt die Stücke in Reihenfolge aneinander. Ein unlesbares Stück (typisch: das letzte nach
    einer Unterbrechung, MP4 ohne Abschluss) wird übersprungen und als Hinweis vermerkt."""
    teile, beginn, hinweise = [], [], []
    position = 0
    for name in aufnahme.stuecke:
        pfad = aufnahme.ordner / name
        try:
            audio = decode_audio(str(pfad), sampling_rate=ABTASTRATE)
        except Exception as e:  # PyAV wirft je nach Schaden verschiedene Fehler
            hinweise.append(f"Stück {name} ist nicht lesbar und fehlt im Text ({type(e).__name__}).")
            continue
        if len(audio) == 0:
            hinweise.append(f"Stück {name} ist leer.")
            continue
        beginn.append((name, position / ABTASTRATE))
        teile.append(audio)
        position += len(audio)
    gesamt = np.concatenate(teile) if teile else np.zeros(0, dtype=np.float32)
    return Zusammengefuegt(audio=gesamt, stueck_beginn_s=beginn, hinweise=hinweise)
