# Datenschutzerklärung für Braininger

Stand: 14. Oktober 2026

Braininger ist ein privates, nicht kommerzielles Projekt. Die App wird nicht im Play Store angeboten und ist für den eigenen Gebrauch des Entwicklers gebaut. Diese Erklärung beschreibt, was mit den Daten passiert.

## Welche Daten die App verarbeitet
- **Tonaufnahmen**, die du selbst mit der App startest („Gedanke“ oder „Gespräch“).
- **Angaben zur Aufnahme**: Zeitpunkt, Dauer, gewähltes Mikrofon, Art der Aufnahme.
- **Dein Google-Konto**, nur soweit es für die Anmeldung bei Google Drive nötig ist.

## Zugriff auf Google-Daten
Braininger fordert bei Google nur die Berechtigung `drive.file` an. Damit sieht die App ausschließlich Dateien, die sie selbst in deinem Google Drive angelegt hat oder die du ihr ausdrücklich öffnest. Auf andere Dateien in deinem Drive, auf Gmail, Kontakte oder Kalender greift die App nicht zu.

Die App nutzt diesen Zugriff, um
- Aufnahmen in den Ordner „Braininger“ in deinem Google Drive hochzuladen,
- dort abgelegte Ergebnisse (Texte und Notizen zu deinen Aufnahmen) anzuzeigen,
- hochgeladene Aufnahmen nach der Auswertung wieder zu löschen (spätestens nach 3 Tagen, nie bevor der Text fertig ist).

Die Nutzung von Daten, die über Google-APIs empfangen werden, hält sich an die [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), einschließlich der Anforderungen zur eingeschränkten Nutzung (Limited Use).

## Wo die Daten verarbeitet werden
- **Auf dem Handy**: Aufnahmen liegen im App-eigenen Speicher, bis sie hochgeladen sind.
- **In deinem Google Drive**: Zwischenablage zwischen Handy und Laptop.
- **Auf deinem eigenen Windows-Rechner**: Die Texterkennung (Whisper) und die Sprechertrennung laufen lokal, ohne fremden Server.
- **Bei Anthropic (Claude)**: Der erkannte Text, nicht die Tonaufnahme, wird zur Zusammenfassung an Claude geschickt, über das Konto des Nutzers.

Es gibt keinen eigenen Server von Braininger. Daten werden nicht verkauft, nicht für Werbung genutzt und an niemanden sonst weitergegeben.

## Löschen
Hochgeladene Aufnahmen werden automatisch gelöscht, sobald der Text fertig ist, spätestens nach 3 Tagen. Wer die App deinstalliert, löscht damit alle Aufnahmen auf dem Handy. Den Google-Zugriff kannst du jederzeit unter [myaccount.google.com/permissions](https://myaccount.google.com/permissions) widerrufen.

## Aufnahmen von Gesprächen
Bei „Gespräch“ fragt die App vor jeder Aufnahme, ob alle Anwesenden einverstanden sind. Für die Einholung dieser Zustimmung ist die Person verantwortlich, die aufnimmt.

## Kontakt
Fragen bitte als Issue im [GitHub-Projekt](https://github.com/charmeundmelone-lab/braininger/issues).
