"""Sprechertrennung über Stimmabdrücke (ersetzt die sherpa-onnx-Diarisierung, Messungen vom 15.10.2026).

Ablauf: Sprachstücke (VAD) → überlappende Fenster à 1,5 s → Stimmabdruck je Fenster (eres2netv2)
→ benachbarte, sehr ähnliche Fenster zu Läufen zusammenfassen → Läufe gruppieren (Average Linkage)
→ kleine Restgruppen der ähnlichsten großen Gruppe zuschlagen → Zeitachse mit Mehrheitsentscheid.

Gemessen an gebauten Gesprächen (Jan + fremde Stimme, AAC wie vom Handy): 100 % der Sprechzeit
richtig, eine Stimme allein bleibt ein Sprecher. Die alte Diarisierung lag bei ~70 % und zerlegte
Jans Stimme in bis zu 4 Sprecher.
"""
from dataclasses import dataclass

import numpy as np

from . import einstellungen as E

FENSTER_S = 1.5
SCHRITT_S = 0.75
LAUF_SCHWELLE = 0.6  # Nachbarfenster ab dieser Ähnlichkeit gelten sicher als dieselbe Stimme
MIN_GRUPPE_S = 8.0  # kleinere Gruppen sind Fetzen (Lachen, Räuspern), höchstens 5 % der Sprechzeit
RASTER_S = 0.25


@dataclass
class Stueck:
    start: float
    end: float
    speaker: int


def _sprachstuecke(audio: np.ndarray) -> list[tuple[float, float]]:
    from faster_whisper.vad import VadOptions, get_speech_timestamps

    st = get_speech_timestamps(audio, VadOptions(min_silence_duration_ms=300), sampling_rate=E.ABTASTRATE)
    return [(s["start"] / E.ABTASTRATE, s["end"] / E.ABTASTRATE) for s in st]


def _fenster(stuecke: list[tuple[float, float]]) -> list[tuple[float, float]]:
    aus = []
    for s, e in stuecke:
        if e - s < 0.5:
            continue
        if e - s <= FENSTER_S * 1.5:
            aus.append((s, e))
            continue
        t = s
        while t + FENSTER_S <= e:
            aus.append((t, t + FENSTER_S))
            t += SCHRITT_S
        if aus[-1][1] < e:
            aus.append((max(s, e - FENSTER_S), e))
    return aus


def _abdruecke(audio: np.ndarray, fenster: list[tuple[float, float]]) -> np.ndarray:
    import sherpa_onnx

    ex = sherpa_onnx.SpeakerEmbeddingExtractor(
        sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=str(E.STIMMEN_MODELL), num_threads=E.THREADS)
    )
    vek = []
    for s, e in fenster:
        st = ex.create_stream()
        st.accept_waveform(E.ABTASTRATE, audio[int(s * E.ABTASTRATE):int(e * E.ABTASTRATE)])
        st.input_finished()
        v = np.array(ex.compute(st), dtype=np.float32)
        vek.append(v / max(np.linalg.norm(v), 1e-9))
    return np.array(vek)


def _average_linkage(S: np.ndarray, groesse: np.ndarray, schwelle: float) -> np.ndarray:
    """Legt immer die zwei ähnlichsten Gruppen zusammen (Ähnlichkeit = Durchschnitt aller Paare)."""
    G = S.astype(np.float64).copy()
    np.fill_diagonal(G, -2)
    groesse = groesse.astype(np.float64).copy()
    aktiv = np.ones(len(G), bool)
    zu = np.arange(len(G))
    while aktiv.sum() > 1:
        M = np.where(aktiv[:, None] & aktiv[None, :], G, -2)
        i, j = np.unravel_index(np.argmax(M), M.shape)
        if M[i, j] < schwelle:
            break
        i, j = min(i, j), max(i, j)
        G[i, :] = (G[i, :] * groesse[i] + G[j, :] * groesse[j]) / (groesse[i] + groesse[j])
        G[:, i] = G[i, :]
        G[i, i] = -2
        groesse[i] += groesse[j]
        aktiv[j] = False
        zu[zu == j] = i
    _, nummern = np.unique(zu, return_inverse=True)
    return nummern


def trennen(audio: np.ndarray) -> tuple[list[Stueck], dict[int, np.ndarray]]:
    """Sprecher-Zeitachse und je Sprecher ein mittlerer Stimmabdruck (für späteres Wiedererkennen)."""
    fe = _fenster(_sprachstuecke(audio))
    if not fe:
        return [], {}
    V = _abdruecke(audio, fe)

    # Läufe: Nachbarfenster aus demselben Sprachstück, die sich sehr ähneln
    lauf = [0]
    for k in range(1, len(fe)):
        selbes_stueck = fe[k][0] < fe[k - 1][1]
        lauf.append(lauf[-1] if selbes_stueck and V[k] @ V[k - 1] >= LAUF_SCHWELLE else lauf[-1] + 1)
    lauf = np.array(lauf)
    R = np.zeros((len(fe), lauf[-1] + 1), dtype=np.float32)
    R[np.arange(len(fe)), lauf] = 1
    groesse = R.sum(0)
    S = (R.T @ (V @ V.T) @ R) / np.outer(groesse, groesse)
    nr = _average_linkage(S, groesse, E.SPRECHER_SCHWELLE)[lauf]

    # Restgruppen (Fetzen) der ähnlichsten großen Gruppe zuschlagen
    dauer = {n: float(np.sum(nr == n)) * SCHRITT_S for n in set(nr.tolist())}
    grenze = min(MIN_GRUPPE_S, 0.05 * sum(dauer.values()))
    gross = [n for n, d in dauer.items() if d >= grenze] or [max(dauer, key=dauer.get)]
    mitte = {n: V[nr == n].mean(0) for n in gross}
    mitte = {n: m / np.linalg.norm(m) for n, m in mitte.items()}
    for k in range(len(nr)):
        if nr[k] not in mitte:
            nr[k] = max(mitte, key=lambda n: float(V[k] @ mitte[n]))

    # Zeitachse: je Rasterpunkt entscheidet die Mehrheit der überlappenden Fenster
    stimmen: dict[int, dict[int, int]] = {}
    for (s, e), n in zip(fe, nr):
        for t in range(int(np.ceil(s / RASTER_S)), int(np.ceil(e / RASTER_S))):
            stimmen.setdefault(t, {}).setdefault(int(n), 0)
            stimmen[t][int(n)] += 1
    stuecke: list[Stueck] = []
    for t in sorted(stimmen):
        n = max(stimmen[t], key=stimmen[t].get)
        start = t * RASTER_S
        if stuecke and stuecke[-1].speaker == n and start - stuecke[-1].end < 1e-6:
            stuecke[-1].end = start + RASTER_S
        else:
            stuecke.append(Stueck(start, start + RASTER_S, n))
    return stuecke, mitte
