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

/** Alles, was nicht vorne steht: ältere Aufnahmen und von Hand archivierte, nach Tagen. */
@Composable
fun ArchivSeite(
    aufnahmen: List<Aufnahme>,
    uploads: Map<String, UploadStand>,
    ergebnisse: Map<String, Ergebnis?>,
    archiviert: Set<String>,
    oeffnen: (String) -> Unit,
    zurueck: () -> Unit,
) {
    val context = LocalContext.current
    var auswahl by remember { mutableStateOf(emptySet<String>()) }
    var rueckgaengig by remember { mutableStateOf<Rueckgaengig?>(null) }
    BackHandler { if (auswahl.isNotEmpty()) auswahl = emptySet() else zurueck() }

    val imArchiv = aufnahmen.filter { !Ordnung.vorne(it, archiviert) }
    val tagFormat = remember { SimpleDateFormat("EEEE, dd.MM.yyyy", Locale.GERMANY) }
    val nachTagen = remember(imArchiv) { imArchiv.groupBy { tagFormat.format(Date(it.start)) }.toList() }
    // nur von Hand archivierte der letzten 7 Tage können wieder nach vorne
    val kannNachVorne = auswahl.filter { id -> id in archiviert && aufnahmen.any { it.ordner.name == id && Ordnung.frisch(it) } }

    Box(Modifier.fillMaxSize().background(Schwarz)) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    TextButton(onClick = zurueck) { Text("‹ ZURÜCK", color = Weiss, fontWeight = FontWeight.Bold) }
                }
                Text("ARCHIV", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = Weiss)
                Text(
                    "Ältere Aufnahmen und alles, was du archiviert hast. Suche und Fragen finden sie weiter.",
                    color = Grau, fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                if (imArchiv.isEmpty()) Text("Das Archiv ist leer.", color = Grau, fontSize = 14.sp)
            }
            for ((tag, liste) in nachTagen) {
                item(key = "tag_$tag") {
                    Text(
                        tag.uppercase(Locale.GERMANY), color = Grau, fontSize = 12.sp, letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                    )
                    HorizontalDivider(color = Linie)
                }
                items(liste, key = { it.ordner.name }) { a ->
                    val id = a.ordner.name
                    AufnahmeZeile(
                        a, uploads[id], ergebnisse[id],
                        ausgewaehlt = id in auswahl,
                        lang = { auswahl = auswahl + id },
                    ) {
                        if (auswahl.isNotEmpty()) auswahl = if (id in auswahl) auswahl - id else auswahl + id
                        else oeffnen(id)
                    }
                    HorizontalDivider(color = Linie)
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
                        rueckgaengig = Rueckgaengig("${ids.size} wieder vorne") { Ordnung.archivieren(context, ids) }
                    }) { Text("NACH VORNE", color = Weiss, fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            RueckgaengigLeiste(rueckgaengig, Modifier.align(Alignment.BottomCenter)) { rueckgaengig = null }
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
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = abbrechen) { Text("✕", color = Weiss, fontSize = 18.sp) }
        Text("$anzahl ausgewählt", color = Weiss, fontSize = 14.sp, modifier = Modifier.weight(1f))
        aktionen()
    }
}

@Composable
fun RueckgaengigLeiste(r: Rueckgaengig?, modifier: Modifier, weg: () -> Unit) {
    if (r == null) return
    LaunchedEffect(r) {
        delay(6000)
        weg()
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
        TextButton(onClick = { r.aktion(); weg() }) { Text("RÜCKGÄNGIG", color = Weiss, fontWeight = FontWeight.Bold) }
    }
}

private val Leiste = Color(0xFF1A1A1A)
