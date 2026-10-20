# Übergabe: Braininger darf die Essential Key selbst wieder einschalten

Diese Aufgabe stammt aus einer Cloud-Sitzung, die das Handy nicht erreichen konnte. Erledige sie hier am Laptop (Windows) möglichst selbstständig. Jan muss nur die Zahlen vom Handy ablesen.

## Hintergrund
- Android schaltet die Bedienungshilfe „Braininger: Essential Key“ manchmal still ab, zum Beispiel nach einem App-Update. Dann startet und stoppt die Essential Key keine Aufnahme mehr.
- Ab App-Version 0.1.35 gibt es die `TastenWache` (`android/app/src/main/java/de/braininger/app/TastenWache.kt`). Hat die App die Erlaubnis `WRITE_SECURE_SETTINGS`, schaltet sie die Bedienungshilfe selbst wieder ein.
- Diese Erlaubnis kann nur einmalig per ADB erteilt werden. Danach bleibt sie auch bei App-Updates erhalten, nur nicht bei einer Neuinstallation.

## Voraussetzungen (Jan vorher fragen)
1. Ist Braininger **0.1.35 oder neuer** auf dem Handy? (In der App oder unter Einstellungen → Apps → Braininger.) Falls nicht: Die APK gibt es bei https://github.com/charmeundmelone-lab/braininger/actions/runs/39322314367 unter „Artifacts“ → `braininger-apk`. Ohne diese Version schlägt `pm grant` fehl, weil die alte App die Erlaubnis nicht anmeldet.
2. Handy und Laptop sind im selben WLAN, und am Handy ist „Kabelloses Debugging“ an.

## Schritte
1. Prüfen, ob `adb` vorhanden ist (`adb version`). Falls nicht: `winget install Google.PlatformTools` und danach ein neues Terminal öffnen, oder die Platform-Tools von https://developer.android.com/tools/releases/platform-tools entpacken und `adb.exe` aus diesem Ordner aufrufen.
2. Jan bitten: Am Handy unter Entwickleroptionen → Kabelloses Debugging → **„Gerät über einen Kopplungscode koppeln“** öffnen. Er soll die **IP:Port** und den **sechsstelligen Code** nennen und das Fenster offen lassen.
3. `adb pair <IP:Kopplungs-Port> <Code>`
4. Jan bitten: Auf der Hauptseite „Kabelloses Debugging“ die **IP-Adresse und den Port** nennen (das ist ein anderer Port als beim Koppeln).
5. `adb connect <IP:Port>`, danach mit `adb devices` prüfen, dass das Handy als `device` erscheint.
6. `adb shell pm grant de.braininger.app android.permission.WRITE_SECURE_SETTINGS`
7. Prüfen: `adb shell dumpsys package de.braininger.app | findstr WRITE_SECURE_SETTINGS`. Dort muss `granted=true` stehen.
8. Test zusammen mit Jan: Bedienungshilfe am Handy ausschalten, dann Braininger öffnen. Sie sollte sofort wieder an sein, und die Essential Key funktioniert (doppelt drücken startet und stoppt).
   Gegenprobe per ADB: `adb shell settings get secure enabled_accessibility_services` muss `de.braininger.app/de.braininger.app.TastenDienst` enthalten.
9. Zum Schluss `adb disconnect`. Jan sagen, dass er das kabellose Debugging wieder ausschalten kann, die Erlaubnis bleibt bestehen.

## Wenn etwas schiefgeht
- `pm grant` meldet „has not requested permission“: Auf dem Handy läuft noch eine alte Version. Erst 0.1.35 installieren.
- Kopplung schlägt fehl: Der Code läuft nach kurzer Zeit ab. Kopplungsfenster neu öffnen und mit dem neuen Code wiederholen.
- `connect` schlägt fehl: Der Port auf der Hauptseite ändert sich, wenn kabelloses Debugging neu gestartet wird. Den aktuellen Port nennen lassen.

Wenn alles geklappt hat, diese Datei löschen und den Commit pushen.
