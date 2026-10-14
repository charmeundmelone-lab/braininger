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


def erkennen(audio: np.ndarray, gespraech: bool, fortschritt: Callable[[str, float], None] = lambda s, p: None) -> Text:
    """fortschritt(schritt, anteil 0..1) wird zwischendurch aufgerufen, für die Statusanzeige in der App."""
    from faster_whisper import WhisperModel

    dauer = len(audio) / E.ABTASTRATE
    sprecher_stuecke = []
    if gespraech and dauer > 0:
        fortschritt("sprecher", 0.0)
        sprecher_stuecke = sprecher_trennen(audio)

    fortschritt("text", 0.0)
    modell = WhisperModel(str(E.WHISPER_MODELL), device="cpu", compute_type="int8", cpu_threads=E.THREADS)
    try:
        segmente, info = modell.transcribe(audio, beam_size=E.WHISPER_BEAM, vad_filter=True)
        roh = []
        for s in segmente:  # Generator: die eigentliche Arbeit passiert hier
            text = s.text.strip()
            if text:
                roh.append((s.start, s.end, text))
            if dauer:
                fortschritt("text", min(s.end / dauer, 1.0))
    finally:
        del modell
        gc.collect()

    # Sprecher in der Reihenfolge ihres ersten Auftretens nummerieren (S1 spricht zuerst)
    reihenfolge: dict[int, str] = {}
    abschnitte = []
    for start, ende, text in roh:
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
        sprache=info.language,
        sprache_sicherheit=round(info.language_probability, 2),
        sprecher_anzahl=len(reihenfolge) if gespraech else None,
    )
