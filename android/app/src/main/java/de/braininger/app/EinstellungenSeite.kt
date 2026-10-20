package de.braininger.app

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.claude.Kosten
import de.braininger.app.claude.Schluessel
import de.braininger.app.erkennung.HandyAuswertung
import de.braininger.app.erkennung.Modelle
import de.braininger.app.ui.Bereiche
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun EinstellungenSeite(zurueck: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = zurueck)
    var stand by remember { mutableIntStateOf(0) }
    var schluesselEingabe by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Einstellungen.name(context)) }
    var beschreibung by remember { mutableStateOf(Einstellungen.beschreibung(context)) }
    var gespeichert by remember { mutableStateOf<String?>(null) }
    val kalenderAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        stand++
        if (it) Tagesrhythmus.planen(context)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            stand++ // Download-Fortschritt und Kosten aktuell halten
        }
    }
    val takt = stand // gelesen, damit die Seite alle 2 s neu zeichnet
    if (takt < 0) return

    val feld = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Weiss, unfocusedTextColor = Weiss, focusedBorderColor = Weiss, unfocusedBorderColor = Linie, cursorColor = Weiss,
    )
    Column(
        Modifier.fillMaxSize().background(Schwarz).safeDrawingPadding().padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Weiss, fontSize = 30.sp, modifier = Modifier.clickable(onClick = zurueck).padding(end = 12.dp))
            Text("EINSTELLUNGEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Weiss)
        }
        Spacer(Modifier.height(20.dp))

        // --- Claude ---
        Ueberschrift("CLAUDE AM HANDY")
        val anzeige = Schluessel.anzeige(context)
        Text(
            if (anzeige != null) "Schlüssel eingetragen ($anzeige). Neue Aufnahmen wertet das Handy selbst aus."
            else "Kein Schlüssel. Ohne Schlüssel wertet weiter der Laptop aus.",
            color = Grau, fontSize = 13.sp,
        )
        OutlinedTextField(
            value = schluesselEingabe, onValueChange = { schluesselEingabe = it.trim().take(300) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            placeholder = { Text("API-Schlüssel einfügen (sk-ant-…)", color = Grau, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = feld,
        )
        Row(Modifier.padding(top = 6.dp)) {
            KleinerKnopf("SPEICHERN", hell = schluesselEingabe.startsWith("sk-ant-")) {
                if (schluesselEingabe.startsWith("sk-ant-")) {
                    Schluessel.speichern(context, schluesselEingabe)
                    schluesselEingabe = ""
                    gespeichert = "Schlüssel gespeichert."
                    Modelle.laden(context) // Spracherkennung schon mal herunterladen (nur WLAN)
                    stand++
                } else gespeichert = "Das sieht nicht wie ein Claude-Schlüssel aus (beginnt mit sk-ant-)."
            }
            if (anzeige != null) {
                Spacer(Modifier.padding(4.dp))
                KleinerKnopf("ENTFERNEN", hell = false) { Schluessel.loeschen(context); gespeichert = "Schlüssel entfernt."; stand++ }
            }
        }
        gespeichert?.let { Text(it, color = Grau, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Text(
            String.format(Locale.GERMANY, "Diesen Monat: %.2f \$ von %.2f \$", Kosten.diesenMonat(context), Kosten.DECKEL_DOLLAR),
            color = Weiss, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(24.dp))

        // --- Spracherkennung ---
        Ueberschrift("SPRACHERKENNUNG AM HANDY")
        if (Modelle.bereit(context)) {
            Text("Bereit (Whisper large-v3-turbo). Erkennt sofort nach der Aufnahme, unter ${HandyAuswertung.AKKU_GRENZE} % Akku erst beim Laden.",
                 color = Grau, fontSize = 13.sp)
        } else {
            val geladen = Modelle.geladen(context)
            val prozent = (geladen * 100 / Modelle.gesamt).toInt()
            Text(
                if (geladen > 0) "Wird geladen: $prozent % von 875 MB (nur im WLAN)."
                else "Noch nicht geladen. Braucht einmal 875 MB, nur im WLAN.",
                color = Grau, fontSize = 13.sp,
            )
            Row(Modifier.padding(top = 6.dp)) { KleinerKnopf("JETZT LADEN") { Modelle.laden(context); stand++ } }
        }
        Spacer(Modifier.height(24.dp))

        // --- Persönliches ---
        Ueberschrift("FÜR DIE NOTIZEN")
        Text("So nennt Claude dich in den Notizen. Bleibt nur auf dem Handy.", color = Grau, fontSize = 13.sp)
        OutlinedTextField(name, { name = it.take(40) }, singleLine = true, colors = feld,
            placeholder = { Text("Vorname", color = Grau) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(beschreibung, { beschreibung = it.take(160) }, colors = feld,
            placeholder = { Text("z. B. Musiker und Familienvater", color = Grau) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.padding(top = 6.dp)) {
            KleinerKnopf("ÜBERNEHMEN") { Einstellungen.speichern(context, name, beschreibung); gespeichert = "Angaben gespeichert." }
        }
        Spacer(Modifier.height(24.dp))

        // --- Kalender ---
        Ueberschrift("KALENDER")
        if (Kalender.erlaubt(context)) {
            Text("Braininger liest deine Kalender am Handy (nur lesen) und erinnert 1,5 Stunden vor Terminen.",
                 color = Grau, fontSize = 13.sp)
        } else {
            Text("Damit Termine im Überblick stehen, darf Braininger deine Kalender lesen.", color = Grau, fontSize = 13.sp)
            Row(Modifier.padding(top = 6.dp)) { KleinerKnopf("ERLAUBEN") { kalenderAnfrage.launch(Manifest.permission.READ_CALENDAR) } }
        }
        Spacer(Modifier.height(24.dp))

        // --- Farben ---
        Ueberschrift("FARBEN DER LEBENSBEREICHE")
        Bereiche.alle.forEach { b ->
            Text(b.name, color = Weiss, fontSize = 14.sp,
                 modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                     .background(b.flaeche, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", color = Grau, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Ueberschrift(text: String) {
    Text(text, fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Grau,
         modifier = Modifier.padding(bottom = 4.dp))
}
