package de.braininger.app

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.claude.Schluessel
import de.braininger.app.claude.Zerlegen
import de.braininger.app.ui.Bereiche
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Rot
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.launch
import java.util.Calendar

private const val SICHTBAR = 5

/**
 * Aufgaben-Reiter (Grill 20.10.2026): drei Körbe statt Uhrzeiten, kein Rot, kein Zähler „überfällig“.
 * Oben der Wochenblick (sonntags) und die Vorschläge aus den Aufnahmen.
 */
@Composable
fun AufgabenSeite() {
    val context = LocalContext.current
    val stand by AufgabenSpeicher.stand.collectAsState()
    val ordnungStand by Ordnung.stand.collectAsState()
    val vorschlaege = remember(stand, ordnungStand) {
        val geloescht = Ordnung.ausgeblendet(context)
        AufgabenSpeicher.vorschlaege(context).filter { it.aufnahme !in geloescht }
    }
    val offene = remember(stand) { AufgabenSpeicher.offene(context) }
    val texte = remember(stand) { AufgabenSpeicher.alle(context).associate { it.id to it.text } }
    var bearbeiten by remember { mutableStateOf<Aufgabe?>(null) }
    var neuDialog by remember { mutableStateOf(false) }
    var vorschlaegeOffen by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            AufgabenSicherung.wiederherstellen(context) // nach Neuinstallation aus dem Drive
        } catch (_: Exception) {
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AUFGABEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Weiss,
                 modifier = Modifier.weight(1f))
            Text("+", color = Weiss, fontSize = 30.sp, modifier = Modifier.clickable { neuDialog = true }.padding(horizontal = 12.dp))
        }
        Spacer(Modifier.height(12.dp))

        if (Tagesrhythmus.wochenblickOffen(context)) {
            val alte = remember(stand) { Tagesrhythmus.wochenblickAufgaben(context) }
            if (alte.isNotEmpty()) Wochenblick(alte) else Tagesrhythmus.wochenblickFertig(context)
        }

        if (vorschlaege.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clickable { vorschlaegeOffen = !vorschlaegeOffen }.padding(vertical = 4.dp)) {
                Text("${vorschlaege.size} VORSCHLÄGE AUS AUFNAHMEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold,
                     fontSize = 14.sp, color = Grau, modifier = Modifier.weight(1f))
                Text(if (vorschlaegeOffen) "▾" else "▸", color = Grau, fontSize = 14.sp)
            }
            if (vorschlaegeOffen) vorschlaege.forEach { v -> VorschlagKarte(v, v.bezug?.let { texte[it] }) }
            Spacer(Modifier.height(16.dp))
        }

        val erledigt = remember(stand) { AufgabenSpeicher.erledigtHeute(context) }
        for (korb in listOf(Koerbe.HEUTE, Koerbe.WOCHE, Koerbe.IRGENDWANN)) {
            val liste = offene.filter { it.korbJetzt() == korb }
            KorbAbschnitt(korb, liste, if (korb == Koerbe.HEUTE) erledigt else emptyList()) { bearbeiten = it }
        }
        if (offene.isEmpty()) Text("Keine offenen Aufgaben.", color = Grau, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
    }

    if (neuDialog) NeueAufgabeDialog { neuDialog = false }
    bearbeiten?.let { a -> AufgabeDialog(a) { bearbeiten = null } }
}

@Composable
private fun KorbAbschnitt(korb: String, liste: List<Aufgabe>, erledigt: List<Aufgabe>, oeffnen: (Aufgabe) -> Unit) {
    val context = LocalContext.current
    var alle by remember { mutableStateOf(false) }
    Text(Koerbe.name(korb).uppercase(), // ohne Anzahl: Zahlen erzeugen Druck (Grill 20.10.2026)
         fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Grau)
    Spacer(Modifier.height(4.dp))
    if (liste.isEmpty() && erledigt.isEmpty()) {
        Text("–", color = Linie, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
        return
    }
    val sichtbar = if (alle) liste else liste.take(SICHTBAR)
    sichtbar.forEach { a -> AufgabeZeile(a, { AufgabenSpeicher.abhaken(context, a.id) }) { oeffnen(a) } }
    // heute Abgehaktes bleibt durchgestrichen stehen; Tipp auf den Kreis macht es wieder offen
    erledigt.forEach { a -> AufgabeZeile(a, { AufgabenSpeicher.wiederOeffnen(context, a.id) }) { } }
    if (liste.size > SICHTBAR) {
        Text(if (alle) "WENIGER" else "ALLE ${liste.size} ZEIGEN", color = Weiss, fontSize = 13.sp, fontWeight = FontWeight.Bold,
             modifier = Modifier.clickable { alle = !alle }.padding(vertical = 6.dp))
    }
    Spacer(Modifier.height(16.dp))
}

/** Sonntags: drei alte Aufgaben aus „Irgendwann“ – holen, behalten oder weg (Frage 59). */
@Composable
private fun Wochenblick(alte: List<Aufgabe>) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp)
            .border(1.dp, Weiss, RoundedCornerShape(16.dp)).padding(14.dp)
    ) {
        Text("WOCHENBLICK", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Weiss)
        Text("Drei alte Aufgaben. Was soll mit ihnen passieren?", color = Grau, fontSize = 13.sp)
        alte.forEach { a ->
            Spacer(Modifier.height(10.dp))
            Text(a.text, color = Weiss, fontSize = 15.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                KleinerKnopf("HOLEN") { AufgabenSpeicher.korbSetzen(context, a.id, Koerbe.HEUTE) }
                KleinerKnopf("BEHALTEN") {
                    // bleibt in „irgendwann“, kommt aber erst in einer Woche wieder in den Wochenblick
                    AufgabenSpeicher.korbSetzen(context, a.id, Koerbe.IRGENDWANN)
                    AufgabenSpeicher.behalten(context, a.id)
                }
                KleinerKnopf("WEG") { AufgabenSpeicher.loeschen(context, a.id) }
            }
        }
    }
}

@Composable
internal fun KleinerKnopf(text: String, hell: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.height(36.dp), shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (hell) Weiss else Linie),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hell) Weiss else Grau),
        // weniger Innenrand als üblich (24 dp), sonst passen drei Knöpfe nicht in eine Zeile und „IRGENDWANN“ verschwindet
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
}

@Composable
private fun VorschlagKarte(v: Vorschlag, bezugText: String?) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(Bereiche.flaeche(v.bereich), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val vorsatz = when (v.art) {
            "erledigt" -> "Erledigt? "
            "ergaenzen" -> "Ergänzen: "
            else -> ""
        }
        Text(vorsatz + (if (v.art == "erledigt") bezugText ?: v.text else v.text), color = Weiss, fontSize = 15.sp)
        val zusatz = listOfNotNull(
            v.frist?.let { Koerbe.fristText(it) }?.takeIf { it.isNotBlank() },
            "unklar, wer".takeIf { v.unklarWer },
            bezugText?.takeIf { v.art == "ergaenzen" }?.let { "bisher: $it" },
        )
        if (zusatz.isNotEmpty()) Text(zusatz.joinToString(" · "), color = Grau, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { AufgabenSpeicher.uebernehmen(context, setOf(v.id)) },
                modifier = Modifier.weight(1f).height(40.dp), shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.5.dp, Weiss), colors = ButtonDefaults.outlinedButtonColors(contentColor = Weiss),
            ) { Text(if (v.art == "erledigt") "ABHAKEN" else "ÜBERNEHMEN", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { AufgabenSpeicher.verwerfen(context, v.id) },
                modifier = Modifier.height(40.dp), shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Linie), colors = ButtonDefaults.outlinedButtonColors(contentColor = Grau),
            ) { Text("VERWERFEN", fontSize = 13.sp) }
        }
    }
}

/** Eine Aufgabe als gefärbte Fläche (Lebensbereich), darunter ihre Schritte als Häkchen. */
@Composable
internal fun AufgabeZeile(a: Aufgabe, abhaken: () -> Unit, oeffnen: () -> Unit) {
    val context = LocalContext.current
    val fertig = a.erledigt != null
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(if (fertig) Schwarz else Bereiche.flaeche(a.bereich), RoundedCornerShape(14.dp))
            .border(1.dp, if (fertig) Linie else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kreis(fertig, 26, abhaken)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clickable(enabled = !fertig, onClick = oeffnen)) {
                Text(a.text, color = if (fertig) Grau else Weiss, fontSize = 15.sp,
                     textDecoration = if (fertig) TextDecoration.LineThrough else null)
                if (fertig) {
                    Text("erledigt · zum Rückgängigmachen auf den Kreis tippen", color = Grau, fontSize = 12.sp)
                    return@Column
                }
                val zusatz = listOfNotNull(
                    a.heuteSeitFrueher()?.let { Koerbe.seitText(it) },
                    "ab morgen wieder in Heute".takeIf { a.zurueckAm?.let { it > System.currentTimeMillis() } == true },
                    a.frist?.let { Koerbe.fristText(it) }?.takeIf { it.isNotBlank() },
                    a.schritte.takeIf { it.isNotEmpty() }?.let { s -> "${s.count { it.erledigt }}/${s.size} Schritte" },
                    "unklar, wer".takeIf { a.unklarWer },
                )
                if (zusatz.isNotEmpty()) Text(zusatz.joinToString(" · "), color = Grau, fontSize = 12.sp)
            }
        }
        a.schritte.forEachIndexed { i, s ->
            Row(Modifier.padding(start = 38.dp, top = 6.dp).clickable { AufgabenSpeicher.schrittAbhaken(context, a.id, i) },
                verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp).border(1.dp, Weiss, CircleShape)
                        .background(if (s.erledigt) Weiss else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(s.text, color = if (s.erledigt) Grau else Weiss, fontSize = 14.sp,
                     textDecoration = if (s.erledigt) TextDecoration.LineThrough else null)
            }
        }
    }
}

/** Abhak-Kreis: leer = offen, gefüllt mit Häkchen = erledigt. */
@Composable
internal fun Kreis(fertig: Boolean, groesse: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(groesse.dp).border(1.5.dp, if (fertig) Grau else Weiss, CircleShape)
            .background(if (fertig) Grau else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (fertig) Text("✓", color = Schwarz, fontSize = (groesse * 0.55).sp, fontWeight = FontWeight.Bold)
    }
}

/** Nur ein Tag, keine Uhrzeit (Grill 20.10.2026: Uhrzeiten machen Schuldgefühle). Gespeichert wird 12 Uhr. */
private fun tagWaehlen(context: Context, start: Long?, fertig: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = start ?: (System.currentTimeMillis() + 86_400_000L) }
    DatePickerDialog(context, { _, j, m, t ->
        c.set(j, m, t, 12, 0, 0); c.set(Calendar.MILLISECOND, 0)
        fertig(c.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

private fun teilen(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

@Composable
private fun NeueAufgabeDialog(schliessen: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var frist by remember { mutableStateOf<Long?>(null) }
    var korb by remember { mutableStateOf(Koerbe.IRGENDWANN) }
    AlertDialog(
        onDismissRequest = schliessen,
        containerColor = Schwarz,
        title = { Text("NEUE AUFGABE", fontFamily = Punktschrift, color = Weiss) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(200) }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Was ist zu tun?", color = Grau) },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Weiss, unfocusedTextColor = Weiss,
                        focusedBorderColor = Weiss, unfocusedBorderColor = Linie, cursorColor = Weiss),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in listOf(Koerbe.HEUTE, Koerbe.WOCHE, Koerbe.IRGENDWANN)) {
                        KleinerKnopf(Koerbe.name(k).uppercase(), hell = korb == k) { korb = k }
                    }
                }
                TextButton(onClick = { tagWaehlen(context, frist) { frist = it } }) {
                    Text(frist?.let { "Frist: " + Koerbe.fristText(it) } ?: "+ FRIST (optional)", color = Weiss)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                AufgabenSpeicher.neu(context, text, frist, korb = korb); schliessen()
            }) { Text("HINZUFÜGEN", color = if (text.isNotBlank()) Weiss else Grau, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = schliessen) { Text("ABBRECHEN", color = Grau) } },
    )
}

@Composable
internal fun AufgabeDialog(a: Aufgabe, schliessen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var meldung by remember { mutableStateOf<String?>(null) }
    var zerlegt by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = schliessen,
        containerColor = Schwarz,
        title = { Text(a.text, color = Weiss, fontSize = 17.sp) },
        text = {
            Column {
                Text(listOfNotNull(Koerbe.name(a.korbJetzt()), a.frist?.let { Koerbe.fristText(it) }?.takeIf { it.isNotBlank() }).joinToString(" · "),
                     color = Grau, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                // heute / morgen / später (Frage 14)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KleinerKnopf("HEUTE") { AufgabenSpeicher.korbSetzen(context, a.id, Koerbe.HEUTE); schliessen() }
                    KleinerKnopf("MORGEN") { AufgabenSpeicher.morgenWieder(context, a.id); schliessen() }
                    KleinerKnopf("SPÄTER") { AufgabenSpeicher.korbSetzen(context, a.id, Koerbe.IRGENDWANN); schliessen() }
                }
                Spacer(Modifier.height(12.dp))
                Text("FOKUS", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Grau)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    for (m in listOf(10, 25, 45)) KleinerKnopf("$m MIN") { FokusDienst.starten(context, a.text, m); schliessen() }
                }
                Spacer(Modifier.height(6.dp))
                if (a.schritte.isEmpty()) {
                    TextButton(enabled = !zerlegt, onClick = {
                        if (!Schluessel.vorhanden(context)) {
                            meldung = "Dafür braucht Braininger einen Claude-Schlüssel (Einstellungen)."
                            return@TextButton
                        }
                        zerlegt = true
                        meldung = "Claude zerlegt die Aufgabe …"
                        scope.launch {
                            meldung = try {
                                val s = Zerlegen.schritte(context, a)
                                if (s.isEmpty()) "Claude hat keine Schritte gefunden." else {
                                    AufgabenSpeicher.schritteSetzen(context, a.id, s); schliessen(); null
                                }
                            } catch (e: Exception) {
                                zerlegt = false
                                "Ging gerade nicht: ${e.message}"
                            }
                        }
                    }) { Text("KLEINER MACHEN", color = Weiss) }
                } else {
                    TextButton(onClick = { AufgabenSpeicher.schritteSetzen(context, a.id, emptyList()); schliessen() }) {
                        Text("SCHRITTE ENTFERNEN", color = Weiss)
                    }
                }
                meldung?.let { Text(it, color = Grau, fontSize = 12.sp) }
                TextButton(onClick = { tagWaehlen(context, a.frist) { AufgabenSpeicher.fristSetzen(context, a.id, it); schliessen() } }) {
                    Text(if (a.frist == null) "FRIST SETZEN" else "FRIST ÄNDERN", color = Weiss)
                }
                if (a.frist != null) TextButton(onClick = { AufgabenSpeicher.fristSetzen(context, a.id, null); schliessen() }) {
                    Text("FRIST ENTFERNEN", color = Weiss)
                }
                TextButton(onClick = { teilen(context, a.text); schliessen() }) { Text("TEILEN", color = Weiss) }
                TextButton(onClick = { AufgabenSpeicher.loeschen(context, a.id); schliessen() }) { Text("LÖSCHEN", color = Rot) }
            }
        },
        confirmButton = {
            TextButton(onClick = { AufgabenSpeicher.abhaken(context, a.id); schliessen() }) {
                Text("ERLEDIGT", color = Weiss, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = schliessen) { Text("SCHLIESSEN", color = Grau) } },
    )
}
