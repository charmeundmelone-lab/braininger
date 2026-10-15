"""Audio → Text (faster-whisper) und bei Gesprächen Sprechertrennung (sherpa-onnx), beides lokal auf der CPU."""
import gc
from dataclasses import asdict, dataclass
from typing import Callable

import numpy as np

from . import einstellungen as E


@dataclass
class Abschnitt:
    start: float
    ende: float
    sprecher: str | None  # "S1", "S2", … nur bei Gesprächen
    text: str
    pause: bool = False  # Platzhalter für eine lange Stille, text z. B. "[Pause 3 min]"

    def als_dict(self) -> dict:
        d = asdict(self)
        d["start"], d["ende"] = round(self.start, 1), round(self.ende, 1)
        return d


@dataclass
class Text:
    abschnitte: list[Abschnitt]
    sprache: str
    sprache_sicherheit: float
    sprecher_anzahl: int | None
    stimmen: dict[str, list[float]] | None = None  # mittlerer Stimmabdruck je Sprecher (S1, S2 …)

    def als_zeilen(self) -> str:
        zeilen = []
        for a in self.abschnitte:
            marke = f"{int(a.start // 60):02d}:{int(a.start % 60):02d}"
            if a.pause:
                zeilen.append(f"[{marke}] {a.text}")
            else:
                zeilen.append(f"[{marke}] {a.sprecher + ': ' if a.sprecher else ''}{a.text}")
        return "\n".join(zeilen)


def _sprecher_fuer(start: float, ende: float, stuecke: list) -> int | None:
    """Sprecher mit der größten zeitlichen Überlappung zum Textabschnitt."""
    beste, max_u = None, 0.0
    for s in stuecke:
        u = min(ende, s.end) - max(start, s.start)
        if u > max_u:
            beste, max_u = s.speaker, u
    return beste


def _pausenzeit(s: float) -> str:
    return f"{s / 60:.0f} min" if s >= 90 else f"{s:.0f} s"


def sprechbloecke(audio: np.ndarray) -> list[tuple[float, float]]:
    """Abschnitte mit Sprache (Sekunden), getrennt an Stillen ab PAUSE_AB_S. Leer bei reiner Stille."""
    from faster_whisper.vad import VadOptions, get_speech_timestamps

    stellen = get_speech_timestamps(audio, VadOptions(), sampling_rate=E.ABTASTRATE)
    bloecke: list[list[float]] = []
    for st in stellen:
        start, ende = st["start"] / E.ABTASTRATE, st["end"] / E.ABTASTRATE
        if bloecke and start - bloecke[-1][1] < E.PAUSE_AB_S:
            bloecke[-1][1] = ende
        else:
            bloecke.append([start, ende])
    return [(a, b) for a, b in bloecke]


def _naechster_sprecher(start: float, ende: float, stuecke: list) -> int | None:
    """Wie _sprecher_fuer, aber ohne Überlappung der zeitlich nächste Sprecher (Wort in einer kurzen Lücke)."""
    nummer = _sprecher_fuer(start, ende, stuecke)
    if nummer is not None or not stuecke:
        return nummer
    mitte = (start + ende) / 2
    return min(stuecke, key=lambda s: min(abs(s.start - mitte), abs(s.end - mitte))).speaker


MIN_WECHSEL_S = 0.6  # kürzere Wortgruppen bleiben beim Nachbarn (einzelnes „Ja,“ springt sonst hin und her)


def _nach_sprechern(segment, versatz: float, stuecke: list) -> list[tuple[float, float, str, int | None]]:
    """Teilt ein Whisper-Segment an Sprecherwechseln, Wort für Wort (braucht word_timestamps)."""
    woerter = [w for w in (segment.words or []) if w.word.strip()]
    if not woerter:  # ohne Wortzeiten: ganzes Segment
        s, e = segment.start + versatz, segment.end + versatz
        return [(s, e, segment.text.strip(), _naechster_sprecher(s, e, stuecke))]
    teile: list[list] = []
    for w in woerter:
        nummer = _naechster_sprecher(w.start + versatz, w.end + versatz, stuecke)
        if teile and teile[-1][3] == nummer:
            teile[-1][1] = w.end + versatz
            teile[-1][2] += w.word
        else:
            teile.append([w.start + versatz, w.end + versatz, w.word, nummer])
    # zu kurze Wortgruppen dem längeren Nachbarn zuschlagen
    k = 0
    while len(teile) > 1 and k < len(teile):
        s, e, txt, n = teile[k]
        if e - s < MIN_WECHSEL_S:
            if k == 0:
                nachbar = 1
            elif k == len(teile) - 1:
                nachbar = k - 1
            else:
                vor, nach = teile[k - 1], teile[k + 1]
                nachbar = k - 1 if vor[1] - vor[0] >= nach[1] - nach[0] else k + 1
            z = teile[nachbar]
            if nachbar < k:
                z[1], z[2] = e, z[2] + txt
            else:
                z[0], z[2] = s, txt + z[2]
            del teile[k]
            # gleiche Sprecher, die jetzt nebeneinander stehen, zusammenlegen
            j = 1
            while j < len(teile):
                if teile[j][3] == teile[j - 1][3]:
                    teile[j - 1][1], teile[j - 1][2] = teile[j][1], teile[j - 1][2] + teile[j][2]
                    del teile[j]
                else:
                    j += 1
            k = 0
            continue
        k += 1
    return [(s, e, txt.strip(), n) for s, e, txt, n in teile if txt.strip()]


def erkennen(audio: np.ndarray, gespraech: bool, fortschritt: Callable[[str, float], None] = lambda s, p: None) -> Text:
    """fortschritt(schritt, anteil 0..1) wird zwischendurch aufgerufen, für die Statusanzeige in der App."""
    from faster_whisper import WhisperModel

    from . import sprecher

    dauer = len(audio) / E.ABTASTRATE
    sprecher_stuecke: list = []
    abdruecke: dict = {}
    if gespraech and dauer > 0:
        fortschritt("sprecher", 0.0)
        sprecher_stuecke, abdruecke = sprecher.trennen(audio)

    fortschritt("text", 0.0)
    bloecke = sprechbloecke(audio) if dauer > 0 else []
    roh = []  # (start, ende, text, sprecher-nummer) oder (start, ende, None, None) für eine Pause
    sprachen: dict[str, float] = {}  # Sprache -> gesprochene Sekunden
    sicherheit: dict[str, float] = {}
    if bloecke:
        modell = WhisperModel(str(E.WHISPER_MODELL), device="cpu", compute_type="int8", cpu_threads=E.THREADS)
        try:
            for i, (b_start, b_ende) in enumerate(bloecke):
                if i > 0:
                    vorher = bloecke[i - 1][1]
                    roh.append((vorher, b_start, None, None))
                # Jeder Block einzeln, damit Whisper nichts über eine lange Pause hinweg zusammenklebt
                von = max(0, int((b_start - 0.5) * E.ABTASTRATE))
                bis = min(len(audio), int((b_ende + 0.5) * E.ABTASTRATE))
                versatz = von / E.ABTASTRATE
                # Bei Gesprächen Zeiten je Wort, damit ein Satz am Sprecherwechsel geteilt werden kann
                segmente, info = modell.transcribe(audio[von:bis], beam_size=E.WHISPER_BEAM, vad_filter=True,
                                                   word_timestamps=gespraech)
                laenge = (bis - von) / E.ABTASTRATE
                for s in segmente:  # Generator: die eigentliche Arbeit passiert hier
                    text = s.text.strip()
                    # Zeilen, die über das Ende des Audios hinausreichen, hat Whisper erfunden
                    # (typisch am abgeschnittenen Ende vor einer Pause, gemessen am 15.10.2026)
                    if text and s.end <= laenge + 0.5:
                        if gespraech:
                            roh += _nach_sprechern(s, versatz, sprecher_stuecke)
                        else:
                            roh.append((s.start + versatz, s.end + versatz, text, None))
                    fortschritt("text", min((s.end + versatz) / dauer, 1.0))
                sprachen[info.language] = sprachen.get(info.language, 0) + (b_ende - b_start)
                sicherheit.setdefault(info.language, info.language_probability)
        finally:
            del modell
            gc.collect()
    sprache = max(sprachen, key=sprachen.get) if sprachen else "?"

    # Sprecher in der Reihenfolge ihres ersten Auftretens nummerieren (S1 spricht zuerst)
    reihenfolge: dict[int, str] = {}
    abschnitte = []
    for start, ende, text, nummer in roh:
        if text is None:
            abschnitte.append(Abschnitt(start, ende, None, f"[Pause {_pausenzeit(ende - start)}]", pause=True))
            continue
        name = None
        if gespraech:
            name = "?" if nummer is None else reihenfolge.setdefault(nummer, f"S{len(reihenfolge) + 1}")
        abschnitte.append(Abschnitt(start, ende, name, text))

    return Text(
        abschnitte=abschnitte,
        sprache=sprache,
        sprache_sicherheit=round(sicherheit.get(sprache, 0.0), 2),
        sprecher_anzahl=len(reihenfolge) if gespraech else None,
        stimmen={name: [round(float(x), 5) for x in abdruecke[nr]] for nr, name in reihenfolge.items()
                 if nr in abdruecke} or None,
    )
