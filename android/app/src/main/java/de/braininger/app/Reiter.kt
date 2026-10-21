package de.braininger.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.drive.Ergebnis
import de.braininger.app.drive.UploadStand
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss

/**
 * Reiter unten (Grill 20.10.2026, Frage 25; Muster nach Material Design 3 „Navigation bar“, 3–5 Ziele):
 * Heute / Aufnahmen / Aufgaben / Fragen. Jede Seite zeigt nur ihr Thema.
 */
@Composable
fun ReiterLeiste(aktiv: String, waehlen: (String) -> Unit) {
    val reiter = listOf("heute" to "HEUTE", "aufnahmen" to "AUFNAHMEN", "aufgaben" to "AUFGABEN", "fragen" to "FRAGEN")
    Column(Modifier.fillMaxWidth().background(Schwarz)) {
        HorizontalDivider(color = Linie)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            reiter.forEach { (kennung, name) ->
                val an = kennung == aktiv
                Column(
                    Modifier.weight(1f).fillMaxSize().clickable { waehlen(kennung) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // immer fett und etwas größer: die Punktschrift ist dünn sonst schwer zu lesen
                    Text(name, fontFamily = Punktschrift, fontWeight = FontWeight.Bold,
                         fontSize = 14.sp, color = if (an) Weiss else Grau, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    Spacer(Modifier.height(3.dp).fillMaxWidth(0.35f).background(if (an) Weiss else Schwarz))
                }
            }
        }
    }
}

/** Reiter „Aufnahmen“: Suche, Liste der letzten 7 Tage, Archiv und Tagesübersicht. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AufnahmenListe(
    aufnahmen: List<Aufnahme>,
    treffer: List<Pair<Aufnahme, String>>,
    suche: String,
    sucheAendern: (String) -> Unit,
    uploads: Map<String, UploadStand>,
    ergebnisse: Map<String, Ergebnis?>,
    auswahl: Set<String>,
    auswahlAendern: (Set<String>) -> Unit,
    imArchiv: Int,
    imPapierkorb: Int,
    leisteSichtbar: Boolean,
    oeffnen: (String) -> Unit,
    archivOeffnen: () -> Unit,
    uebersichtOeffnen: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Column {
                Spacer(Modifier.height(16.dp))
                Text("AUFNAHMEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Weiss)
                Spacer(Modifier.height(12.dp))
                SeitenKnopf("TAGESÜBERSICHT", Modifier.fillMaxWidth().padding(bottom = 12.dp), uebersichtOeffnen)
                if (aufnahmen.isNotEmpty()) {
                    OutlinedTextField(
                        value = suche, onValueChange = { sucheAendern(it.take(80)) }, singleLine = true,
                        placeholder = { Text("Suchen in allen Notizen und Texten", color = Grau, fontSize = 14.sp) },
                        trailingIcon = if (suche.isNotEmpty()) {
                            { Text("✕", color = Grau, fontSize = 16.sp, modifier = Modifier.clickable { sucheAendern("") }.padding(8.dp)) }
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
                    Text(if (treffer.isEmpty()) "Nichts gefunden." else "${treffer.size} Treffer",
                         color = Grau, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
        items(treffer, key = { it.first.ordner.name }) { (a, ausschnitt) ->
            val id = a.ordner.name
            AufnahmeZeile(
                a, uploads[id], ergebnisse[id],
                ausgewaehlt = id in auswahl,
                lang = if (a.status == "laeuft") null else ({ auswahlAendern(auswahl + id) }),
            ) {
                if (auswahl.isNotEmpty()) {
                    if (a.status != "laeuft") auswahlAendern(if (id in auswahl) auswahl - id else auswahl + id)
                } else {
                    oeffnen(id)
                }
            }
            if (suche.isNotBlank() && ausschnitt.isNotBlank()) {
                Text(Suche.hervorheben(ausschnitt, suche), color = Grau, fontSize = 13.sp,
                     modifier = Modifier.padding(bottom = 10.dp).clickable { oeffnen(id) })
            }
        }
        if (suche.isBlank() && (imArchiv > 0 || imPapierkorb > 0)) {
            item {
                Spacer(Modifier.height(16.dp))
                SeitenKnopf("ARCHIV ($imArchiv)", Modifier.fillMaxWidth(), archivOeffnen)
            }
        }
        item {
            Spacer(Modifier.height(if (auswahl.isNotEmpty() || leisteSichtbar) 90.dp else 0.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", color = Grau, fontSize = 11.sp,
                 modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.Center)
        }
    }
}
