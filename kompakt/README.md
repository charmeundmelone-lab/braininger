# Braininger Lite (Mudita Kompakt)

Kleine Schwester der Braininger-App für das Mudita Kompakt (E-Ink 480 × 800, Helio A22, 3 GB RAM, Android 12,
keine Google-Dienste). Zweig `kompakt-lite`, gebaut von `.github/workflows/kompakt.yml` (Artefakt `braininger-lite-apk`).

## Was sie macht
- Optik wie Muditas eigene Apps (Books, CalmCast): Lato, nur reines Schwarz, dicke Linien, gepunktete Trenner.
- Kein Scrollen: Startseite passt immer ganz auf den Bildschirm (Knopf, Aufgaben, Einkauf, Aufnahmen, Laptop-Zeile).
  Alles andere ist in Seiten geteilt, Blättern mit Pfeilen unten oder Lautstärketasten (kurz drücken). Passt eine
  Liste auf eine Seite, fällt die Blätterleiste weg. Eine Aufnahme liest sich wie ein Buch (StaticLayout mit deutscher
  Silbentrennung, an Zeilengrenzen in Seiten geschnitten).
- Aufgaben: je eine Zeile, gruppiert (Überfällig, Heute, Morgen, Später, Ohne Termin, Erledigt). Antippen klappt eine
  Aufgabe auf: ganzer Text, Frist, Bezug, Sprung in die Aufnahme. Kästchen hakt ab (nur lokal gespeichert).
- Einkauf nach Abteilungen im Laden, die ganze Zeile hakt ab, „Abgehakte entfernen“.
- Aufnehmen: großer Knopf oder **Lauter-Taste 1 Sekunde halten** (auch bei dunklem Bildschirm, auch in anderen Apps,
  die die Taste nicht selbst nutzen). Start = einmal lang vibrieren, Stopp = zweimal kurz, stummes Mikrofon = dreimal lang.
- Warnt, sobald das Mikrofon 2 s lang nur Stille liefert, auch mitten in der Aufnahme: Offline+ (trennt die Mikrofone
  per Hardware, ≥ 90 % der Werte praktisch 0) oder Android-Sperre (`AudioRecordingConfiguration.isClientSilenced`).
- Schickt fertige Aufnahmen an den Laptop und holt die Ergebnisse ab: Kurzfassung, Notizen, Aufgaben mit Frist, Einkauf.

## Aufnahme bei dunklem Bildschirm (gelöst am 8.11.2026)
Mudita stellt nachinstallierte Apps auf „Akku eingeschränkt“ (App-Op `RUN_ANY_IN_BACKGROUND = ignore`, gilt für fast
alle Apps auf Jans Kompakt). Dann schaltete Android das Mikrofon etwa 7 s nach dem Ausschalten des Bildschirms stumm und
stoppte den Dienst nach einer Minute (`am_stop_idle_service … AufnahmeDienst`). Abhilfe:
- Der Laptop hebt die Einschränkung beim Anstecken auf (`kompakt.akku_sparen_aufheben()`: App-Op auf `allow`, dazu
  `deviceidle whitelist`; entspricht „Akku: Uneingeschränkt“). Nach einer Neuinstallation also einmal Kabel anstecken.
- Die Startseite warnt, solange die Einschränkung aktiv ist (`ActivityManager.isBackgroundRestricted`).
- Getestet: 109 s Aufnahme bei dunklem Bildschirm, `silenced:false`, kein Abbruch.

## Lauter-Taste
`AufnahmeDienst` läuft dauerhaft als Vordergrund-Dienst (Typ Mikrofon, Hinweis „Braininger ist bereit“) und hält eine
Mediensitzung mit eigener Lautstärke (`setPlaybackToRemote`, `VolumeProvider`). Lautstärketasten, die keine App selbst
verbraucht, landen dort, bei dunklem Bildschirm ebenso. +1 = Lauter gedrückt, 0 = losgelassen, −1 = Leiser.
Halten ≥ 900 ms schaltet die Aufnahme, kurze Drücke gehen als normale Klingelton-Lautstärke weiter (bei dunklem
Bildschirm gar nichts). Spielt eine andere App Ton ab oder läuft ein Anruf, ist die Sitzung aus und die Tasten gehören
wieder dem System. In der App selbst übernimmt `LiteActivity.onKeyDown/onKeyUp` dasselbe (kurz = blättern).
Getestet per virtueller HID-Taste (`hid`-Werkzeug, Verbraucher-Tasten 0xE9/0xEA) bei dunklem und hellem Bildschirm.
Die echte Taste muss Jan einmal selbst probieren.

Nach Neustart (`BOOT_COMPLETED`) oder Update (`MY_PACKAGE_REPLACED`) startet der Dienst im Hintergrund und hat zunächst
kein Mikrofon-Recht (`allowWhileInUsePermissionInFgs=false`). Beim Tastendruck ruft `beginnen()` erneut `startForeground()`
auf, Android prüft dann neu, und die Mediensitzung gibt der App in dem Moment Vordergrund-Rechte: gemessen am 8.11.2026
false → true, Aufnahme nicht stumm. Sollte es doch einmal stumm sein, warnt die App mit dreimal Vibrieren.

Apps, die die Lautstärketasten selbst nutzen (Books, Jans Blatt-/Claude-App), behalten sie: dort geht die Taste nicht,
wohl aber bei dunklem Bildschirm, auf dem Startbildschirm und in Braininger selbst.

## Warum der Laptop auswertet
Whisper large-v3-turbo braucht auf dem Nothing Phone (Snapdragon) schon 0,75–0,9 × Aufnahmelänge. Der Helio A22 hat nur
vier langsame A53-Kerne: eine Stunde Aufnahme würde mehrere Stunden rechnen und viel Akku kosten. Google Drive geht ohne
Google-Dienste nicht wie in der großen App. Deshalb: Kompakt nimmt nur auf, der Laptop macht alles wie in Stufe 1.

## Weg zum Laptop
Per USB-Kabel. Der Empfang läuft im Laptop-Programm mit (`laptop/braininger/kompakt.py`, nur 127.0.0.1:8765, Schlüssel im
Windows-Tresor „Braininger/kompakt_schluessel“). Die Kabelwache öffnet `adb reverse`, sobald der Kompakt angesteckt ist,
hebt bei Bedarf die Akku-Einschränkung auf und stößt den Abgleich in der App an (gleich und dann jede Minute). Die App
gleicht zusätzlich beim Laden alle 15 Minuten ab. Aufnahmen landen im Drive-Eingang ohne `"auswertung": "handy"`, das
normale Laptop-Programm wertet sie ganz aus.
Einmalig nach Neuinstallation der App: `python kompakt_empfang.py einrichten` (gibt den Schlüssel per adb).
WLAN ist bewusst aus: das Öffnen eines Ports im Netz hat der Auto-Mode blockiert, Jan müsste es selbst freigeben.

## Prüfen per adb
- Protokoll: `adb shell cat /sdcard/Android/data/de.braininger.lite/files/lite.log`
- Laptop-Seite: `Braininger\daten\protokoll.txt` (Zeilen mit „Kompakt“)
- Akku-Einschränkung: `adb shell cmd appops get de.braininger.lite RUN_ANY_IN_BACKGROUND` (soll `allow` sein)
- Taste ohne Finger: JSON für `hid` mit Verbraucher-Deskriptor nach `/data/local/tmp`, dann `adb shell hid datei.json`

## Offen
- Echte Lauter-Taste von Jan probieren lassen, Offline+-Warnung mit echtem Schalter, Akku über einen Tag mit Dienst.
- WLAN-Weg (braucht Jans Firewall-Klick), Ansteckmikrofon am USB-C.
- Die alte Bedienungshilfe „Braininger Lite: Tasten“ (Headset-Taste) ist noch drin, aber unnötig, solange Jan kein
  Mikro mit Knopf hat.
