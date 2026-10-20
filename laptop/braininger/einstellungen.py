"""Orte und feste Werte des Laptop-Programms. Alles Private liegt außerhalb des GitHub-Projekts."""
import os
from pathlib import Path

LAPTOP_VERSION = "0.1.0"

# Projektordner „Claude Projekte\Braininger“ (eine Ebene über dem GitHub-Projekt)
PROJEKT = Path(__file__).resolve().parents[3]

# Google Drive für Desktop: hier legt die Handy-App die Aufnahmen ab
EINGANG = Path(os.environ.get("BRAININGER_EINGANG", r"G:\Meine Ablage\Braininger\Eingang"))

# Lokale Daten (Texte, Ergebnisse, Zustand). Nicht im GitHub-Projekt.
DATEN = Path(os.environ.get("BRAININGER_DATEN", PROJEKT / "daten"))
AUFNAHMEN_LOKAL = DATEN / "aufnahmen"
PROTOKOLL = DATEN / "protokoll.txt"
PERSOENLICH = DATEN / "persoenlich.json"  # Name und Beschreibung des Nutzers für Claude
CLAUDE_ARBEITSORDNER = DATEN / "claude_arbeitsordner"  # leer, damit Claude keine fremden Projektdateien sieht

# Notizen und Tagesübersichten als lesbare Dateien, nach Monaten sortiert („Claude Projekte\Braininger Notizen“)
NOTIZEN = Path(os.environ.get("BRAININGER_NOTIZEN", PROJEKT.parent / "Braininger Notizen"))

MODELLE = Path(os.environ.get("BRAININGER_MODELLE", PROJEKT / "modelle"))
WHISPER_MODELL = MODELLE / "whisper-large-v3-turbo"
# Stimmabdrücke: am 15.10.2026 gegen campplus_en, wespeaker resnet34 und titanet_small verglichen,
# eres2netv2 trennt kurze deutsche Sätze am deutlichsten (AUC 0,999 statt 0,62 beim alten Modell).
STIMMEN_MODELL = MODELLE / "3dspeaker_speech_eres2netv2_sv_zh-cn_16k-common.onnx"

# Gemessen am 14.10.2026 (Tempotest): turbo, beam 1, int8, 8 Threads ≈ 0,76x Echtzeit
# 15.10.2026, 3 min Hörtext: 4 Threads 0,40–0,42x, 5: 0,42x, 6: 0,42x, 8: 0,47x, 12: 0,53x, 3: 0,82x.
# Der i5-12450H hat 4 schnelle Kerne; mehr Threads landen auf den sparsamen Kernen und bremsen.
THREADS = 4
WHISPER_BEAM = 1
SPRECHER_SCHWELLE = 0.30  # Ähnlichkeit, ab der Gruppen zusammengelegt werden; 0,25–0,35 trafen in Tests 100 %

ABTASTRATE = 16000
PAUSE_AB_S = 30  # längere Stille: Aufnahme dort teilen und im Text als [Pause …] markieren
PRUEFABSTAND_S = 30  # wie oft der Eingang angeschaut wird
SYNC_GEDULD_S = 30 * 60  # so lange auf fehlende Stücke warten, bevor ohne sie gearbeitet wird
WARNGRENZE_FREI_GB = 10

# Grill 20.10.2026 (Frage 62): Der Laptop beantwortet nur noch Fragen und wertet auf Wunsch neu aus.
# Notizdateien, Tagesübersicht-Dateien und Aufgaben.md auf dem Desktop entfallen. True schaltet sie wieder ein.
DATEIEN_AM_DESKTOP = False
