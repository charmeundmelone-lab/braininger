package de.braininger.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Rot
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import java.util.Calendar

private const val SICHTBAR = 4

/** Vorschläge aus den Aufnahmen und die offenen Aufgaben, direkt unter den Aufnahme-Knöpfen. */
@Composable
fun AufgabenBereich() {
    val context = LocalContext.current
    val stand by AufgabenSpeicher.stand.collectAsState()
    val ordnungStand by Ordnung.stand.collectAsState()
    val vorschlaege = remember(stand, ordnungStand) {
        val geloescht = Ordnung.ausgeblendet(context)
        AufgabenSpeicher.vorschlaege(context).filter { it.aufnahme !in geloescht }
    }
    val offene = remember(stand) { AufgabenSpeicher.offene(context) }
    val texte = remember(stand) { AufgabenSpeicher.alle(context).associate { it.id to it.text } }
    var alleZeigen by remember { mutableStateOf(false) }
    var bearbeiten by remember { mutableStateOf<Aufgabe?>(null) }
    var neuDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            AufgabenSicherung.wiederherstellen(context) // nach Neuinstallation aus dem Drive
        } catch (_: Exception) {
        }
    }

    if (vorschlaege.isNotEmpty()) {
        Text("VORSCHLÄGE", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Grau)
        Spacer(Modifier.height(4.dp))
        vorschlaege.forEach { v -> VorschlagKarte(v, v.bezug?.let { texte[it] }) }
        Spacer(Modifier.height(12.dp))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("AUFGABEN", fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Grau,
             modifier = Modifier.weight(1f))
        Text("+", color = Weiss, fontSize = 26.sp, modifier = Modifier.clickable { neuDialog = true }.padding(horizontal = 12.dp))
    }
    if (offene.isEmpty()) {
        Text("Keine offenen Aufgaben.", color = Grau, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
    val sichtbar = if (alleZeigen) offene else offene.take(SICHTBAR)
    sichtbar.forEach { a -> AufgabeZeile(a, { AufgabenSpeicher.abhaken(context, a.id) }) { bearbeiten = a } }
    if (offene.size > SICHTBAR) {
        Text(
            if (alleZeigen) "WENIGER" else "ALLE ${offene.size} ZEIGEN", color = Weiss, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { alleZeigen = !alleZeigen }.padding(vertical = 6.dp),
        )
    }
    Spacer(Modifier.height(16.dp))

    if (neuDialog) NeueAufgabeDialog { neuDialog = false }
    bearbeiten?.let { a -> AufgabeDialog(a) { bearbeiten = null } }
}

@Composable
private fun VorschlagKarte(v: Vorschlag, bezugText: String?) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, Weiss, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        val vorsatz = when (v.art) {
            "erledigt" -> "Erledigt? "
            "ergaenzen" -> "Ergänzen: "
            else -> ""
        }
        Text(vorsatz + (if (v.art == "erledigt") bezugText ?: v.text else v.text), color = Weiss, fontSize = 15.sp)
        val zusatz = listOfNotNull(
            v.frist?.let { "bis " + AufgabenSpeicher.fristText(it) },
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

@Composable
private fun AufgabeZeile(a: Aufgabe, abhaken: () -> Unit, oeffnen: () -> Unit) {
    val ueberfaellig = (a.frist ?: Long.MAX_VALUE) < System.currentTimeMillis()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).border(1.5.dp, Weiss, CircleShape).clickable(onClick = abhaken))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(onClick = oeffnen)) {
            Text(a.text, color = Weiss, fontSize = 15.sp)
            val zusatz = listOfNotNull(a.frist?.let { AufgabenSpeicher.fristText(it) }, "unklar, wer".takeIf { a.unklarWer })
            if (zusatz.isNotEmpty()) Text(zusatz.joinToString(" · "), color = if (ueberfaellig) Rot else Grau, fontSize = 12.sp)
        }
    }
}

/** Datum und dann Uhrzeit wählen (Android-Standarddialoge). */
private fun zeitWaehlen(context: Context, start: Long?, fertig: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = start ?: AufgabenSpeicher.morgenAcht() }
    DatePickerDialog(context, { _, j, m, t ->
        TimePickerDialog(context, { _, h, min ->
            c.set(j, m, t, h, min, 0); c.set(Calendar.MILLISECOND, 0)
            fertig(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
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
                TextButton(onClick = { zeitWaehlen(context, frist) { frist = it } }) {
                    Text(frist?.let { "Frist: " + AufgabenSpeicher.fristText(it) } ?: "+ FRIST (optional)", color = Weiss)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                AufgabenSpeicher.neu(context, text, frist); schliessen()
            }) { Text("HINZUFÜGEN", color = if (text.isNotBlank()) Weiss else Grau, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = schliessen) { Text("ABBRECHEN", color = Grau) } },
    )
}

@Composable
private fun AufgabeDialog(a: Aufgabe, schliessen: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = schliessen,
        containerColor = Schwarz,
        title = { Text(a.text, color = Weiss, fontSize = 17.sp) },
        text = {
            Column {
                Text(a.frist?.let { "Frist: " + AufgabenSpeicher.fristText(it) } ?: "Keine Frist", color = Grau, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { zeitWaehlen(context, a.frist) { AufgabenSpeicher.fristSetzen(context, a.id, it); schliessen() } }) {
                    Text("FRIST ÄNDERN", color = Weiss)
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
