package de.braininger.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss

/**
 * Reiter „Einkauf“ (Grill Einkauf 2.11.2026): eine Liste, Laden als Überschrift, Supermarkt nach Abteilungen sortiert.
 * Abgehaktes bleibt durchgestrichen bis LISTE LEEREN; wischen = weg. IM LADEN: Bildschirm bleibt an, große Kästchen,
 * Abgehaktes verschwindet aus dem Blick.
 */
@Composable
fun EinkaufSeite() {
    val context = LocalContext.current
    val stand by EinkaufSpeicher.stand.collectAsState()
    val liste = remember(stand) { EinkaufSpeicher.alle(context) }
    val oft = remember(stand) { EinkaufSpeicher.oftGekauft(context) }
    var imLaden by remember { mutableStateOf(false) }
    var verschieben by remember { mutableStateOf<Einkauf?>(null) }

    if (imLaden) {
        ImLaden(liste) { imLaden = false }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 16.dp)) {
        Text("EINKAUF", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Weiss)
        Spacer(Modifier.height(12.dp))
        val offen = liste.filter { it.erledigt == null }
        if (offen.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SeitenKnopf("IM LADEN", Modifier.weight(1f)) { imLaden = true }
                SeitenKnopf("TEILEN", Modifier.weight(1f)) {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EinkaufSpeicher.alsText(context)), null))
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (oft.isNotEmpty()) {
            Text("OFT GEKAUFT", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Grau)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                oft.forEach { s ->
                    Text("+ $s", color = Weiss, fontSize = 14.sp,
                         modifier = Modifier.border(1.dp, Linie, RoundedCornerShape(16.dp))
                             .clickable { EinkaufSpeicher.ausHaeufig(context, s) }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (liste.isEmpty()) {
            Text("Die Liste ist leer. Sag beim Aufnehmen einfach, was fehlt („Die Milch ist alle“), oder halte die Taste " +
                 "und sag „Einkauf: Eier, Butter“.", color = Grau, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
        }

        // je Laden: offene oben (nach Abteilung), Abgehaktes durchgestrichen darunter
        val gruppen = EinkaufSpeicher.nachLaden(liste)
        gruppen.forEach { (laden, eintraege) ->
            Spacer(Modifier.height(10.dp))
            Text(laden.uppercase(), fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Grau)
            Spacer(Modifier.height(4.dp))
            (eintraege.filter { it.erledigt == null } + eintraege.filter { it.erledigt != null }.sortedBy { it.erledigt })
                .forEach { e -> key(e.id) { EinkaufZeile(e, gross = false) { verschieben = e } } }
        }

        if (liste.any { it.erledigt != null }) {
            Spacer(Modifier.height(18.dp))
            SeitenKnopf("LISTE LEEREN", Modifier.fillMaxWidth()) {
                val weg = EinkaufSpeicher.leeren(context)
                rueckgaengigHinweis.value = Rueckgaengig("Abgehaktes weg") { EinkaufSpeicher.alleZurueck(context, weg) }
            }
        }
        Spacer(Modifier.height(100.dp))
    }

    verschieben?.let { e -> LadenDialog(e) { verschieben = null } }
}

/** Ein Eintrag: Kreis zum Abhaken, Sache groß, Menge, Details und „Anna holt“ klein. Zur Seite wischen = weg. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EinkaufZeile(e: Einkauf, gross: Boolean, tippen: () -> Unit) {
    val context = LocalContext.current
    val fertig = e.erledigt != null
    val wisch = rememberSwipeToDismissBoxState(confirmValueChange = { wert ->
        if (wert != SwipeToDismissBoxValue.Settled) {
            EinkaufSpeicher.loeschen(context, e.id)?.let { weg ->
                rueckgaengigHinweis.value = Rueckgaengig("${weg.sache} gelöscht") { EinkaufSpeicher.zurueck(context, weg) }
            }
            true
        } else false
    })
    SwipeToDismissBox(
        state = wisch,
        backgroundContent = {
            Box(Modifier.fillMaxSize().padding(vertical = 3.dp).border(1.dp, Linie, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart) { Text("LÖSCHEN", color = Grau, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Schwarz, RoundedCornerShape(14.dp))
                .border(1.dp, Linie, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = if (gross) 16.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Kreis(fertig, if (gross) 36 else 26) { EinkaufSpeicher.umschalten(context, e.id) }
            Spacer(Modifier.width(if (gross) 16.dp else 12.dp))
            Column(Modifier.weight(1f).clickable(enabled = !fertig, onClick = tippen)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(e.sache, color = if (fertig) Grau else Weiss, fontSize = if (gross) 21.sp else 16.sp,
                         textDecoration = if (fertig) TextDecoration.LineThrough else null)
                    if (e.menge.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(e.menge, color = Grau, fontSize = if (gross) 17.sp else 14.sp,
                             textDecoration = if (fertig) TextDecoration.LineThrough else null)
                    }
                }
                if (fertig) return@Column
                val zusatz = listOfNotNull(e.details.ifBlank { null }, e.werHolt.ifBlank { null }?.let { EinkaufSpeicher.holtText(it) })
                if (zusatz.isNotEmpty()) Text(zusatz.joinToString(" · "), color = Grau, fontSize = if (gross) 15.sp else 12.sp)
            }
        }
    }
}

/** Tipp auf einen Eintrag: in einen anderen Laden schieben (Braininger merkt es sich). */
@Composable
private fun LadenDialog(e: Einkauf, schliessen: () -> Unit) {
    val context = LocalContext.current
    val laeden = remember { EinkaufSpeicher.laeden(context) }
    AlertDialog(
        onDismissRequest = schliessen,
        containerColor = Schwarz,
        title = { Text(e.sache, color = Weiss, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("In welchen Laden? Braininger merkt sich das für „${e.sache}“.", color = Grau, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                laeden.forEach { l ->
                    val hier = l == e.laden
                    Text(l, color = if (hier) Grau else Weiss, fontSize = 16.sp,
                         fontWeight = if (hier) FontWeight.Bold else FontWeight.Normal,
                         modifier = Modifier.fillMaxWidth().clickable(enabled = !hier) {
                             EinkaufSpeicher.verschieben(context, e.id, l); schliessen()
                         }.padding(vertical = 10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = schliessen) { Text("FERTIG", color = Weiss, fontWeight = FontWeight.Bold) } },
    )
}

/** IM LADEN (Frage 18): Bildschirm bleibt an, große Kästchen, Abgehaktes verschwindet sofort aus dem Blick. */
@Composable
private fun ImLaden(liste: List<Einkauf>, fertig: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = fertig)
    val offen = liste.filter { it.erledigt == null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("IM LADEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Weiss,
                 modifier = Modifier.weight(1f))
            KleinerKnopf("FERTIG", onClick = fertig)
        }
        Spacer(Modifier.height(8.dp))
        if (offen.isEmpty()) {
            Text("Alles im Korb.", color = Weiss, fontSize = 20.sp, modifier = Modifier.padding(vertical = 24.dp))
        }
        EinkaufSpeicher.nachLaden(offen).forEach { (laden, eintraege) ->
            Spacer(Modifier.height(12.dp))
            Text(laden.uppercase(), fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Grau)
            Spacer(Modifier.height(4.dp))
            eintraege.forEach { e -> key(e.id) { EinkaufZeile(e, gross = true) { } } }
        }
        Spacer(Modifier.height(100.dp))
    }
}
