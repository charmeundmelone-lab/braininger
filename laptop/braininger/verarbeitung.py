"""Der Ablauf für eine Aufnahme: zusammenfügen → (Sprecher) → Text → Claude → ergebnis.json.

Der lokale Zustand (daten/aufnahmen/<id>/zustand.json) merkt sich, wie weit eine Aufnahme ist.
Ist der Text einmal gesichert, wird er bei einem neuen Versuch (z. B. nach einem Claude-Limit) nicht neu erkannt.
"""
import json
import re
import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

from . import aufgaben, auswertung, ergebnis, notizdateien, stimmen, woerterbuch
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
        return Text([Abschnitt(**x) for x in j["abschnitte"]], j["sprache"], j["sprache_sicherheit"], j["sprecher_anzahl"],
                    j.get("stimmen"))

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
            "stimmen": text.stimmen,
            "dauer_audio_s": round(audio.dauer_s, 1),
            "rechenzeit_s": round(dauer, 1),
        }, ensure_ascii=False, indent=1))
        ergebnis.lokal_schreiben(self.lokal / "text.txt", text.als_zeilen())
        if self.a.modus != "gespraech" and text.stimmen and text.stimmen.get("ich"):
            stimmen.lernen(auswertung.eigener_name(), text.stimmen["ich"])

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

    def _sprecher_namen(self, text: Text) -> dict[str, str]:
        """Namen je Kennung: vom Nutzer bestätigt, sonst an der Stimme erkannt. Füllt daten["sprecher_details"]."""
        if self.a.modus != "gespraech" or not text.stimmen:
            return {}
        alt = {d["kennung"]: d for d in self.daten.get("sprecher_details") or []}
        fest = {k: d["name"] for k, d in alt.items() if d.get("quelle") == "bestaetigt" and d.get("name")}
        ohne_name = {k for k, d in alt.items() if d.get("quelle") == "leer"}  # Nutzer hat den Namen entfernt
        erkannt = stimmen.zuordnen({k: v for k, v in text.stimmen.items() if k not in ohne_name}, fest)
        details = []
        for kennung in text.stimmen:
            teile = [a for a in text.abschnitte if a.sprecher == kennung and not a.pause]
            beispiel = max((a.text for a in teile), key=len, default="")
            eintrag = {"kennung": kennung, "name": None, "quelle": None, "aehnlichkeit": None,
                       "beispiel": beispiel[:120], "sprechzeit_s": round(sum(a.ende - a.start for a in teile))}
            if kennung in fest:
                eintrag.update(name=fest[kennung], quelle="bestaetigt")
            elif kennung in ohne_name:
                eintrag.update(quelle="leer")
            elif kennung in erkannt:
                eintrag.update(name=erkannt[kennung][0], quelle="stimme", aehnlichkeit=erkannt[kennung][1])
            details.append(eintrag)
        self.daten["sprecher_details"] = details
        self.daten["bekannte_namen"] = stimmen.namen()
        return {d["kennung"]: d["name"] for d in details if d["name"]}

    def _sprecher_melden(self, namen: dict[str, str], von_claude: list[dict]) -> None:
        """sprecher = nur benannte (so liest es auch die App bis 0.1.11), dazu Namen, die Claude aus dem Inhalt kennt."""
        details = {d["kennung"]: d for d in self.daten.get("sprecher_details") or []}
        for s in von_claude:
            k, n = s.get("kennung", ""), (s.get("name") or "").strip()
            if re.fullmatch(r"S\d+", k) and n and k not in namen and k in details:
                details[k].update(name=n, quelle="gespraech")
                namen[k] = n
        self.daten["sprecher"] = [{"kennung": k, "name": n} for k, n in sorted(namen.items())]

    def _fotos(self) -> tuple[list[Path], list[float]]:
        """Fotos, die im Drive angekommen sind, und ihre Zeitpunkte."""
        da = [(self.a.ordner / d, s) for d, s in self.a.fotos if (self.a.ordner / d).is_file()]
        return [p for p, _ in da], [s for _, s in da]

    def _notizen_ablegen(self, notizen: list[dict]) -> None:
        """Jede Notiz zusätzlich als Markdown-Datei, zum Lesen am Laptop."""
        for alt in self.lokal.glob("notiz_*.md"):
            alt.unlink()
        for i, n in enumerate(notizen, 1):
            vorlage = NACH_KENNUNG[n["vorlage"]].name
            kopf = f"# {n['titel']}\n\n*{vorlage} · Aufnahme {self.a.id}*\n\n"
            ergebnis.lokal_schreiben(self.lokal / f"notiz_{i}_{n['vorlage']}.md", kopf + n["inhalt"].strip() + "\n")
        try:
            notizdateien.ablegen(self.a.id, self.a.modus, notizen, self.lokal, foto_ordner=self.a.ordner)
        except (OSError, ValueError):  # der Ordner „Braininger Notizen“ ist nur eine Lesekopie
            log.exception("%s: Notizdateien ließen sich nicht schreiben", self.a.id)

    def ausfuehren(self) -> None:
        self.zustand.versuche += 1
        self.zustand.weiter_ab = None
        try:
            text = self._text_holen(self.a.fehlende_stuecke())

            self._fortschritt("auswertung", 0.0)
            dauer = self.daten.get("dauer_audio_s") or float(self.a.dauer_s or 0)
            namen = self._sprecher_namen(text)
            antwort = auswertung.auswerten(text, self.a.modus, self.a.start, dauer, self.daten.get("hinweise", []),
                                           namen, self.a.markierungen, *self._fotos(), aufgaben.offene())
            self._notizen_ablegen(antwort["notizen"])
            self._sprecher_melden(namen, antwort.get("sprecher", []))
            self.daten.update(
                status="fertig", schritt=None, fortschritt=1.0, meldung=None, weiter_ab=None,
                kurz=antwort["kurz"], notizen=antwort["notizen"], wichtig=antwort.get("wichtig", []),
                aufgaben=antwort["aufgaben"], vorlage_hinweis=antwort["vorlage_hinweis"],
            )
            self.zustand.status, self.zustand.meldung = "fertig", None
            log.info("%s: fertig, %d Notiz(en): %s, %d Aufgaben-Vorschläge", self.a.id, len(antwort["notizen"]),
                     ", ".join(n["vorlage"] for n in antwort["notizen"]), len(antwort["aufgaben"]))

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

    def _sprecher_benennen(self, text: Text, nummer: int, kennung: str, name: str) -> None:
        """Nutzer gibt einem Sprecher in der App einen Namen (leer = Name entfernen). Stimme merken, Notizen neu."""
        name = name.strip()[:60]
        details = self.daten.get("sprecher_details") or []
        eintrag = next((d for d in details if d["kennung"] == kennung), None)
        if eintrag is None:
            raise ValueError(f"Sprecher {kennung} gibt es in dieser Aufnahme nicht.")
        for d in details:  # ein Name gehört zu genau einem Sprecher dieser Aufnahme
            if name and d is not eintrag and (d.get("name") or "").casefold() == name.casefold():
                d.update(name=None, quelle=None, aehnlichkeit=None)
        eintrag.update(name=name or None, quelle="bestaetigt" if name else "leer", aehnlichkeit=None)
        if name and text.stimmen and text.stimmen.get(kennung):
            stimmen.lernen(name, text.stimmen[kennung])
        if name:
            woerterbuch.hinzufuegen(name)
        log.info("%s: Auftrag %d, %s heißt %s", self.a.id, nummer, kennung, name or "(kein Name)")

        self.daten.update(status="in_arbeit", schritt="auswertung", fortschritt=0.0)
        self.speichern()
        # bestätigte Namen bleiben, für die übrigen wird mit der neu gelernten Stimme noch einmal gesucht
        namen = self._sprecher_namen(text)
        dauer = self.daten.get("dauer_audio_s") or float(self.a.dauer_s or 0)
        antwort = auswertung.auswerten(text, self.a.modus, self.a.start, dauer, self.daten.get("hinweise", []), namen,
                                       self.a.markierungen, *self._fotos(), aufgaben.offene())
        self._notizen_ablegen(antwort["notizen"])
        self._sprecher_melden(namen, antwort.get("sprecher", []))
        self.daten.update(kurz=antwort["kurz"], notizen=antwort["notizen"], wichtig=antwort.get("wichtig", []))

    def auftrag_ausfuehren(self, auftrag: dict) -> None:
        nummer = int(auftrag.get("nummer", 0))
        meldung = None
        try:
            art = auftrag.get("art")
            text = self._gesicherter_text()
            if text is None:
                raise ValueError("Der Text dieser Aufnahme ist am Laptop nicht mehr da.")
            namen = {s["kennung"]: s["name"] for s in self.daten.get("sprecher") or [] if s.get("name")}
            if art == "vorlage_aendern":
                index, vorlage = int(auftrag["notiz"]), str(auftrag["vorlage"])
                if vorlage not in NACH_KENNUNG or not 0 <= index < len(self.daten["notizen"]):
                    raise ValueError("Auftrag passt nicht zu den Notizen.")
                self.daten.update(status="in_arbeit", schritt="auswertung", fortschritt=0.0)
                self.speichern()
                log.info("%s: Auftrag %d, Notiz %d → Vorlage %s", self.a.id, nummer, index, vorlage)
                neu = auswertung.vorlage_aendern(text, self.a.modus, self.a.start, self.daten["notizen"][index],
                                                 vorlage, namen)
                self.daten["notizen"][index] = neu
                self.daten["vorlage_hinweis"] = None  # umgestellt: Hinweis erledigt
                self._notizen_ablegen(self.daten["notizen"])
            elif art == "sprecher_benennen":
                self._sprecher_benennen(text, nummer, str(auftrag.get("kennung", "")), str(auftrag.get("name") or ""))
            elif art == "neu_auswerten":
                # Knopf „Neu auswerten“ in der App (Grill 20.10.2026): Claude am Laptop schreibt die Notizen neu,
                # mit dem vorhandenen Text (das Audio ist nach 3 Tagen ohnehin gelöscht)
                self.daten.update(status="in_arbeit", schritt="auswertung", fortschritt=0.0)
                self.speichern()
                log.info("%s: Auftrag %d, neu auswerten", self.a.id, nummer)
                dauer = self.daten.get("dauer_audio_s") or float(self.a.dauer_s or 0)
                antwort = auswertung.auswerten(text, self.a.modus, self.a.start, dauer, self.daten.get("hinweise", []),
                                               namen, self.a.markierungen, *self._fotos(), aufgaben.offene())
                self._notizen_ablegen(antwort["notizen"])
                self.daten.update(kurz=antwort["kurz"], notizen=antwort["notizen"], wichtig=antwort.get("wichtig", []),
                                  aufgaben=antwort["aufgaben"], vorlage_hinweis=antwort["vorlage_hinweis"],
                                  quelle="laptop")
            else:
                raise ValueError(f"Unbekannter Auftrag {art!r}")
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
