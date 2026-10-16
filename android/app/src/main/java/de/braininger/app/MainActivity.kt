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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
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
        Erinnerungen.kanalAnlegen(this)
        Erinnerungen.allePlanen(this)
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
    val ordnungStand by Ordnung.stand.collectAsStateWithLifecycle()
    var archiviert by remember { mutableStateOf(emptySet<String>()) }
    var auswahl by remember { mutableStateOf(emptySet<String>()) }
    var papierkorb by remember { mutableStateOf(emptyMap<String, Long>()) }
    var zeigeArchiv by remember { mutableStateOf(false) }
    var vonArchiv by remember { mutableStateOf(false) }
    var zeigePapierkorb by remember { mutableStateOf(false) }
    var vonPapierkorb by remember { mutableStateOf(false) }
    val rueckgaengig by rueckgaengigHinweis
    val scope = rememberCoroutineScope()

    fun pruefen() {
        mikroErlaubt = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hinweiseErlaubt = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        akkuFrei = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        aufnahmen = Ablage.alle(context)
        uploads = aufnahmen.associate { it.ordner.name to UploadStand.lesen(it.ordner) }
        ergebnisse = aufnahmen.associate { it.ordner.name to Ergebnis.lesen(it.ordner) }
        googleFehlt = HochladenAuftrag.anmeldungFehlt(context)
        archiviert = Ordnung.archivierte(context)
        papierkorb = Ordnung.papierkorb(context)
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

    LaunchedEffect(aktualisierung, zustand.laeuft, zustand.stueckeFertig, ordnungStand) { pruefen() }
    LaunchedEffect(Unit) {
        try {
            OrdnungSicherung.wiederherstellen(context) // nach Neuinstallation aus dem Drive
        } catch (_: Exception) {
            // kein Netz oder nicht angemeldet: beim nächsten Start wieder
        }
    }
    LaunchedEffect(Unit) {
        val fehlend = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA)
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
        val id = offeneAufnahme.ordner.name
        fun zurueckZurListe() {
            offen = null
            // aus einer Antwort oder dem Archiv geöffnet: dorthin zurück
            if (vonFragen) {
                vonFragen = false; zeigeFragen = true
            }
            if (vonArchiv) {
                vonArchiv = false; zeigeArchiv = true
            }
            if (vonPapierkorb) {
                vonPapierkorb = false; zeigePapierkorb = true
            }
        }
        fun zurStartseite() {
            offen = null; vonFragen = false; vonArchiv = false; vonPapierkorb = false
        }
        val knoepfe = buildList<Pair<String, () -> Unit>> {
            if (offeneAufnahme.status == "laeuft") return@buildList
            when {
                id in papierkorb -> add("ZURÜCKHOLEN" to {
                    Ordnung.zurueckholen(context, listOf(id))
                    zurStartseite()
                    rueckgaengigHinweis.value = Rueckgaengig("Zurückgeholt") { Ordnung.loeschen(context, listOf(id)) }
                    ergebnisseHolen()
                })
                else -> {
                    if (Ordnung.vorne(offeneAufnahme, archiviert)) add("ARCHIVIEREN" to {
                        Ordnung.archivieren(context, listOf(id))
                        zurueckZurListe()
                        rueckgaengigHinweis.value = Rueckgaengig("Archiviert") { Ordnung.zurueckholen(context, listOf(id)) }
                    })
                    else if (id in archiviert && Ordnung.frisch(offeneAufnahme)) add("NACH VORNE" to {
                        Ordnung.zurueckholen(context, listOf(id))
                        zurStartseite()
                        rueckgaengigHinweis.value = Rueckgaengig("Wieder vorne") { Ordnung.archivieren(context, listOf(id)) }
                    })
                    add("LÖSCHEN" to {
                        Ordnung.loeschen(context, listOf(id))
                        zurueckZurListe()
                        rueckgaengigHinweis.value = Rueckgaengig("Gelöscht") { Ordnung.zurueckholen(context, listOf(id)) }
                        ergebnisseHolen()
                    })
                }
            }
        }
        ErgebnisSeite(offeneAufnahme, ergebnisse[id], aktualisierung + abrufZaehler, knoepfe) { zurueckZurListe() }
        return
    }
    // Gelöschte zählen nirgends mehr mit, nur im Papierkorb
    val sichtbar = aufnahmen.filter { it.ordner.name !in papierkorb }
    if (zeigePapierkorb) {
        PapierkorbSeite(
            aufnahmen.filter { it.ordner.name in papierkorb }, papierkorb, uploads, ergebnisse,
            oeffnen = { id -> zeigePapierkorb = false; vonPapierkorb = true; offen = id },
            zurueckgeholt = { ergebnisseHolen() },
        ) { zeigePapierkorb = false; zeigeArchiv = true }
        return
    }
    if (zeigeArchiv) {
        ArchivSeite(
            sichtbar, uploads, ergebnisse, archiviert, papierkorb.size,
            oeffnen = { id -> zeigeArchiv = false; vonArchiv = true; offen = id; ergebnisseHolen() },
            papierkorbOeffnen = { zeigeArchiv = false; zeigePapierkorb = true },
            geloescht = { ergebnisseHolen() },
        ) { zeigeArchiv = false }
        return
    }
    BackHandler(enabled = auswahl.isNotEmpty()) { auswahl = emptySet() }

    // Suche findet alles, auch Archiviertes. Ohne Suche stehen vorne nur die letzten 7 Tage.
    val treffer = remember(sichtbar, ergebnisse, suche, archiviert) {
        if (suche.isBlank()) sichtbar.filter { Ordnung.vorne(it, archiviert) }.map { it to "" }
        else sichtbar.mapNotNull { a -> Suche.treffer(a, ergebnisse[a.ordner.name], suche)?.let { a to it } }
    }
    val imArchiv = sichtbar.count { !Ordnung.vorne(it, archiviert) }
    Box(Modifier.fillMaxSize().background(Schwarz)) {
    // Die ganze Seite scrollt, damit Aufgaben, Vorschläge und Aufnahmen immer Platz haben
    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Schwarz)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        item {
        Column {
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

        AufgabenBereich()

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
        } else if (suche.isBlank() && treffer.isEmpty()) {
            Text("Keine Aufnahmen der letzten ${Ordnung.TAGE_VORNE} Tage.", color = Grau, fontSize = 14.sp,
                 modifier = Modifier.padding(vertical = 8.dp))
        }
        if (suche.isNotBlank()) {
            Text(
                if (treffer.isEmpty()) "Nichts gefunden." else "${treffer.size} Treffer",
                color = Grau, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        }
        }
            items(treffer, key = { it.first.ordner.name }) { (a, ausschnitt) ->
                val id = a.ordner.name
                AufnahmeZeile(
                    a, uploads[id], ergebnisse[id],
                    ausgewaehlt = id in auswahl,
                    lang = if (a.status == "laeuft") null else ({ auswahl = auswahl + id }),
                ) {
                    if (auswahl.isNotEmpty()) {
                        if (a.status != "laeuft") auswahl = if (id in auswahl) auswahl - id else auswahl + id
                    } else {
                        offen = id
                        ergebnisseHolen()
                    }
                }
                if (suche.isNotBlank() && ausschnitt.isNotBlank()) {
                    Text(
                        Suche.hervorheben(ausschnitt, suche), color = Grau, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp).clickable { offen = a.ordner.name },
                    )
                }
                HorizontalDivider(color = Linie)
            }
        if (suche.isBlank() && (imArchiv > 0 || papierkorb.isNotEmpty())) {
            item {
                Spacer(Modifier.height(16.dp))
                SeitenKnopf("ARCHIV ($imArchiv)", Modifier.fillMaxWidth()) { auswahl = emptySet(); zeigeArchiv = true }
            }
        }
        item {
        Spacer(Modifier.height(if (auswahl.isNotEmpty() || rueckgaengig != null) 90.dp else 0.dp))
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            color = Grau, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
        }
    }
    if (auswahl.isNotEmpty()) {
        AuswahlLeiste(auswahl.size, abbrechen = { auswahl = emptySet() }, Modifier.align(Alignment.BottomCenter)) {
            TextButton(onClick = {
                val ids = auswahl.toList()
                Ordnung.archivieren(context, ids)
                auswahl = emptySet()
                rueckgaengigHinweis.value = Rueckgaengig(if (ids.size == 1) "Archiviert" else "${ids.size} archiviert") {
                    Ordnung.zurueckholen(context, ids)
                }
            }) { Text("ARCHIVIEREN", color = Weiss, fontWeight = FontWeight.Bold) }
            LoeschenKnopf(auswahl) { auswahl = emptySet(); ergebnisseHolen() }
        }
    } else {
        RueckgaengigLeiste(Modifier.align(Alignment.BottomCenter))
    }
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
private val Ausgewaehlt = androidx.compose.ui.graphics.Color(0xFF262626)

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
        val context = LocalContext.current
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { AufnahmeDienst.markieren(context) },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.5.dp, Weiss),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Weiss),
            ) {
                Text(if (z.markierungen > 0) "MARKIERT ${z.markierungen}" else "MARKIEREN",
                     fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            }
            OutlinedButton(
                onClick = { z.ordner?.let { context.startActivity(FotoActivity.intent(context, java.io.File(it), z.start)) } },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.5.dp, Weiss),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Weiss),
            ) {
                Text(if (z.fotos > 0) "FOTO ${z.fotos}" else "FOTO",
                     fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
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
internal fun SeitenKnopf(text: String, modifier: Modifier, onClick: () -> Unit) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AufnahmeZeile(
    a: Aufnahme, upload: UploadStand?, ergebnis: Ergebnis?,
    ausgewaehlt: Boolean = false, lang: (() -> Unit)? = null, hinweis: String? = null, oeffnen: () -> Unit,
) {
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
        Modifier
            .fillMaxWidth()
            .background(if (ausgewaehlt) Ausgewaehlt else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = oeffnen, onLongClick = lang)
            .padding(vertical = 12.dp, horizontal = if (ausgewaehlt) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ausgewaehlt) {
            Text("✓", color = Weiss, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("${a.modus.anzeige} · $datum", color = Weiss, fontSize = 15.sp)
            Text(
                ergebnis?.kurz ?: "${dauerText(a.dauerMs)} · ${a.stuecke} Stücke · ${a.mikrofon}",
                color = Grau, fontSize = 12.sp, maxLines = 2,
            )
            hinweis?.let { Text(it, color = Rot, fontSize = 12.sp) }
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
