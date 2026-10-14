"""Legt den Signierschlüssel der Android-App an und übergibt ihn verschlüsselt an GitHub.

- Schlüsseldatei: %USERPROFILE%\\Braininger-Signierschluessel\\braininger.jks (nie im Projekt)
- Passwort: zufällig erzeugt, nur in der Windows-Anmeldeinformationsverwaltung
  (Dienst „Braininger“, Name „android_keystore_passwort“)
- GitHub: Secrets BRAININGER_KEYSTORE_BASE64, _KEYSTORE_PASSWORT, _KEY_ALIAS, _KEY_PASSWORT
- Ausgabe: nur der SHA-1-Fingerabdruck (nicht geheim, braucht Google für die Anmeldung)

Aufruf:  python signierschluessel.py anlegen   (einmalig)
         python signierschluessel.py github    (Secrets erneut hochladen)
         python signierschluessel.py sha1
"""
import base64
import os
import re
import secrets
import subprocess
import sys
from pathlib import Path

import keyring

DIENST = "Braininger"
NAME = "android_keystore_passwort"
ALIAS = "braininger"
ORDNER = Path.home() / "Braininger-Signierschluessel"
DATEI = ORDNER / "braininger.jks"
KEYTOOL = Path(r"C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe")
GH = Path.home() / ".local" / "bin" / "gh.exe"
REPO = "charmeundmelone-lab/braininger"


def keytool(*args: str, passwort: str) -> str:
    env = os.environ.copy()
    env["BRAININGER_KS_PW"] = passwort
    r = subprocess.run([str(KEYTOOL), *args], env=env, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"keytool-Fehler: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def anlegen() -> None:
    if DATEI.exists():
        sys.exit(f"Schlüssel existiert schon: {DATEI} – wird nicht überschrieben.")
    ORDNER.mkdir(exist_ok=True)
    passwort = secrets.token_urlsafe(24)
    keyring.set_password(DIENST, NAME, passwort)
    keytool(
        "-genkeypair", "-keystore", str(DATEI), "-storetype", "PKCS12",
        "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
        "-dname", "CN=Braininger", "-storepass:env", "BRAININGER_KS_PW",
        passwort=passwort,
    )
    print(f"Schlüssel angelegt: {DATEI}")


def github() -> None:
    passwort = keyring.get_password(DIENST, NAME)
    if not passwort or not DATEI.exists():
        sys.exit("Schlüssel oder Passwort fehlt. Erst 'anlegen' ausführen.")
    werte = {
        "BRAININGER_KEYSTORE_BASE64": base64.b64encode(DATEI.read_bytes()).decode(),
        "BRAININGER_KEYSTORE_PASSWORT": passwort,
        "BRAININGER_KEY_ALIAS": ALIAS,
        "BRAININGER_KEY_PASSWORT": passwort,  # bei PKCS12 identisch mit dem Speicher-Passwort
    }
    for name, wert in werte.items():
        r = subprocess.run([str(GH), "secret", "set", name, "--repo", REPO], input=wert, text=True, capture_output=True)
        if r.returncode != 0:
            sys.exit(f"GitHub-Secret {name} fehlgeschlagen: {r.stderr.strip()}")
        print(f"GitHub-Secret gesetzt: {name}")


def sha1() -> None:
    passwort = keyring.get_password(DIENST, NAME)
    ausgabe = keytool("-list", "-v", "-keystore", str(DATEI), "-alias", ALIAS,
                      "-storepass:env", "BRAININGER_KS_PW", passwort=passwort)
    for zeile in ausgabe.splitlines():
        if re.search(r"SHA1:", zeile):
            print(zeile.strip())


if __name__ == "__main__":
    befehl = sys.argv[1] if len(sys.argv) > 1 else ""
    {"anlegen": anlegen, "github": github, "sha1": sha1}.get(befehl, lambda: sys.exit(__doc__))()
