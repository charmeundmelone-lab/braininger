package de.braininger.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.drive.Ergebnis
import de.braininger.app.drive.UploadStand
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Hinweis unten mit RÜCKGÄNGIG, verschwindet nach ein paar Sekunden. */
class Rueckgaengig(val text: String, val aktion: () -> Unit)

/** Ein Hinweis für alle Seiten, damit er auch nach einem Seitenwechsel stehen bleibt. */
val rueckgaengigHinweis = mutableStateOf<Rueckgaengig?>(null)

/** Alles, was nicht vorne steht: ältere Aufnahmen und von Hand archivierte, nach Tagen. Unten der Papierkorb. */
@Composable
fun ArchivSeite(
    sichtbar: List<Aufnahme>,
    uploads: Map<String, UploadStand>,
    ergebnisse: Map<String, Ergebnis?>,
    archiviert: Set<String>,
    imPapierkorb: Int,
    oeffnen: (String) -> Unit,
    papierkorbOeffnen: () -> Unit,
    geloescht: () -> Unit,
    zurueck: () -> Unit,
) {
    val context = LocalContext.current
    var auswahl by remember { mutableStateOf(emptySet<String>()) }
    BackHandler { if (auswahl.isNotEmpty()) auswahl = emptySet() else zurueck() }

    val imArchiv = sichtbar.filter { !Ordnung.vorne(it, archiviert) }
    // nur von Hand archivierte der letzten 7 Tage können wieder nach vorne
    val kannNachVorne = auswahl.filter { id -> id in archiviert && sichtbar.any { it.ordner.name == id && Ordnung.frisch(it) } }

    Box(Modifier.fillMaxSize().background(Schwarz)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            item {
                Kopf("ARCHIV", "Ältere Aufnahmen und alles, was du archiviert hast. Suche und Fragen finden sie weiter.", zurueck)
                if (imArchiv.isEmpty()) Text("Das Archiv ist leer.", color = Grau, fontSize = 14.sp)
            }
            nachTagen(imArchiv, uploads, ergebnisse, auswahl, { auswahl = it }, oeffnen)
            if (imPapierkorb > 0) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SeitenKnopf("PAPIERKORB ($imPapierkorb)", Modifier.fillMaxWidth()) { auswahl = emptySet(); papierkorbOeffnen() }
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }

        if (auswahl.isNotEmpty()) {
            AuswahlLeiste(auswahl.size, abbrechen = { auswahl = emptySet() }, Modifier.align(Alignment.BottomCenter)) {
                if (kannNachVorne.isNotEmpty()) {
                    TextButton(onClick = {
                        val ids = kannNachVorne.toList()
                        Ordnung.zurueckholen(context, ids)
                        auswahl = emptySet()
                        rueckgaengigHinweis.value = Rueckgaengig("${ids.size} wieder vorne") { Ordnung.archivieren(context, ids) }
                    }) { Text("NACH VORNE", color = Weiss, fontWeight = FontWeight.Bold) }
                }
                LoeschenKnopf(auswahl) { auswahl = emptySet(); geloescht() }
            }
        } else {
            RueckgaengigLeiste(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Gelöschte Aufnahmen, 30 Tage zum Zurückholen. Danach sind sie überall weg. */
@Composable
fun PapierkorbSeite(
    geloeschte: List<Aufnahme>,
    papierkorb: Map<String, Long>,
    uploads: Map<String, UploadStand>,
    ergebnisse: Map<String, Ergebnis?>,
    oeffnen: (String) -> Unit,
    zurueckgeholt: () -> Unit,
    zurueck: () -> Unit,
) {
    val context = LocalContext.current
    var auswahl by remember { mutableStateOf(emptySet<String>()) }
    BackHandler { if (auswahl.isNotEmpty()) auswahl = emptySet() else zurueck() }
    // zuletzt gelöschte zuerst
    val liste = geloeschte.sortedByDescending { papierkorb[it.ordner.name] ?: 0L }

    Box(Modifier.fillMaxSize().background(Schwarz)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            item {
                Kopf(
                    "PAPIERKORB",
                    "Gelöschte Aufnahmen zählen nirgends mehr mit. Nach ${Ordnung.TAGE_PAPIERKORB} Tagen sind sie überall " +
                        "endgültig weg. Bis dahin kannst du sie zurückholen.",
                    zurueck,
                )
                if (liste.isEmpty()) Text("Der Papierkorb ist leer.", color = Grau, fontSize = 14.sp)
            }
            items(liste, key = { it.ordner.name }) { a ->
                val id = a.ordner.name
                AufnahmeZeile(
                    a, uploads[id], ergebnisse[id],
                    ausgewaehlt = id in auswahl,
                    lang = { auswahl = auswahl + id },
                    hinweis = papierkorb[id]?.let { tageText(Ordnung.restTage(it)) },
                ) {
                    if (auswahl.isNotEmpty()) auswahl = if (id in auswahl) auswahl - id else auswahl + id
                    else oeffnen(id)
                }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }

        if (auswahl.isNotEmpty()) {
            AuswahlLeiste(auswahl.size, abbrechen = { auswahl = emptySet() }, Modifier.align(Alignment.BottomCenter)) {
                TextButton(onClick = {
                    val ids = auswahl.toList()
                    Ordnung.zurueckholen(context, ids)
                    auswahl = emptySet()
                    rueckgaengigHinweis.value = Rueckgaengig(if (ids.size == 1) "Zurückgeholt" else "${ids.size} zurückgeholt") {
                        Ordnung.loeschen(context, ids)
                    }
                    zurueckgeholt()
                }) { Text("ZURÜCKHOLEN", color = Weiss, fontWeight = FontWeight.Bold) }
            }
        } else {
            RueckgaengigLeiste(Modifier.align(Alignment.BottomCenter))
        }
    }
}

fun tageText(tage: Int) = when (tage) {
    0 -> "wird heute endgültig gelöscht"
    1 -> "noch 1 Tag"
    else -> "noch $tage Tage"
}

/** Knopf LÖSCHEN in der Auswahlleiste: in den Papierkorb, mit RÜCKGÄNGIG. */
@Composable
fun LoeschenKnopf(auswahl: Set<String>, danach: () -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = {
        val ids = auswahl.toList()
        Ordnung.loeschen(context, ids)
        rueckgaengigHinweis.value = Rueckgaengig(if (ids.size == 1) "Gelöscht" else "${ids.size} gelöscht") {
            Ordnung.zurueckholen(context, ids)
        }
        danach()
    }) { Text("LÖSCHEN", color = Weiss, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Kopf(titel: String, text: String, zurueck: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        TextButton(onClick = zurueck) { Text("‹ ZURÜCK", color = Weiss, fontWeight = FontWeight.Bold) }
    }
    Text(titel, fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Weiss)
    Text(text, color = Grau, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
}

private fun LazyListScope.nachTagen(
    liste: List<Aufnahme>,
    uploads: Map<String, UploadStand>,
    ergebnisse: Map<String, Ergebnis?>,
    auswahl: Set<String>,
    auswahlSetzen: (Set<String>) -> Unit,
    oeffnen: (String) -> Unit,
) {
    val tagFormat = SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY)
    for ((tag, tagesListe) in liste.groupBy { tagFormat.format(Date(it.start)) }) {
        item(key = "tag_$tag") {
            Text(
                tag.uppercase(Locale.GERMANY), color = Grau, fontSize = 12.sp, letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
            )
            HorizontalDivider(color = Linie)
        }
        items(tagesListe, key = { it.ordner.name }) { a ->
            val id = a.ordner.name
            AufnahmeZeile(
                a, uploads[id], ergebnisse[id],
                ausgewaehlt = id in auswahl,
                lang = { auswahlSetzen(auswahl + id) },
            ) {
                if (auswahl.isNotEmpty()) auswahlSetzen(if (id in auswahl) auswahl - id else auswahl + id)
                else oeffnen(id)
            }
        }
    }
}

/** Leiste unten im Auswahlmodus: Anzahl, Aktionen, ✕. */
@Composable
fun AuswahlLeiste(anzahl: Int, abbrechen: () -> Unit, modifier: Modifier, aktionen: @Composable () -> Unit) {
    Row(
        modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .background(Leiste, RoundedCornerShape(20.dp))
            .border(1.dp, Linie, RoundedCornerShape(20.dp))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = abbrechen) { Text("✕", color = Weiss, fontSize = 18.sp) }
        Text("$anzahl gewählt", color = Weiss, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
        aktionen()
    }
}

/** Zeigt den Hinweis aus [rueckgaengigHinweis] für ein paar Sekunden. */
@Composable
fun RueckgaengigLeiste(modifier: Modifier) {
    val r = rueckgaengigHinweis.value ?: return
    LaunchedEffect(r) {
        delay(6000)
        if (rueckgaengigHinweis.value === r) rueckgaengigHinweis.value = null
    }
    Row(
        modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .background(Leiste, RoundedCornerShape(20.dp))
            .border(1.dp, Linie, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.text, color = Weiss, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { r.aktion(); rueckgaengigHinweis.value = null }) {
            Text("RÜCKGÄNGIG", color = Weiss, fontWeight = FontWeight.Bold)
        }
    }
}

private val Leiste = Color(0xFF1A1A1A)
