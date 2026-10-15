"""Der Ablauf für eine Aufnahme: zusammenfügen → (Sprecher) → Text → Claude → ergebnis.json.

Der lokale Zustand (daten/aufnahmen/<id>/zustand.json) merkt sich, wie weit eine Aufnahme ist.
Ist der Text einmal gesichert, wird er bei einem neuen Versuch (z. B. nach einem Claude-Limit) nicht neu erkannt.
"""
import json
import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

from . import auswertung, ergebnis, notizdateien
from .aufnahme import Aufnahme, zusammenfuegen
from .claude_aufruf import ClaudeFehler, ClaudeLimitErreicht
from .einstellungen import AUFNAHMEN_LOKAL
from .texterkennung import Abschnitt, Text, erkennen
from .vorlagen import NACH_KENNUNG

log = logging.getLogger("braininger")

MAX_VERSUCHE = 3
LIMIT_PAUSE = timedelta(minutes=30)
FEHLER_PAUSE = timedelta(minutes=10)
FORTSCHRITT_ABSTAND_S = 120  # so oft höchstens den Fortschritt ins Drive schreiben


@dataclass
class Zustand:
    status: str = "neu"  # neu | text_fertig | wartet | fertig | fehler
    versuche: int = 0
    weiter_ab: str | None = None
    meldung: str | None = None
    drive_aktuell: bool = False  # hat die letzte ergebnis.json das Drive erreicht?
    zuerst_gesehen: float = 0.0

    @staticmethod
    def lesen(ordner: Path) -> "Zustand":
        try:
            return Zustand(**json.loads((ordner / "zustand.json").read_text(encoding="utf-8")))
        except (OSError, ValueError, TypeError):
            return Zustand(zuerst_gesehen=time.time())

    def speichern(self, ordner: Path) -> None:
        ergebnis.lokal_schreiben(ordner / "zustand.json", json.dumps(self.__dict__, ensure_ascii=False, indent=1))

    def bereit(self) -> bool:
        if self.status == "fertig":
            return False
        if self.status == "fehler" and self.versuche >= MAX_VERSUCHE:
            return False
        if self.weiter_ab and datetime.now().astimezone() < datetime.fromisoformat(self.weiter_ab):
            return False
        return True


def lokaler_ordner(aufnahme_id: str) -> Path:
    return AUFNAHMEN_LOKAL / aufnahme_id


class Bearbeitung:
    def __init__(self, aufnahme: Aufnahme):
        self.a = aufnahme
        self.lokal = lokaler_ordner(aufnahme.id)
        self.zustand = Zustand.lesen(self.lokal)
        self.daten = self._bisheriges_ergebnis() or ergebnis.neu(aufnahme.id)
        self._letzter_fortschritt = 0.0

    def _bisheriges_ergebnis(self) -> dict | None:
        try:
            return json.loads((self.lokal / "ergebnis.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def speichern(self) -> None:
        self.zustand.drive_aktuell = ergebnis.speichern(self.daten, self.a.ergebnis_datei, self.lokal / "ergebnis.json")
        self.zustand.speichern(self.lokal)

    def _fortschritt(self, schritt: str, anteil: float) -> None:
        neuer_schritt = self.daten["schritt"] != schritt
        self.daten.update(status="in_arbeit", schritt=schritt, fortschritt=round(anteil, 2), meldung=None)
        if neuer_schritt or time.monotonic() - self._letzter_fortschritt > FORTSCHRITT_ABSTAND_S:
            self._letzter_fortschritt = time.monotonic()
            self.speichern()

    # --- Schritte ---

    def _gesicherter_text(self) -> Text | None:
        try:
            j = json.loads((self.lokal / "text.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return Text([Abschnitt(**x) for x in j["abschnitte"]], j["sprache"], j["sprache_sicherheit"], j["sprecher_anzahl"])

    def _text_holen(self, fehlende: list[str]) -> Text:
        datei = self.lokal / "text.json"
        if self.zustand.status in ("text_fertig", "wartet", "fehler"):
            gesichert = self._gesicherter_text()
            if gesichert is not None:
                return gesichert

        hinweise = [f"Stück {n} ist nicht im Drive angekommen." for n in fehlende]
        self._fortschritt("text", 0.0)
        audio = zusammenfuegen(self.a)
        hinweise += audio.hinweise
        log.info("%s: %.1f min Audio, %d Stücke", self.a.id, audio.dauer_s / 60, len(audio.stueck_beginn_s))

        t0 = time.perf_counter()
        text = erkennen(audio.audio, gespraech=self.a.modus == "gespraech", fortschritt=self._fortschritt)
        dauer = time.perf_counter() - t0
        log.info("%s: Text in %.0f s (%.2fx Echtzeit), Sprache %s, Sprecher %s",
                 self.a.id, dauer, dauer / max(audio.dauer_s, 1), text.sprache, text.sprecher_anzahl)

        ergebnis.lokal_schreiben(datei, json.dumps({
            "abschnitte": [x.__dict__ for x in text.abschnitte],
            "sprache": text.sprache,
            "sprache_sicherheit": text.sprache_sicherheit,
            "sprecher_anzahl": text.sprecher_anzahl,
            "dauer_audio_s": round(audio.dauer_s, 1),
            "rechenzeit_s": round(dauer, 1),
        }, ensure_ascii=False, indent=1))
        ergebnis.lokal_schreiben(self.lokal / "text.txt", text.als_zeilen())

        self.daten.update(
            text_fertig=True,
            abschnitte=[x.als_dict() for x in text.abschnitte],
            sprache=text.sprache,
            hinweise=hinweise,
        )
        self.daten["dauer_audio_s"] = round(audio.dauer_s, 1)
        self.zustand.status = "text_fertig"
        self.speichern()
        return text

    def _notizen_ablegen(self, notizen: list[dict]) -> None:
        """Jede Notiz zusätzlich als Markdown-Datei, zum Lesen am Laptop."""
        for alt in self.lokal.glob("notiz_*.md"):
            alt.unlink()
        for i, n in enumerate(notizen, 1):
            vorlage = NACH_KENNUNG[n["vorlage"]].name
            kopf = f"# {n['titel']}\n\n*{vorlage} · Aufnahme {self.a.id}*\n\n"
            ergebnis.lokal_schreiben(self.lokal / f"notiz_{i}_{n['vorlage']}.md", kopf + n["inhalt"].strip() + "\n")
        try:
            notizdateien.ablegen(self.a.id, self.a.modus, notizen, self.lokal)
        except (OSError, ValueError):  # der Ordner „Braininger Notizen“ ist nur eine Lesekopie
            log.exception("%s: Notizdateien ließen sich nicht schreiben", self.a.id)

    def ausfuehren(self) -> None:
        self.zustand.versuche += 1
        self.zustand.weiter_ab = None
        try:
            text = self._text_holen(self.a.fehlende_stuecke())

            self._fortschritt("auswertung", 0.0)
            dauer = self.daten.get("dauer_audio_s") or float(self.a.dauer_s or 0)
            antwort = auswertung.auswerten(text, self.a.modus, self.a.start, dauer, self.daten.get("hinweise", []))
            self._notizen_ablegen(antwort["notizen"])
            self.daten.update(
                status="fertig", schritt=None, fortschritt=1.0, meldung=None, weiter_ab=None,
                kurz=antwort["kurz"], notizen=antwort["notizen"], sprecher=antwort.get("sprecher", []),
            )
            self.zustand.status, self.zustand.meldung = "fertig", None
            log.info("%s: fertig, %d Notiz(en): %s", self.a.id, len(antwort["notizen"]),
                     ", ".join(n["vorlage"] for n in antwort["notizen"]))

        except ClaudeLimitErreicht as e:
            weiter = datetime.now().astimezone() + LIMIT_PAUSE
            self.zustand.versuche -= 1  # Limit ist kein Fehler der Aufnahme
            self._warten(f"Claude-Limit erreicht, neuer Versuch ab {weiter:%H:%M}.", weiter, str(e))
        except ClaudeFehler as e:
            self._fehler(f"Claude: {e}")
        except Exception as e:  # nichts darf das Programm beenden; Aufnahme bleibt für einen neuen Versuch
            log.exception("%s: unerwarteter Fehler", self.a.id)
            self._fehler(f"{type(e).__name__}: {e}")
        self.speichern()
        if not self.zustand.drive_aktuell:
            log.warning("%s: ergebnis.json im Drive nicht erreichbar (von der App noch nicht angelegt?)", self.a.id)

    def offener_auftrag(self) -> dict | None:
        """auftrag.json legt die App an (z. B. „Notiz 2 mit anderer Vorlage“). Erledigte Nummern merkt sich ergebnis.json."""
        try:
            j = json.loads((self.a.ordner / "auftrag.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        if int(j.get("nummer", 0)) <= int(self.daten.get("auftrag_erledigt", 0)):
            return None
        if self.zustand.weiter_ab and datetime.now().astimezone() < datetime.fromisoformat(self.zustand.weiter_ab):
            return None
        return j

    def auftrag_ausfuehren(self, auftrag: dict) -> None:
        nummer = int(auftrag.get("nummer", 0))
        meldung = None
        try:
            if auftrag.get("art") != "vorlage_aendern":
                raise ValueError(f"Unbekannter Auftrag {auftrag.get('art')!r}")
            index, vorlage = int(auftrag["notiz"]), str(auftrag["vorlage"])
            if vorlage not in NACH_KENNUNG or not 0 <= index < len(self.daten["notizen"]):
                raise ValueError("Auftrag passt nicht zu den Notizen.")
            text = self._gesicherter_text()
            if text is None:
                raise ValueError("Der Text dieser Aufnahme ist am Laptop nicht mehr da.")
            self.daten.update(status="in_arbeit", schritt="auswertung", fortschritt=0.0)
            self.speichern()
            log.info("%s: Auftrag %d, Notiz %d → Vorlage %s", self.a.id, nummer, index, vorlage)
            neu = auswertung.vorlage_aendern(text, self.a.modus, self.a.start, self.daten["notizen"][index], vorlage)
            self.daten["notizen"][index] = neu
            self._notizen_ablegen(self.daten["notizen"])
        except ClaudeLimitErreicht as e:
            weiter = datetime.now().astimezone() + LIMIT_PAUSE
            log.warning("%s: Claude-Limit beim Auftrag, neuer Versuch ab %s (%s)", self.a.id, f"{weiter:%H:%M}", e)
            self.zustand.weiter_ab = weiter.isoformat(timespec="seconds")
            self.daten.update(status="fertig", schritt=None, meldung=f"Claude-Limit erreicht, Vorlage wird ab {weiter:%H:%M} geändert.")
            self.speichern()
            return
        except Exception as e:  # Auftrag gilt als erledigt, damit er nicht endlos wiederholt wird
            log.exception("%s: Auftrag %d fehlgeschlagen", self.a.id, nummer)
            meldung = f"Vorlage ändern hat nicht geklappt: {e}"
        self.zustand.weiter_ab = None
        self.daten.update(status="fertig", schritt=None, fortschritt=1.0, meldung=meldung, auftrag_erledigt=nummer)
        self.speichern()

    def _warten(self, meldung: str, weiter: datetime, detail: str = "") -> None:
        log.warning("%s: %s %s", self.a.id, meldung, detail)
        self.zustand.status = "wartet"
        self.zustand.weiter_ab = weiter.isoformat(timespec="seconds")
        self.zustand.meldung = meldung
        self.daten.update(status="wartet", schritt=None, meldung=meldung, weiter_ab=self.zustand.weiter_ab)

    def _fehler(self, meldung: str) -> None:
        log.error("%s: Versuch %d/%d fehlgeschlagen: %s", self.a.id, self.zustand.versuche, MAX_VERSUCHE, meldung)
        if self.zustand.versuche < MAX_VERSUCHE:
            self._warten(f"Fehler, neuer Versuch folgt ({self.zustand.versuche}/{MAX_VERSUCHE}).",
                         datetime.now().astimezone() + FEHLER_PAUSE, meldung)
            return
        self.zustand.status, self.zustand.meldung = "fehler", meldung
        self.daten.update(status="fehler", schritt=None, meldung=meldung, weiter_ab=None)


def drive_nachtragen(aufnahme: Aufnahme) -> None:
    """Fertiges Ergebnis, das das Drive noch nicht erreicht hat (z. B. weil die App die Datei erst
    später angelegt hat), nachträglich hineinschreiben."""
    b = Bearbeitung(aufnahme)
    if b.zustand.status in ("fertig", "fehler") and not b.zustand.drive_aktuell and aufnahme.ergebnis_datei.exists():
        b.speichern()
        if b.zustand.drive_aktuell:
            log.info("%s: ergebnis.json im Drive nachgetragen", aufnahme.id)
