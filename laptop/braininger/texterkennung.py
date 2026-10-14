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

    def als_zeilen(self) -> str:
        zeilen = []
        for a in self.abschnitte:
            marke = f"{int(a.start // 60):02d}:{int(a.start % 60):02d}"
            if a.pause:
                zeilen.append(f"[{marke}] {a.text}")
            else:
                zeilen.append(f"[{marke}] {a.sprecher + ': ' if a.sprecher else ''}{a.text}")
        return "\n".join(zeilen)


def sprecher_trennen(audio: np.ndarray) -> list:
    import sherpa_onnx

    config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(E.SEGMENTIERUNG_MODELL)),
            num_threads=E.THREADS,
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(E.STIMMEN_MODELL), num_threads=E.THREADS),
        clustering=sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=E.SPRECHER_SCHWELLE),
        min_duration_on=0.3,
        min_duration_off=0.5,
    )
    sd = sherpa_onnx.OfflineSpeakerDiarization(config)
    return list(sd.process(audio).sort_by_start_time())


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


def erkennen(audio: np.ndarray, gespraech: bool, fortschritt: Callable[[str, float], None] = lambda s, p: None) -> Text:
    """fortschritt(schritt, anteil 0..1) wird zwischendurch aufgerufen, für die Statusanzeige in der App."""
    from faster_whisper import WhisperModel

    dauer = len(audio) / E.ABTASTRATE
    sprecher_stuecke = []
    if gespraech and dauer > 0:
        fortschritt("sprecher", 0.0)
        sprecher_stuecke = sprecher_trennen(audio)

    fortschritt("text", 0.0)
    bloecke = sprechbloecke(audio) if dauer > 0 else []
    roh = []  # (start, ende, text) oder (start, ende, None) für eine Pause
    sprachen: dict[str, float] = {}  # Sprache -> gesprochene Sekunden
    sicherheit: dict[str, float] = {}
    if bloecke:
        modell = WhisperModel(str(E.WHISPER_MODELL), device="cpu", compute_type="int8", cpu_threads=E.THREADS)
        try:
            for i, (b_start, b_ende) in enumerate(bloecke):
                if i > 0:
                    vorher = bloecke[i - 1][1]
                    roh.append((vorher, b_start, None))
                # Jeder Block einzeln, damit Whisper nichts über eine lange Pause hinweg zusammenklebt
                von = max(0, int((b_start - 0.5) * E.ABTASTRATE))
                bis = min(len(audio), int((b_ende + 0.5) * E.ABTASTRATE))
                versatz = von / E.ABTASTRATE
                segmente, info = modell.transcribe(audio[von:bis], beam_size=E.WHISPER_BEAM, vad_filter=True)
                laenge = (bis - von) / E.ABTASTRATE
                for s in segmente:  # Generator: die eigentliche Arbeit passiert hier
                    text = s.text.strip()
                    # Zeilen, die über das Ende des Audios hinausreichen, hat Whisper erfunden
                    # (typisch am abgeschnittenen Ende vor einer Pause, gemessen am 15.10.2026)
                    if text and s.end <= laenge + 0.5:
                        roh.append((s.start + versatz, s.end + versatz, text))
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
    for start, ende, text in roh:
        if text is None:
            abschnitte.append(Abschnitt(start, ende, None, f"[Pause {_pausenzeit(ende - start)}]", pause=True))
            continue
        name = None
        if gespraech:
            nummer = _sprecher_fuer(start, ende, sprecher_stuecke)
            if nummer is None:
                name = "?"
            else:
                name = reihenfolge.setdefault(nummer, f"S{len(reihenfolge) + 1}")
        abschnitte.append(Abschnitt(start, ende, name, text))

    return Text(
        abschnitte=abschnitte,
        sprache=sprache,
        sprache_sicherheit=round(sicherheit.get(sprache, 0.0), 2),
        sprecher_anzahl=len(reihenfolge) if gespraech else None,
    )
