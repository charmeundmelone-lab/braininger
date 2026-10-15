package de.braininger.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.braininger.app.ui.BrainingerThema
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Rot
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import de.braininger.app.drive.AnmeldungNoetig
import de.braininger.app.drive.Auftraege
import de.braininger.app.drive.AudioAufraeumen
import de.braininger.app.drive.Ergebnis
import de.braininger.app.drive.ErgebnisAbruf
import de.braininger.app.drive.ErgebnisAuftrag
import androidx.compose.foundation.clickable
import de.braininger.app.drive.GoogleKonto
import de.braininger.app.drive.HochladenAuftrag
import de.braininger.app.drive.UploadStand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var aktualisierung by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AufnahmeDienst.kanaeleAnlegen(this)
        setContent { BrainingerThema { Startseite(aktualisierung) } }
    }

    override fun onResume() {
        super.onResume()
        Ablage.unterbrocheneAbschliessen(this)
        BrainingerWidget.aktualisieren(this)
        HochladenAuftrag.anstossen(this)
        ErgebnisAuftrag.planen(this)
        aktualisierung++
    }
}

private fun dauerText(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
}

@Composable
private fun Startseite(aktualisierung: Int) {
    val context = LocalContext.current
    val zustand by AufnahmeStatus.zustand.collectAsStateWithLifecycle()

    var mikroErlaubt by remember { mutableStateOf(false) }
    var hinweiseErlaubt by remember { mutableStateOf(false) }
    var akkuFrei by remember { mutableStateOf(false) }
    var fragZustimmung by remember { mutableStateOf(false) }
    var aufnahmen by remember { mutableStateOf(emptyList<Aufnahme>()) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var googleFehlt by remember { mutableStateOf(false) }
    var uploads by remember { mutableStateOf(emptyMap<String, UploadStand>()) }
    var testText by remember { mutableStateOf<String?>(null) }
    var ergebnisse by remember { mutableStateOf(emptyMap<String, Ergebnis?>()) }
    var offen by remember { mutableStateOf<String?>(null) }
    var suche by remember { mutableStateOf("") }
    var zeigeUebersicht by remember { mutableStateOf(false) }
    var zeigeFragen by remember { mutableStateOf(false) }
    var vonFragen by remember { mutableStateOf(false) }
    var abrufZaehler by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun pruefen() {
        mikroErlaubt = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hinweiseErlaubt = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        akkuFrei = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        aufnahmen = Ablage.alle(context)
        uploads = aufnahmen.associate { it.ordner.name to UploadStand.lesen(it.ordner) }
        ergebnisse = aufnahmen.associate { it.ordner.name to Ergebnis.lesen(it.ordner) }
        googleFehlt = HochladenAuftrag.anmeldungFehlt(context)
    }

    fun angemeldet() {
        HochladenAuftrag.anmeldungFehltSetzen(context, false)
        HochladenAuftrag.anstossen(context)
        pruefen()
    }

    val googleFenster = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { ergebnis ->
        try {
            GoogleKonto.auswerten(GoogleKonto.ergebnisAusIntent(context, ergebnis.data))
            angemeldet()
        } catch (e: Exception) {
            testText = "Google-Anmeldung nicht abgeschlossen: ${e.message}"
        }
    }

    fun anmelden() {
        scope.launch {
            try {
                GoogleKonto.zugang(context)
                angemeldet()
            } catch (e: AnmeldungNoetig) {
                googleFenster.launch(IntentSenderRequest.Builder(e.pendingIntent.intentSender).build())
            } catch (e: Exception) {
                testText = "Google-Anmeldung fehlgeschlagen: ${e.message}"
            }
        }
    }

    val erlaubnisAnfrage = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { pruefen() }

    LaunchedEffect(aktualisierung, zustand.laeuft, zustand.stueckeFertig) { pruefen() }
    LaunchedEffect(Unit) {
        val fehlend = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (fehlend.isNotEmpty()) erlaubnisAnfrage.launch(fehlend.toTypedArray())
    }
    fun ergebnisseHolen() {
        scope.launch {
            try {
                ErgebnisAbruf.abrufen(context)
                AudioAufraeumen.aufraeumen(context)
                de.braininger.app.drive.GoogleDocs.aktualisieren(context)
            } catch (_: Exception) {
                // kein Netz oder nicht angemeldet: beim nächsten Mal wieder
            }
            pruefen()
        }
    }

    LaunchedEffect(Unit) {
        ergebnisseHolen()
        while (true) {
            delay(5000)
            pruefen()
            // Solange etwas am Laptop in Arbeit ist, alle 30 Sekunden nachsehen
            abrufZaehler++
            val wartend = aufnahmen.any { a ->
                uploads[a.ordner.name]?.let { it.abgeschlossen && it.ergebnisId != null } == true &&
                    (ergebnisse[a.ordner.name]?.endgueltig != true || Auftraege.offen(a.ordner) != null)
            }
            if (wartend && abrufZaehler % 6 == 0) ergebnisseHolen()
        }
    }
    LaunchedEffect(zustand.laeuft) {
        while (zustand.laeuft) {
            jetzt = System.currentTimeMillis()
            delay(500)
        }
    }

    if (zeigeUebersicht) {
        UebersichtSeite { zeigeUebersicht = false }
        return
    }
    if (zeigeFragen) {
        FrageSeite(zurueck = { zeigeFragen = false }) { id -> zeigeFragen = false; vonFragen = true; offen = id }
        return
    }
    val offeneAufnahme = offen?.let { name -> aufnahmen.firstOrNull { it.ordner.name == name } }
    if (offeneAufnahme != null) {
        ErgebnisSeite(offeneAufnahme, ergebnisse[offeneAufnahme.ordner.name], aktualisierung + abrufZaehler) {
            offen = null
            // aus einer Antwort geöffnet: zurück zu den Fragen
            if (vonFragen) {
                vonFragen = false; zeigeFragen = true
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Schwarz)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("BRAININGER", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Weiss)
        Text("Gedanken und Gespräche festhalten", color = Grau, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))

        if (zustand.laeuft) {
            LaufendeAufnahme(zustand, jetzt) { AufnahmeDienst.stoppen(context) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GrosserKnopf("GEDANKE", Modifier.weight(1f), aktiv = mikroErlaubt) {
                    AufnahmeDienst.starten(context, Modus.GEDANKE)
                }
                GrosserKnopf("GESPRÄCH", Modifier.weight(1f), aktiv = mikroErlaubt) {
                    fragZustimmung = true
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (!mikroErlaubt || !hinweiseErlaubt) {
            Hinweis("Braininger braucht Zugriff auf das Mikrofon und darf Hinweise anzeigen.", "ERLAUBEN") {
                erlaubnisAnfrage.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        if (!akkuFrei) {
            Hinweis("Damit lange Aufnahmen bei gesperrtem Handy nicht abbrechen, die Akku-Optimierung für Braininger ausschalten.", "AUSSCHALTEN") {
                @SuppressLint("BatteryLife")
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                context.startActivity(intent)
            }
        }

        if (googleFehlt) {
            Hinweis("Für den Upload ins Google Drive einmal bei Google anmelden.", "ANMELDEN") { anmelden() }
        }

        testText?.let {
            Text(
                it, color = Grau, fontSize = 12.sp,
                modifier = Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeitenKnopf("ÜBERSICHT", Modifier.weight(1f)) { zeigeUebersicht = true }
            SeitenKnopf("FRAGEN", Modifier.weight(1f)) { zeigeFragen = true }
        }

        Text("AUFNAHMEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Grau)
        Spacer(Modifier.height(4.dp))
        if (aufnahmen.isNotEmpty()) {
            OutlinedTextField(
                value = suche, onValueChange = { suche = it.take(80) }, singleLine = true,
                placeholder = { Text("Suchen in allen Notizen und Texten", color = Grau, fontSize = 14.sp) },
                trailingIcon = if (suche.isNotEmpty()) {
                    { Text("✕", color = Grau, fontSize = 16.sp, modifier = Modifier.clickable { suche = "" }.padding(8.dp)) }
                } else null,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Weiss, unfocusedTextColor = Weiss,
                    focusedBorderColor = Weiss, unfocusedBorderColor = Linie, cursorColor = Weiss,
                ),
            )
            Spacer(Modifier.height(4.dp))
        }
        if (aufnahmen.isEmpty()) {
            Text("Noch keine Aufnahmen.", color = Grau, fontSize = 14.sp)
        }
        val treffer = remember(aufnahmen, ergebnisse, suche) {
            aufnahmen.mapNotNull { a -> Suche.treffer(a, ergebnisse[a.ordner.name], suche)?.let { a to it } }
        }
        if (suche.isNotBlank()) {
            Text(
                if (treffer.isEmpty()) "Nichts gefunden." else "${treffer.size} Treffer",
                color = Grau, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(treffer, key = { it.first.ordner.name }) { (a, ausschnitt) ->
                AufnahmeZeile(a, uploads[a.ordner.name], ergebnisse[a.ordner.name]) {
                    offen = a.ordner.name
                    ergebnisseHolen()
                }
                if (suche.isNotBlank() && ausschnitt.isNotBlank()) {
                    Text(
                        Suche.hervorheben(ausschnitt, suche), color = Grau, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp).clickable { offen = a.ordner.name },
                    )
                }
                HorizontalDivider(color = Linie)
            }
        }
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            color = Grau, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
    }

    if (fragZustimmung) {
        AlertDialog(
            onDismissRequest = { fragZustimmung = false },
            title = { Text("Gespräch aufnehmen") },
            text = { Text("Sind alle Anwesenden mit der Aufnahme einverstanden?") },
            confirmButton = {
                TextButton(onClick = {
                    fragZustimmung = false
                    AufnahmeDienst.starten(context, Modus.GESPRAECH)
                }) { Text("Ja, aufnehmen") }
            },
            dismissButton = { TextButton(onClick = { fragZustimmung = false }) { Text("Abbrechen") } },
            containerColor = Color111,
        )
    }
}

private val Color111 = androidx.compose.ui.graphics.Color(0xFF111111)

@Composable
private fun GrosserKnopf(text: String, modifier: Modifier, aktiv: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = aktiv,
        modifier = modifier.height(120.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.5.dp, if (aktiv) Weiss else Linie),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Weiss),
    ) {
        Text(text, fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun LaufendeAufnahme(z: AufnahmeZustand, jetzt: Long, stopp: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.5.dp, Linie, RoundedCornerShape(28.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .alpha(if ((jetzt / 500) % 2 == 0L) 1f else 0.3f)
                    .background(Rot, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text((z.modus?.anzeige ?: "").uppercase(), color = Grau, fontSize = 13.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(dauerText(jetzt - z.start), fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 48.sp, color = Weiss)
        Spacer(Modifier.height(4.dp))
        Text(
            "${z.mikrofon} · ${z.stueckeFertig} Stücke gesichert",
            color = if (z.mikrofonExtern) Grau else Rot, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = stopp,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Rot, contentColor = Weiss),
        ) {
            Text("STOPP", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun Hinweis(text: String, knopf: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
            .border(1.dp, Rot, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Weiss, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onClick) { Text(knopf, color = Rot, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SeitenKnopf(text: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .border(1.dp, Linie, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Weiss,
             modifier = Modifier.weight(1f), maxLines = 1)
        Text("›", color = Weiss, fontSize = 22.sp)
    }
}

@Composable
private fun AufnahmeZeile(a: Aufnahme, upload: UploadStand?, ergebnis: Ergebnis?, oeffnen: () -> Unit) {
    val datum = remember(a.start) { SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY).format(Date(a.start)) }
    val statusText = when {
        ergebnis != null && upload?.abgeschlossen == true ->
            ergebnis.anzeige + if (upload.audioGeloescht) "\nAudio gelöscht" else ""
        upload?.abgeschlossen == true -> "im Drive"
        upload != null && upload.dateien.isNotEmpty() -> "lädt hoch ${upload.dateien.size}/${a.stuecke}"
        else -> null
    } ?: when (a.status) {
        "laeuft" -> "läuft"
        "fertig" -> "auf dem Handy"
        "unterbrochen" -> "unterbrochen"
        else -> a.status
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = oeffnen).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${a.modus.anzeige} · $datum", color = Weiss, fontSize = 15.sp)
            Text(
                ergebnis?.kurz ?: "${dauerText(a.dauerMs)} · ${a.stuecke} Stücke · ${a.mikrofon}",
                color = Grau, fontSize = 12.sp, maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            statusText,
            color = if (a.status == "unterbrochen" || ergebnis?.status == "fehler") Rot
                    else if (ergebnis?.status == "fertig") Weiss else Grau,
            fontSize = 12.sp,
        )
    }
}
