"""Speichert den Claude-Schlüssel aus `claude setup-token` sicher in der Windows-Anmeldeinformationsverwaltung."""
import getpass

import keyring

from braininger.claude_aufruf import KEYRING_DIENST, KEYRING_NAME, ClaudeFehler, frage

print("Füge jetzt den Schlüssel ein (Rechtsklick oder Strg+V). Man sieht beim Einfügen nichts, das ist Absicht.")
schluessel = getpass.getpass("Schlüssel: ").strip()
if len(schluessel) < 20 or " " in schluessel:
    print("Das sieht nicht wie ein Claude-Schlüssel aus. Nichts gespeichert. Bitte nochmal kopieren.")
    raise SystemExit(1)

keyring.set_password(KEYRING_DIENST, KEYRING_NAME, schluessel)
print("Gespeichert. Ich teste die Verbindung ...")
try:
    antwort = frage("Antworte nur mit: Braininger ist verbunden.")
    print("Claude sagt:", antwort.text)
    print("Alles fertig. Du kannst dieses Fenster schließen.")
except ClaudeFehler as fehler:
    print("Test fehlgeschlagen:", fehler)
    raise SystemExit(1)
