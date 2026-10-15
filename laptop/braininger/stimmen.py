"""Gedächtnis für Stimmen: wer wie klingt, damit Sprecher in Gesprächen einen Namen bekommen.

daten/stimmen.json (privat, nicht im GitHub-Projekt): {"Name": [Abdruck, Abdruck, …], …}
Je Person mehrere Abdrücke, einer je Aufnahme, weil Mikrofon und Umgebung den Abdruck spürbar ändern
(gemessen 15.10.2026: Jan in verschiedenen Aufnahmen 0,47–0,65, fremde Stimmen zu Jan 0,33–0,54).
Verglichen wird deshalb immer mit dem ähnlichsten gespeicherten Abdruck.

Die eigene Stimme lernt Braininger aus „Gedanke“-Aufnahmen (dort spricht nur der Nutzer), andere Stimmen,
sobald der Nutzer in der App einen Namen vergibt.
"""
import json
import logging

import numpy as np

from . import ergebnis
from . import einstellungen as E

log = logging.getLogger("braininger")

DATEI = E.DATEN / "stimmen.json"
MAX_JE_NAME = 30  # älteste fallen raus, die Stimme darf sich mit der Zeit ändern
ERKENNEN_AB = 0.55  # darunter kein Namensvorschlag
VORSPRUNG = 0.05  # so viel ähnlicher als der Zweitbeste muss ein Name sein


def laden() -> dict[str, list[list[float]]]:
    try:
        return json.loads(DATEI.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def namen() -> list[str]:
    return sorted(laden(), key=str.casefold)


def lernen(name: str, abdruck: list[float]) -> None:
    name = name.strip()
    if not name or not abdruck:
        return
    alle = laden()
    liste = alle.setdefault(name, [])
    neu = [round(float(x), 5) for x in abdruck]
    if neu in liste:  # derselbe Abdruck (z. B. Auftrag nach Claude-Limit wiederholt)
        return
    liste.append(neu)
    del liste[:-MAX_JE_NAME]
    ergebnis.lokal_schreiben(DATEI, json.dumps(alle, ensure_ascii=False))


def _aehnlichkeit(a: np.ndarray, gespeichert: list[list[float]]) -> float:
    M = np.array(gespeichert, dtype=np.float32)
    M /= np.linalg.norm(M, axis=1, keepdims=True)
    return float(np.max(M @ (a / np.linalg.norm(a))))


def zuordnen(sprecher: dict[str, list[float]], fest: dict[str, str] | None = None) -> dict[str, tuple[str, float]]:
    """{"S1": [Abdruck], …} → {"S1": ("Jan", 0.65), …} für Sprecher, die eine bekannte Stimme haben.

    fest: schon vom Nutzer vergebene Namen ({"S2": "Anna"}); diese Sprecher und Namen sind vergeben.
    Jeder Name höchstens einmal je Aufnahme, die ähnlichsten Paare zuerst.
    """
    fest = fest or {}
    bekannt = {n: a for n, a in laden().items() if a and n not in fest.values()}
    paare = []
    for kennung, abdruck in sprecher.items():
        if kennung in fest or not abdruck:
            continue
        v = np.array(abdruck, dtype=np.float32)
        werte = sorted(((_aehnlichkeit(v, a), n) for n, a in bekannt.items()), reverse=True)
        if not werte:
            continue
        bester, name = werte[0]
        zweiter = werte[1][0] if len(werte) > 1 else -1.0
        if bester >= ERKENNEN_AB and bester - zweiter >= VORSPRUNG:
            paare.append((bester, kennung, name))
    aus: dict[str, tuple[str, float]] = {}
    for wert, kennung, name in sorted(paare, reverse=True):
        if kennung not in aus and name not in {n for n, _ in aus.values()}:
            aus[kennung] = (name, round(wert, 2))
    return aus
