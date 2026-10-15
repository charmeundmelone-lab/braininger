package de.braininger.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AufnahmeZustand(
    val laeuft: Boolean = false,
    val modus: Modus? = null,
    val start: Long = 0,
    val stueckeFertig: Int = 0,
    val mikrofon: String = "",
    val mikrofonExtern: Boolean = false,
    val warnung: String? = null,
    val ordner: String? = null, // für den Foto-Knopf in der App
    val markierungen: Int = 0,
    val fotos: Int = 0,
)

/** Gemeinsamer Zustand zwischen Aufnahme-Dienst, App-Oberfläche und Widget. */
object AufnahmeStatus {
    private val _zustand = MutableStateFlow(AufnahmeZustand())
    val zustand: StateFlow<AufnahmeZustand> = _zustand.asStateFlow()

    fun setzen(neu: AufnahmeZustand) { _zustand.value = neu }
    fun aendern(f: (AufnahmeZustand) -> AufnahmeZustand) = _zustand.update(f)
}
