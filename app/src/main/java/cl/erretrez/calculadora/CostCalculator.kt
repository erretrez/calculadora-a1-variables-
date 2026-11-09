package cl.erretrez.calculadora

import java.text.NumberFormat
import java.util.Locale

/** Campos de entrada de la calculadora. */
enum class Field {
    PRECIO_KG,
    GRAMOS,
    PIEZAS,
    HORAS,
    MINUTOS,
    WATTS,
    PRECIO_KWH,
    PRECIO_IMPRESORA,
    VIDA_UTIL,
    MANTENIMIENTO
}

/** Valores ingresados por el usuario. null = campo vacío o no numérico. */
data class CostInputs(
    val precioKg: Double?,
    val gramos: Double?,
    val piezas: Double?,
    val horas: Double?,
    val minutos: Double?,
    val watts: Double?,
    val precioKwh: Double?,
    val precioImpresora: Double?,
    val vidaUtilHoras: Double?,
    val mantenimientoPorHora: Double?
)

/** Desglose de costos. Las unidades monetarias son las que use el usuario. */
data class CostBreakdown(
    val horasTotales: Double,
    val material: Double,
    val electricidad: Double,
    val depreciacion: Double,
    val mantenimiento: Double,
    val total: Double,
    val unitario: Double
)

sealed class Outcome {
    data class Success(val breakdown: CostBreakdown) : Outcome()
    data class Failure(
        val fieldErrors: Map<Field, String>,
        val general: String? = null
    ) : Outcome()
}

object CostCalculator {

    const val MSG_REQUIRED = "Ingresa un número válido"
    const val MSG_NEGATIVE = "No puede ser negativo"
    const val MSG_POSITIVE = "Debe ser mayor que 0"
    const val MSG_INTEGER = "Debe ser un número entero"
    const val MSG_MINUTES = "Los minutos deben estar entre 0 y 59"
    const val MSG_TIME_REQUIRED = "Ingresa horas o minutos"
    const val MSG_TIME_POSITIVE = "El tiempo de impresión debe ser mayor que 0"
    const val MSG_OVERFLOW = "Los valores ingresados producen un resultado fuera de rango"

    private val NUMBER_FORMAT = Regex("^-?([0-9]+([.][0-9]*)?|[.][0-9]+)$")

    /**
     * Convierte texto en número. Acepta punto o coma como separador decimal.
     * Devuelve null si el texto está vacío, no es un número o no es finito.
     */
    fun parseNumber(text: String?): Double? {
        val clean = text?.trim()?.replace(',', '.') ?: return null
        if (clean.isEmpty()) return null
        // Solo formato decimal simple (rechaza "1e5", "12f", "NaN", "0x1p3", etc.).
        if (!NUMBER_FORMAT.matches(clean)) return null
        val value = clean.toDoubleOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    fun calculate(i: CostInputs): Outcome {
        val errors = LinkedHashMap<Field, String>()

        fun nonNegative(field: Field, v: Double?) {
            when {
                v == null -> errors[field] = MSG_REQUIRED
                v < 0.0 -> errors[field] = MSG_NEGATIVE
            }
        }

        nonNegative(Field.PRECIO_KG, i.precioKg)
        nonNegative(Field.GRAMOS, i.gramos)
        nonNegative(Field.WATTS, i.watts)
        nonNegative(Field.PRECIO_KWH, i.precioKwh)
        nonNegative(Field.PRECIO_IMPRESORA, i.precioImpresora)
        nonNegative(Field.MANTENIMIENTO, i.mantenimientoPorHora)

        // Piezas: entero mayor que 0 (evita división por cero).
        val piezas = i.piezas
        when {
            piezas == null -> errors[Field.PIEZAS] = MSG_REQUIRED
            piezas <= 0.0 -> errors[Field.PIEZAS] = MSG_POSITIVE
            piezas != Math.floor(piezas) -> errors[Field.PIEZAS] = MSG_INTEGER
        }

        // Vida útil: mayor que 0 (evita división por cero).
        val vida = i.vidaUtilHoras
        when {
            vida == null -> errors[Field.VIDA_UTIL] = MSG_REQUIRED
            vida <= 0.0 -> errors[Field.VIDA_UTIL] = MSG_POSITIVE
        }

        // Tiempo: horas y/o minutos. Un campo vacío cuenta como 0 si el otro tiene valor.
        val horas = i.horas
        val minutos = i.minutos
        if (horas == null && minutos == null) {
            errors[Field.HORAS] = MSG_TIME_REQUIRED
            errors[Field.MINUTOS] = MSG_TIME_REQUIRED
        } else {
            if (horas != null && horas < 0.0) errors[Field.HORAS] = MSG_NEGATIVE
            if (minutos != null && (minutos < 0.0 || minutos >= 60.0)) {
                errors[Field.MINUTOS] = MSG_MINUTES
            }
            if (!errors.containsKey(Field.HORAS) && !errors.containsKey(Field.MINUTOS)) {
                val total = (horas ?: 0.0) + (minutos ?: 0.0) / 60.0
                if (total <= 0.0) {
                    errors[Field.HORAS] = MSG_TIME_POSITIVE
                    errors[Field.MINUTOS] = MSG_TIME_POSITIVE
                }
            }
        }

        if (errors.isNotEmpty()) return Outcome.Failure(errors)

        // A partir de aquí todos los valores son no nulos y válidos.
        val horasTotales = (horas ?: 0.0) + (minutos ?: 0.0) / 60.0

        val material = (i.precioKg!! / 1000.0) * i.gramos!!
        val electricidad = (i.watts!! / 1000.0) * horasTotales * i.precioKwh!!
        val depreciacion = (i.precioImpresora!! / vida!!) * horasTotales
        val mantenimiento = i.mantenimientoPorHora!! * horasTotales
        val total = material + electricidad + depreciacion + mantenimiento
        val unitario = total / piezas!!

        val all = doubleArrayOf(material, electricidad, depreciacion, mantenimiento, total, unitario)
        if (all.any { !it.isFinite() }) {
            return Outcome.Failure(emptyMap(), MSG_OVERFLOW)
        }

        return Outcome.Success(
            CostBreakdown(horasTotales, material, electricidad, depreciacion, mantenimiento, total, unitario)
        )
    }

    /** Formato de pesos chilenos (CLP): sin decimales y con punto como separador de miles. */
    fun formatClp(value: Double): String {
        val nf = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
        nf.maximumFractionDigits = 0
        nf.minimumFractionDigits = 0
        return nf.format(value)
    }
}
