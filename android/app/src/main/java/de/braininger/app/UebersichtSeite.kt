package de.braininger.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.drive.AnmeldungNoetig
import de.braininger.app.drive.Uebersicht
import de.braininger.app.drive.Uebersichten
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Rot
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Tagesübersichten der letzten Tage, die neueste aufgeklappt. Knopf: Übersicht für heute jetzt erstellen. */
@Composable
fun UebersichtSeite(zurueck: () -> Unit) {
    BackHandler(onBack = zurueck)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var liste by remember { mutableStateOf(Uebersichten.sichtbar(context)) }
    var offen by remember { mutableStateOf(liste.firstOrNull()?.tag) }
    var meldung by remember { mutableStateOf<String?>(null) }
    var auftragOffen by remember { mutableStateOf(Uebersichten.auftragOffen(context)) }

    suspend fun holen() {
        try {
            Uebersichten.abrufen(context)
        } catch (_: AnmeldungNoetig) {
            meldung = "Erst bei Google anmelden."
        } catch (_: Exception) {
            // kein Netz: beim nächsten Mal
        }
        liste = Uebersichten.sichtbar(context)
        auftragOffen = Uebersichten.auftragOffen(context)
        if (offen == null) offen = liste.firstOrNull()?.tag
    }

    LaunchedEffect(Unit) {
        while (true) {
            holen()
            delay(if (auftragOffen) 20_000 else 60_000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Schwarz)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        TextButton(onClick = zurueck, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("‹ ZURÜCK", color = Weiss, fontWeight = FontWeight.Bold)
        }
        Text("TAGESÜBERSICHT", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Weiss)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                meldung = null
                scope.launch {
                    try {
                        Uebersichten.jetztErstellen(context)
                        auftragOffen = true
                        holen()
                    } catch (_: AnmeldungNoetig) {
                        meldung = "Erst bei Google anmelden."
                    } catch (e: Exception) {
                        meldung = "Auftrag nicht gesendet: ${e.message}"
                    }
                }
            },
            enabled = !auftragOffen,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, if (auftragOffen) Linie else Weiss),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Weiss),
        ) {
            Text(
                if (auftragOffen) "LAPTOP SCHREIBT …" else "HEUTE JETZT ERSTELLEN",
                fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            )
        }
        Text(
            "Die Übersicht für gestern kommt automatisch, sobald der Laptop morgens an ist.",
            color = Grau, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        meldung?.let { Text(it, color = Rot, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(12.dp))

        if (liste.isEmpty()) {
            Text("Noch keine Tagesübersicht.", color = Grau, fontSize = 14.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(liste, key = { it.tag }) { u ->
                TagKarte(u, aufgeklappt = offen == u.tag) { offen = if (offen == u.tag) null else u.tag }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TagKarte(u: Uebersicht, aufgeklappt: Boolean, umschalten: () -> Unit) {
    val datum = remember(u.tag) {
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(u.tag)!!
            SimpleDateFormat("EEEE, dd.MM.", Locale.GERMANY).format(d)
        } catch (_: Exception) {
            u.tag
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, Linie, RoundedCornerShape(20.dp))
            .clickable(onClick = umschalten)
            .padding(16.dp)
    ) {
        Text(
            (datum + if (u.anzeige.isNotEmpty()) " · ${u.anzeige}" else "").uppercase(Locale.GERMANY),
            color = if (u.status == "fehler") Rot else Grau, fontSize = 11.sp, letterSpacing = 1.5.sp,
        )
        Text(u.titel ?: "Noch keine Übersicht", color = Weiss, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        u.meldung?.let { Text(it, color = Grau, fontSize = 12.sp) }
        if (aufgeklappt && u.inhalt != null) {
            Spacer(Modifier.height(8.dp))
            Markdown(u.inhalt)
        }
    }
}
