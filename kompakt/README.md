# Braininger Lite (Mudita Kompakt)

Kleine Schwester der Braininger-App für das Mudita Kompakt (E-Ink 480 × 800, Helio A22, 3 GB RAM, Android 12,
keine Google-Dienste). Zweig `kompakt-lite`, gebaut von `.github/workflows/kompakt.yml` (Artefakt `braininger-lite-apk`).

## Was sie macht
- Großer Knopf AUFNEHMEN / STOPPEN, Aufnahme läuft als Vordergrund-Dienst weiter (gleicher Stück-Recorder wie die große App).
- Taste am Headset/Ansteckmikrofon (HEADSETHOOK, PLAY/PAUSE) über die Bedienungshilfe „Braininger Lite: Tasten“:
  doppelt = Start/Stopp, lang = Funkgerät. Jede Taste landet im Protokoll (zum Herausfinden, was der Kompakt meldet).
- Warnt nach 3 s, wenn das Mikrofon nur Stille liefert (Offline+-Schalter trennt die Mikrofone per Hardware).
- Schickt fertige Aufnahmen an den Laptop und holt die Ergebnisse ab: Kurzfassung, Notizen zum Aufklappen,
  Aufgaben mit Frist, Einkauf. Nur lesen, kein Abhaken (noch).

## Warum der Laptop auswertet
Whisper large-v3-turbo braucht auf dem Nothing Phone (Snapdragon) schon 0,75–0,9 × Aufnahmelänge. Der Helio A22 hat nur
vier langsame A53-Kerne: eine Stunde Aufnahme würde mehrere Stunden rechnen und viel Akku kosten. Google Drive geht ohne
Google-Dienste nicht wie in der großen App. Deshalb: Kompakt nimmt nur auf, der Laptop macht alles wie in Stufe 1.

## Weg zum Laptop
`laptop/kompakt_empfang.py` nimmt Aufnahmen an (Port 8765, Schlüssel im Windows-Tresor „Braininger/kompakt_schluessel“)
und legt sie in den Drive-Eingang ohne `"auswertung": "handy"`. Das normale Laptop-Programm wertet sie dann ganz aus.
- USB-Kabel: `adb reverse tcp:8765 tcp:8765` (macht `kompakt_empfang.py einrichten`), App nutzt 127.0.0.1:8765.
- WLAN: Empfang mit `--wlan` starten. Windows fragt einmal nach der Firewall, Jan klickt selbst. Die Laptop-Adresse
  bekommt die App bei `einrichten` per adb (aktuell 192.168.2.30). Ändert der Router die Adresse, `einrichten` erneut.
- Autostart: `7_Kompakt_Empfang_einrichten.bat` im Braininger-Ordner (Jan startet sie selbst).

## Prüfen per adb
- Protokoll: `adb shell cat /sdcard/Android/data/de.braininger.lite/files/lite.log`
- Laptop-Seite: `Braininger\daten\kompakt_empfang.log`

## Getestet am 6.11.2026
Aufnahme (eingebautes Mikro) → per USB zum Laptop → Whisper + Claude am Laptop → Ergebnis am Kompakt mit Aufgabe
„Koffer reparieren, Sa. 7.11.“ und Einkauf „Hafermilch“. Stumm-Erkennung bei Offline+ bestätigt (Pegel 0 statt ~6000).

## Offen
- WLAN-Weg (braucht Jans Firewall-Klick), Taste am Headset (Bedienungshilfe einschalten, Mikro mit Knopf nötig),
  Aufnahme bei gesperrtem Bildschirm, Ansteckmikrofon am USB-C, Akku über einen Tag.
- Aufgaben abhaken, Einkaufsliste, Kalender: bewusst noch nicht.
