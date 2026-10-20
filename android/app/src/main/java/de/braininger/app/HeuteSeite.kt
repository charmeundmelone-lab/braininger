package de.braininger.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.ui.Bereiche
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.delay

private const val MAX_PUNKTE = 5 // Frage 13: bis zu 5 Punkte, sonst wird es zu viel

/**
 * Heute-Überblick auf der Startseite (Grill 20.10.2026): Termine, die Jan betreffen, und Aufgaben aus „heute“,
 * nach Tageszeit gemischt, höchstens 5 sichtbar. Darunter eine einzige Zeile für die Vorschläge.
 */
@Composable
fun HeuteBereich(aktualisierung: Int, zuAufgaben: () -> Unit) {
    val context = LocalContext.current
    val aufgabenStand by AufgabenSpeicher.stand.collectAsState()
    var kalenderStand by remember { mutableLongStateOf(0L) }
    var alle by remember { mutableStateOf(false) }
    var terminWahl by remember { mutableStateOf<Termin?>(null) }
    val punkte = remember(aktualisierung, aufgabenStand, kalenderStand) { Tagesrhythmus.heute(context) }
    val vorschlaege = remember(aufgabenStand) { AufgabenSpeicher.vorschlaege(context).size }
    val kalenderAnfrage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        kalenderStand++
        if (it) Tagesrhythmus.planen(context)
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000) // vergangene Termine fallen heraus
            kalenderStand++
        }
    }

    Text("HEUTE", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Grau)
    Spacer(Modifier.height(6.dp))
    if (punkte.isEmpty()) {
        Text("Heute steht nichts Festes an.", color = Grau, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
    }
    val sichtbar = if (alle) punkte else punkte.take(MAX_PUNKTE)
    sichtbar.forEach { p -> HeutePunkt(p) { p.termin?.let { terminWahl = it } } }
    if (punkte.size > MAX_PUNKTE) {
        Text(if (alle) "WENIGER" else "+ ${punkte.size - MAX_PUNKTE} WEITERE", color = Weiss, fontSize = 13.sp,
             fontWeight = FontWeight.Bold, modifier = Modifier.clickable { alle = !alle }.padding(vertical = 8.dp))
    }
    if (!Kalender.erlaubt(context)) {
        Text("Termine aus deinem Kalender anzeigen", color = Weiss, fontSize = 13.sp,
             modifier = Modifier.padding(top = 6.dp).border(1.dp, Linie, RoundedCornerShape(14.dp))
                 .clickable { kalenderAnfrage.launch(Manifest.permission.READ_CALENDAR) }
                 .padding(horizontal = 12.dp, vertical = 8.dp))
    }
    if (vorschlaege > 0) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = zuAufgaben).padding(vertical = 6.dp)) {
            Text(if (vorschlaege == 1) "1 Vorschlag ansehen" else "$vorschlaege Vorschläge ansehen",
                 color = Weiss, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("›", color = Weiss, fontSize = 18.sp)
        }
    }
    FokusKarte()
    Spacer(Modifier.height(20.dp))

    terminWahl?.let { t ->
        AlertDialog(
            onDismissRequest = { terminWahl = null },
            containerColor = Schwarz,
            title = { Text(t.titel, color = Weiss, fontSize = 17.sp) },
            text = {
                Text((if (t.ganztags) "ganztägig" else Tagesrhythmus.uhrzeit(t.beginn)) + " · Kalender „${t.kalender}“",
                     color = Grau, fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = { Kalender.festlegen(context, t, true); terminWahl = null; kalenderStand++ }) {
                    Text("ERINNERN", color = Weiss, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { Kalender.festlegen(context, t, false); terminWahl = null; kalenderStand++ }) {
                    Text("BETRIFFT MICH NICHT", color = Grau)
                }
            },
        )
    }
}

@Composable
private fun HeutePunkt(p: Tagesrhythmus.Punkt, terminTippen: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(Bereiche.flaeche(p.bereich), RoundedCornerShape(14.dp))
            .clickable(enabled = p.termin != null, onClick = terminTippen)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val a = p.aufgabe
        if (a != null) {
            Box(Modifier.size(22.dp).border(1.5.dp, Weiss, CircleShape).clickable { AufgabenSpeicher.abhaken(context, a.id) })
            Spacer(Modifier.width(12.dp))
        } else {
            Text(p.zeit?.let { Tagesrhythmus.uhrzeit(it) } ?: "ganzt.", color = Weiss, fontSize = 14.sp,
                 fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(p.text, color = Weiss, fontSize = 15.sp)
            a?.heuteSeitFrueher()?.let { Text(Koerbe.seitText(it), color = Grau, fontSize = 12.sp) }
            p.termin?.ort?.takeIf { it.isNotBlank() }?.let { Text(it, color = Grau, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

/** Läuft der Fokus-Timer, steht er hier mit Restzeit und Knopf zum Beenden. */
@Composable
private fun FokusKarte() {
    val context = LocalContext.current
    val z by FokusDienst.zustand.collectAsState()
    if (!z.laeuft) return
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(z.ende) {
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val rest = ((z.ende - jetzt) / 60_000L).coerceAtLeast(0) + 1
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Weiss, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("FOKUS · noch $rest min", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Weiss)
            Text(z.aufgabe, color = Grau, fontSize = 13.sp, maxLines = 2)
        }
        KleinerKnopf("BEENDEN", hell = false) { FokusDienst.stoppen(context) }
    }
}
