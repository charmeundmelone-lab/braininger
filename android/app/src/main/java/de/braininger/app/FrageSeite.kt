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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import de.braininger.app.drive.Antwort
import de.braininger.app.drive.Fragen
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Rot
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Frage an alle Aufnahmen stellen. Der Laptop beantwortet sie mit Claude, sobald er an ist. */
@Composable
fun FrageSeite(zurueck: () -> Unit, aufnahmeOeffnen: (String) -> Unit) {
    BackHandler(onBack = zurueck)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var eingabe by remember { mutableStateOf("") }
    var aktuell by remember { mutableStateOf(Fragen.aktuell(context)) }
    var verlauf by remember { mutableStateOf(Fragen.verlauf(context)) }
    var meldung by remember { mutableStateOf<String?>(null) }
    val wartet = aktuell?.fertig == false

    LaunchedEffect(wartet) {
        while (aktuell?.fertig == false) {
            try {
                Fragen.abrufen(context)
            } catch (_: AnmeldungNoetig) {
                meldung = "Erst bei Google anmelden."
            } catch (_: Exception) {
                // kein Netz: gleich noch mal
            }
            aktuell = Fragen.aktuell(context)
            verlauf = Fragen.verlauf(context)
            delay(10_000)
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
        Text("FRAGEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Weiss)
        Text("Claude antwortet aus allen deinen Aufnahmen, sobald der Laptop an ist.", color = Grau, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = eingabe, onValueChange = { eingabe = it.take(500) },
            placeholder = { Text("z. B. Was habe ich mit Sibylle ausgemacht?", color = Grau, fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Weiss, unfocusedTextColor = Weiss,
                focusedBorderColor = Weiss, unfocusedBorderColor = Linie, cursorColor = Weiss,
            ),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val frage = eingabe.trim()
                meldung = null
                scope.launch {
                    try {
                        Fragen.stellen(context, frage)
                        eingabe = ""
                        aktuell = Fragen.aktuell(context)
                    } catch (_: AnmeldungNoetig) {
                        meldung = "Erst bei Google anmelden."
                    } catch (e: Exception) {
                        meldung = "Frage nicht gesendet: ${e.message}"
                    }
                }
            },
            enabled = !wartet && eingabe.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            border = BorderStroke(1.5.dp, if (!wartet && eingabe.isNotBlank()) Weiss else Linie),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Weiss),
        ) {
            Text(if (wartet) "LAPTOP DENKT NACH …" else "FRAGEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        meldung?.let { Text(it, color = Rot, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f)) {
            aktuell?.takeIf { !it.fertig }?.let { a ->
                item {
                    AntwortKarte(a, aufnahmeOeffnen)
                }
            }
            items(verlauf, key = { it.nummer }) { a -> AntwortKarte(a, aufnahmeOeffnen) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AntwortKarte(a: Antwort, aufnahmeOeffnen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, Linie, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(a.frage, color = Weiss, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        when (a.status) {
            "fertig" -> Markdown(a.antwort ?: "")
            "fehler" -> Text(a.meldung ?: "Fehler am Laptop.", color = Rot, fontSize = 14.sp)
            "wartet" -> Text(a.meldung ?: "wartet", color = Grau, fontSize = 14.sp)
            "in_arbeit" -> Text("Claude liest deine Aufnahmen …", color = Grau, fontSize = 14.sp)
            else -> Text("Gesendet. Der Laptop antwortet, sobald er an ist.", color = Grau, fontSize = 14.sp)
        }
        if (a.quellen.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("QUELLEN", color = Grau, fontSize = 11.sp, letterSpacing = 1.5.sp)
            a.quellen.forEach { q ->
                Text(
                    "› ${q.datum}" + (q.kurz?.let { " · $it" } ?: ""), color = Weiss, fontSize = 13.sp, maxLines = 2,
                    modifier = Modifier.fillMaxWidth().clickable { aufnahmeOeffnen(q.id) }.padding(vertical = 4.dp),
                )
            }
        }
    }
}
