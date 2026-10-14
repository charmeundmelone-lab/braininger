package de.braininger.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.braininger.app.drive.AnmeldungNoetig
import de.braininger.app.drive.Auftraege
import de.braininger.app.drive.Ergebnis
import de.braininger.app.drive.Notiz
import de.braininger.app.drive.Vorlagen
import de.braininger.app.ui.Grau
import de.braininger.app.ui.Linie
import de.braininger.app.ui.Punktschrift
import de.braininger.app.ui.Rot
import de.braininger.app.ui.Schwarz
import de.braininger.app.ui.Weiss
import kotlinx.coroutines.launch
import java.util.Locale

/** Notizen und Text einer Aufnahme. Vorlage einer Notiz per Tipp ändern. */
@Composable
fun ErgebnisSeite(aufnahme: Aufnahme, ergebnis: Ergebnis?, aktualisierung: Int, zurueck: () -> Unit) {
    BackHandler(onBack = zurueck)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var zeigeText by remember { mutableStateOf(false) }
    var vorlageWahl by remember { mutableStateOf<Int?>(null) }
    var meldung by remember { mutableStateOf<String?>(null) }
    val offenerAuftrag = remember(aktualisierung, ergebnis) { Auftraege.offen(aufnahme.ordner) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Schwarz)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = zurueck) { Text("‹ ZURÜCK", color = Weiss, fontWeight = FontWeight.Bold) }
        }
        Text(aufnahme.modus.anzeige.uppercase(), fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Weiss)
        Text(
            "${datumText(aufnahme.start)} · ${dauerKurz(aufnahme.dauerMs)} · ${aufnahme.mikrofon}",
            color = Grau, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        when {
            ergebnis == null -> Text("Noch kein Ergebnis vom Laptop.", color = Grau, fontSize = 14.sp)
            !ergebnis.endgueltig -> Text(
                ergebnis.anzeige + (ergebnis.meldung?.let { " · $it" } ?: ""),
                color = Grau, fontSize = 14.sp,
            )
            ergebnis.status == "fehler" -> Text("Fehler am Laptop: ${ergebnis.meldung ?: "unbekannt"}", color = Rot, fontSize = 14.sp)
        }
        if (offenerAuftrag != null) {
            Text("Laptop schreibt die Notiz mit neuer Vorlage …", color = Grau, fontSize = 13.sp)
        }
        meldung?.let { Text(it, color = Rot, fontSize = 13.sp) }

        if (ergebnis != null && (ergebnis.notizen.isNotEmpty() || ergebnis.abschnitte.isNotEmpty())) {
            Row(Modifier.padding(vertical = 8.dp)) {
                Reiter("NOTIZEN", !zeigeText) { zeigeText = false }
                Spacer(Modifier.width(8.dp))
                Reiter("TEXT", zeigeText) { zeigeText = true }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (!zeigeText) {
                    items(ergebnis.notizen.withIndex().toList(), key = { it.index }) { (i, n) ->
                        NotizKarte(n, aenderbar = offenerAuftrag == null) { vorlageWahl = i }
                    }
                } else {
                    items(ergebnis.abschnitte) { a ->
                        val wer = a.sprecher?.let { ergebnis.sprecher[it] ?: it }
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Grau)) { append(zeitText(a.start) + "  ") }
                                if (wer != null) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$wer: ") }
                                append(a.text)
                            },
                            color = Weiss, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    vorlageWahl?.let { index ->
        AlertDialog(
            onDismissRequest = { vorlageWahl = null },
            title = { Text("Andere Vorlage") },
            text = {
                Column {
                    Vorlagen.alle.forEach { (kennung, name) ->
                        Text(
                            name, color = Weiss, fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vorlageWahl = null
                                    meldung = null
                                    scope.launch {
                                        try {
                                            Auftraege.vorlageAendern(context, aufnahme.ordner, index, kennung)
                                        } catch (e: AnmeldungNoetig) {
                                            meldung = "Erst bei Google anmelden."
                                        } catch (e: Exception) {
                                            meldung = "Auftrag nicht gesendet: ${e.message}"
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vorlageWahl = null }) { Text("Abbrechen") } },
            containerColor = androidx.compose.ui.graphics.Color(0xFF111111),
        )
    }
}

@Composable
private fun Reiter(text: String, aktiv: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        color = if (aktiv) Schwarz else Grau,
        modifier = Modifier
            .background(if (aktiv) Weiss else Schwarz, RoundedCornerShape(16.dp))
            .border(1.dp, if (aktiv) Weiss else Linie, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun NotizKarte(n: Notiz, aenderbar: Boolean, vorlageAendern: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, Linie, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Vorlagen.name(n.vorlage).uppercase(), color = Grau, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
            if (aenderbar) {
                Text(
                    "ÄNDERN", color = Weiss, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = vorlageAendern).padding(6.dp),
                )
            }
        }
        Text(n.titel, color = Weiss, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Markdown(n.inhalt)
    }
}

/** Sehr einfache Markdown-Darstellung: Überschriften, Listen, Checkboxen, **fett**. */
@Composable
private fun Markdown(text: String) {
    for (roh in text.lines()) {
        val zeile = roh.trimEnd()
        when {
            zeile.isBlank() -> Spacer(Modifier.height(6.dp))
            zeile.startsWith("#") -> {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Linie)
                Text(
                    zeile.trimStart('#', ' ').uppercase(Locale.GERMANY),
                    fontFamily = Punktschrift, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Grau,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
            else -> {
                val einrueckung = (zeile.length - zeile.trimStart().length) / 2
                val inhalt = zeile.trimStart()
                val (zeichen, rest) = when {
                    inhalt.startsWith("- [ ] ") -> "☐ " to inhalt.drop(6)
                    inhalt.startsWith("- [x] ", ignoreCase = true) -> "☑ " to inhalt.drop(6)
                    inhalt.startsWith("- ") || inhalt.startsWith("* ") -> "• " to inhalt.drop(2)
                    else -> "" to inhalt
                }
                Text(
                    fett(zeichen + rest), color = Weiss, fontSize = 15.sp,
                    modifier = Modifier.padding(start = (einrueckung * 14).dp, top = 1.dp, bottom = 1.dp),
                )
            }
        }
    }
}

private fun fett(text: String) = buildAnnotatedString {
    val teile = text.split("**")
    teile.forEachIndexed { i, t ->
        if (i % 2 == 1 && i < teile.size - 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t) } else append(t)
    }
}

private fun zeitText(s: Double): String {
    val sek = s.toInt()
    return String.format(Locale.ROOT, "%02d:%02d", sek / 60, sek % 60)
}

private fun dauerKurz(ms: Long): String {
    val min = ms / 60000
    return if (min < 1) "${ms / 1000} s" else "$min min"
}

fun datumText(ms: Long): String =
    java.text.SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMANY).format(java.util.Date(ms))
