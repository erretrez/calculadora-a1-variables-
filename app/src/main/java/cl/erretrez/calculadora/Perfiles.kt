package cl.erretrez.calculadora

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Perfil de filamento (v2.2, opcional): un carrete con nombre editable que acumula los gramos consumidos.
 * [capacidadG] es el peso del carrete (1 kg por defecto) y [usadoG] lo ya consumido (se puede ingresar a mano
 * para carretes empezados).
 */
data class Perfil(val id: String, val nombre: String, val capacidadG: Double = Perfiles.KILO_G, val usadoG: Double = 0.0)

/** Cómo queda un perfil si se suma un consumo. */
enum class EstadoPerfil { OK, CASI, COMPLETO, SOBREPASA }

/** Resultado de proyectar un consumo sobre un perfil. */
data class Proyeccion(
    val perfil: Perfil,
    val consumoG: Double,
    val totalG: Double,
    val pct: Double,
    val estado: EstadoPerfil
) {
    /** Gramos que faltan para completar el carrete (negativo si se sobrepasa). */
    val restanteG: Double get() = perfil.capacidadG - totalG
}

object Perfiles {
    const val KILO_G = 1000.0

    /** Desde este porcentaje se avisa que el carrete se está completando. */
    const val UMBRAL_AVISO_PCT = 80.0

    /** Porcentaje de un kilo que representan [gramos] (1000 g = 100%). */
    fun porcentajeDelKilo(gramos: Double): Double = gramos / KILO_G * 100.0

    /** "15%", "15.5%", "0.3%": un decimal como máximo, sin ceros sobrantes. */
    fun textoPct(pct: Double): String =
        BigDecimal(pct).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"

    fun estado(totalG: Double, capacidadG: Double): EstadoPerfil {
        if (capacidadG <= 0.0) return EstadoPerfil.OK
        val pct = totalG / capacidadG * 100.0
        return when {
            totalG > capacidadG + EPS -> EstadoPerfil.SOBREPASA
            totalG >= capacidadG - EPS -> EstadoPerfil.COMPLETO
            pct >= UMBRAL_AVISO_PCT -> EstadoPerfil.CASI
            else -> EstadoPerfil.OK
        }
    }

    private const val EPS = 1e-9

    /** Proyecta sumar [consumoG] al consumo ya acumulado del perfil. */
    fun proyectar(perfil: Perfil, consumoG: Double): Proyeccion {
        val total = perfil.usadoG + consumoG
        val pct = if (perfil.capacidadG > 0.0) total / perfil.capacidadG * 100.0 else 0.0
        return Proyeccion(perfil, consumoG, total, pct, estado(total, perfil.capacidadG))
    }

    /** Gramos de este cálculo por perfil elegido (las piezas sin perfil no cuentan). */
    fun consumosPorPerfil(piezas: List<Parte>): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        for (p in piezas) {
            val id = p.perfil ?: continue
            m[id] = (m[id] ?: 0.0) + (p.gramos ?: 0.0)
        }
        return m
    }

    /** Suma los consumos a los perfiles que existan; los ids desconocidos se ignoran. */
    fun registrar(lista: List<Perfil>, consumos: Map<String, Double>): List<Perfil> =
        lista.map { p -> consumos[p.id]?.let { p.copy(usadoG = p.usadoG + it) } ?: p }

    /** Id nuevo, distinto de los existentes: "f1", "f2"… */
    fun nuevoId(lista: List<Perfil>): String {
        val max = lista.mapNotNull { it.id.removePrefix("f").toIntOrNull() }.maxOrNull() ?: 0
        return "f" + (max + 1)
    }

    /** Nombre sin saltos de línea ni tabulaciones (así se guarda en una línea). */
    fun limpiarNombre(nombre: String?): String = (nombre ?: "").replace(Regex("[\\t\\r\\n]+"), " ").trim().take(40)

    fun nombreOPorDefecto(nombre: String?, lista: List<Perfil>): String =
        limpiarNombre(nombre).ifEmpty { "Filamento " + (lista.size + 1) }

    /** Texto para guardar en las preferencias: una línea por perfil, campos separados por tabulación. */
    fun serializar(lista: List<Perfil>): String = lista.joinToString("\n") {
        listOf(it.id, limpiarNombre(it.nombre), it.capacidadG.toString(), it.usadoG.toString()).joinToString("\t")
    }

    fun deserializar(texto: String?): List<Perfil> {
        if (texto.isNullOrBlank()) return emptyList()
        return texto.lines().mapNotNull { linea ->
            val c = linea.split("\t")
            if (c.size < 4) return@mapNotNull null
            val cap = c[2].toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() } ?: return@mapNotNull null
            val usado = c[3].toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() } ?: return@mapNotNull null
            if (c[0].isBlank()) return@mapNotNull null
            Perfil(c[0], c[1], cap, usado)
        }
    }

    /** Texto de una proyección para mostrar en pantalla. */
    fun mensaje(p: Proyeccion): String {
        val g = Suggested::plainRounded
        val base = "«" + p.perfil.nombre + "»: " + g(p.perfil.usadoG) + " g usados + " + g(p.consumoG) +
            " g de este cálculo = " + g(p.totalG) + " de " + g(p.perfil.capacidadG) + " g (" + textoPct(p.pct) + ")"
        return base + when (p.estado) {
            EstadoPerfil.OK -> ""
            EstadoPerfil.CASI -> " · el carrete se está terminando (quedan " + g(p.restanteG) + " g)"
            EstadoPerfil.COMPLETO -> " · con esto el carrete queda completo"
            EstadoPerfil.SOBREPASA -> " · SOBREPASA el carrete por " + g(-p.restanteG) + " g"
        }
    }

    /** Estado de un perfil cuyo consumo ya se registró (sin sumar nada más). */
    fun mensajeRegistrado(p: Perfil): String {
        val pr = proyectar(p, 0.0)
        val g = Suggested::plainRounded
        return "Registrado · «" + p.nombre + "»: " + g(p.usadoG) + " de " + g(p.capacidadG) + " g (" + textoPct(pr.pct) + ")" +
            when (pr.estado) {
                EstadoPerfil.OK -> ""
                EstadoPerfil.CASI -> " · el carrete se está terminando (quedan " + g(pr.restanteG) + " g)"
                EstadoPerfil.COMPLETO -> " · el carrete está completo"
                EstadoPerfil.SOBREPASA -> " · SOBREPASA el carrete por " + g(-pr.restanteG) + " g"
            }
    }
}
