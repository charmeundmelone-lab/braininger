# Braininger Lite (Mudita Kompakt)

Kleine Schwester der Braininger-App für das Mudita Kompakt (E-Ink 480 × 800, Helio A22, 3 GB RAM, Android 12,
keine Google-Dienste). Zweig `kompakt-lite`, gebaut von `.github/workflows/kompakt.yml` (Artefakt `braininger-lite-apk`).

## Was sie macht
- Optik wie Muditas eigene Apps (Books, CalmCast): Lato, nur reines Schwarz, dicke Linien, gepunktete Trenner.
- Kein Scrollen: Startseite passt immer ganz auf den Bildschirm (Knopf, Aufgaben, Einkauf, Aufnahmen, Laptop-Zeile).
  Alles andere ist in Seiten geteilt, Blättern mit Pfeilen unten oder Lautstärketasten. Eine Aufnahme liest sich wie
  ein Buch (StaticLayout mit deutscher Silbentrennung, an Zeilengrenzen in Seiten geschnitten).
- Aufgaben und Einkauf sammeln sich aus allen Ergebnissen, abhaken am Kompakt (nur lokal gespeichert).
- Großer Knopf Aufnehmen / Stoppen, Aufnahme läuft als Vordergrund-Dienst weiter (gleicher Stück-Recorder wie die große App).
- Taste am Headset/Ansteckmikrofon (HEADSETHOOK, PLAY/PAUSE) über die Bedienungshilfe „Braininger Lite: Tasten“:
  doppelt = Start/Stopp, lang = Funkgerät. Jede Taste landet im Protokoll (zum Herausfinden, was der Kompakt meldet).
- Warnt, sobald das Mikrofon 2 s lang nur Stille liefert, auch mitten in der Aufnahme (Offline+ trennt die Mikrofone
  per Hardware; ≥ 90 % der Werte praktisch 0, echte Aufnahmen haben höchstens ~7 %).
- Schickt fertige Aufnahmen an den Laptop und holt die Ergebnisse ab: Kurzfassung, Notizen zum Aufklappen,
  Aufgaben mit Frist, Einkauf. Nur lesen, kein Abhaken (noch).

## Warum der Laptop auswertet
Whisper large-v3-turbo braucht auf dem Nothing Phone (Snapdragon) schon 0,75–0,9 × Aufnahmelänge. Der Helio A22 hat nur
vier langsame A53-Kerne: eine Stunde Aufnahme würde mehrere Stunden rechnen und viel Akku kosten. Google Drive geht ohne
Google-Dienste nicht wie in der großen App. Deshalb: Kompakt nimmt nur auf, der Laptop macht alles wie in Stufe 1.

## Weg zum Laptop
Per USB-Kabel. Der Empfang läuft im Laptop-Programm mit (`laptop/braininger/kompakt.py`, nur 127.0.0.1:8765, Schlüssel im
Windows-Tresor „Braininger/kompakt_schluessel“). Die Kabelwache öffnet `adb reverse`, sobald der Kompakt angesteckt ist,
und stößt den Abgleich in der App an (gleich und dann jede Minute). Die App gleicht zusätzlich beim Laden alle 15 Minuten ab.
Aufnahmen landen im Drive-Eingang ohne `"auswertung": "handy"`, das normale Laptop-Programm wertet sie ganz aus.
Einmalig nach Neuinstallation der App: `python kompakt_empfang.py einrichten` (gibt den Schlüssel per adb).
WLAN ist bewusst aus: das Öffnen eines Ports im Netz hat der Auto-Mode blockiert, Jan müsste es selbst freigeben.

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
