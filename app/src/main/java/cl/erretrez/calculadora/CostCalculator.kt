package cl.erretrez.calculadora

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Campos de entrada de la calculadora. Los marcados como opcionales pueden quedar vacíos. */
enum class Field(val optional: Boolean = false) {
    PRECIO_KG,
    GRAMOS,
    PIEZAS,
    HORAS,
    MINUTOS,
    WATTS,
    PRECIO_KWH,
    PRECIO_IMPRESORA,
    VIDA_UTIL,
    MANTENIMIENTO,

    // Opcionales (v1.2)
    HORAS_TRABAJO(true),
    COSTO_HORA_TRABAJO(true),
    MERMA(true),
    MARGEN(true)
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
    // Desde la v1.3 la app no pide estos tres datos: usa los valores fijos de `Suggested`
    // y solo los muestra en el resumen. El motor sigue validándolos si se entregan otros.
    val precioImpresora: Double? = Suggested.PRECIO_IMPRESORA,
    val vidaUtilHoras: Double? = Suggested.VIDA_UTIL_HORAS,
    val mantenimientoPorHora: Double? = Suggested.MANTENIMIENTO_POR_HORA,
    // Opcionales (v1.2). Vacío = no aplica.
    val horasTrabajo: Double? = null,
    val costoHoraTrabajo: Double? = null,
    val mermaPct: Double? = null,
    val margenPct: Double? = null,
    val incluirIva: Boolean = false,
    /** Campos opcionales con texto escrito pero que no es un número válido. */
    val invalidOptional: Set<Field> = emptySet()
)

/** Desglose de costos. Las unidades monetarias son las que use el usuario. */
data class CostBreakdown(
    val horasTotales: Double,
    val material: Double,
    val electricidad: Double,
    val depreciacion: Double,
    val mantenimiento: Double,
    val total: Double,
    val unitario: Double,
    val manoObra: Double = 0.0,
    val merma: Double = 0.0,
    /** Precio de venta unitario sin IVA (costo unitario + margen). null si no se indicó margen. */
    val precioVenta: Double? = null,
    /** Precio de venta unitario con IVA. null si no se indicó margen o no se pidió IVA. */
    val precioConIva: Double? = null,
    /** Ganancia estimada del lote completo, sin IVA. null si no se indicó margen. */
    val gananciaLote: Double? = null
)

sealed class Outcome {
    data class Success(val breakdown: CostBreakdown) : Outcome()
    data class Failure(
        val fieldErrors: Map<Field, String>,
        val general: String? = null
    ) : Outcome()
}

/**
 * Consumo eléctrico de referencia de la Bambu Lab A1 Combo (ver README, sección Fuentes).
 * El pico de calentamiento (hasta 1.300 W a 220 V según la hoja técnica) dura poco y NO es
 * un consumo promedio, por eso no se ofrece como valor.
 */
object ImpresoraA1 {
    /** Límite inferior del rango estándar: promedios de 70–80 W medidos por usuarios en PLA. */
    const val WATTS_ESTANDAR_MIN = 70.0

    /** Promedio en PLA publicado por Bambu Lab (wiki, 220 V): 95 W. */
    const val WATTS_ESTANDAR = 95.0

    /** Consumo alto: unos 200 W en ABS / materiales de alta temperatura (mediciones de usuarios). */
    const val WATTS_ALTO = 200.0
}

/**
 * Valores fijos de la impresora: se suman automáticamente al costo (depreciación y
 * mantenimiento) y solo aparecen en el resumen; no se editan en pantalla.
 */
object Suggested {
    const val PRECIO_IMPRESORA = 450000.0
    const val VIDA_UTIL_HORAS = 5000.0
    const val MANTENIMIENTO_POR_HORA = 100.0

    /** Texto plano numérico (sin separador de miles) para copiar a un campo editable. */
    fun plain(value: Double): String =
        if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    /** Texto plano redondeado a 2 decimales, sin ceros sobrantes ("150", "151.67"). */
    fun plainRounded(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Número con separador de miles chileno (punto), sin decimales. */
    fun grouped(value: Double): String {
        // Determinista: algunas versiones de ICU/CLDR no agrupan números de 4 dígitos en español.
        val symbols = DecimalFormatSymbols(Locale.forLanguageTag("es-CL"))
        symbols.groupingSeparator = '.'
        return DecimalFormat("#,##0", symbols).format(value)
    }
}

/** Resultado de estimar el precio del kWh a partir de una boleta de luz. */
data class BoletaResult(val precioKwh: Double?, val error: String?)

object Boleta {
    const val MSG_MONTO_NEGATIVO = "El monto no puede ser negativo"
    const val MSG_KWH_POSITIVO = "Los kWh deben ser mayores que 0"
    const val MSG_FUERA_DE_RANGO = "Los valores producen un resultado fuera de rango"

    /**
     * Precio efectivo del kWh = monto total de la boleta / kWh consumidos.
     * Incluye cargos fijos e impuestos de la boleta, por lo que es un precio efectivo.
     * Devuelve (null, null) mientras falte algún dato.
     */
    fun precioKwh(monto: Double?, kwh: Double?): BoletaResult {
        if (monto == null || kwh == null) return BoletaResult(null, null)
        if (monto < 0.0) return BoletaResult(null, MSG_MONTO_NEGATIVO)
        if (kwh <= 0.0) return BoletaResult(null, MSG_KWH_POSITIVO)
        val price = monto / kwh
        return if (price.isFinite()) BoletaResult(price, null) else BoletaResult(null, MSG_FUERA_DE_RANGO)
    }
}

/**
 * Región de Chile con la ubicación de su capital regional (para ubicar la región más cercana
 * sin conexión) y, si se tiene, un precio referencial del kWh.
 */
data class Region(
    val nombre: String,
    val lat: Double,
    val lon: Double,
    val referenciaKwh: Double?
)

object Regiones {
    /** Fecha de los valores referenciales. NO son oficiales y se desactualizan: ver boleta. */
    const val REFERENCIA_FECHA = "abril 2026"

    /** Más lejos que esto de cualquier capital regional se considera fuera de Chile. */
    const val MAX_KM = 700.0

    // Valores referenciales tomados de cuantomecuesta.com (abril 2026, sitio no oficial),
    // anteriores al alza de julio de 2026. Solo hay dato para 8 regiones; el resto es null
    // a propósito (la app pide usar la boleta) en vez de inventar un valor.
    val todas: List<Region> = listOf(
        Region("Arica y Parinacota", -18.4783, -70.3126, null),
        Region("Tarapacá", -20.2307, -70.1357, null),
        Region("Antofagasta", -23.6509, -70.3975, 170.0),
        Region("Atacama", -27.3668, -70.3314, 165.0),
        Region("Coquimbo", -29.9027, -71.2519, null),
        Region("Valparaíso", -33.0472, -71.6127, 155.0),
        Region("Metropolitana de Santiago", -33.4489, -70.6693, 160.0),
        Region("O'Higgins", -34.1708, -70.7444, null),
        Region("Maule", -35.4264, -71.6554, null),
        Region("Ñuble", -36.6063, -72.1034, null),
        Region("Biobío", -36.8201, -73.0444, 150.0),
        Region("La Araucanía", -38.7359, -72.5904, 145.0),
        Region("Los Ríos", -39.8142, -73.2459, null),
        Region("Los Lagos", -41.4693, -72.9424, 148.0),
        Region("Aysén", -45.5752, -72.0662, null),
        Region("Magallanes y la Antártica Chilena", -53.1638, -70.9171, 140.0)
    )

    fun distanciaKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * Región cuya capital está más cerca de la coordenada (aproximación: no usa límites
     * regionales). Devuelve null si la coordenada es inválida o queda fuera de Chile.
     */
    fun masCercana(lat: Double, lon: Double): Region? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat < -56.0 || lat > -17.0 || lon < -76.0 || lon > -66.0) return null
        val best = todas.minByOrNull { distanciaKm(lat, lon, it.lat, it.lon) } ?: return null
        return if (distanciaKm(lat, lon, best.lat, best.lon) <= MAX_KM) best else null
    }
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
    const val MSG_PAIR = "Completa también el otro dato de mano de obra"
    const val MSG_PCT = "Debe ser un porcentaje menor que 100"

    /** Tasa de IVA vigente en Chile. */
    const val IVA_RATE = 0.19

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

        // Opcionales (v1.2): vacío = no aplica; si se escribe algo debe ser válido.
        for (f in i.invalidOptional) errors[f] = MSG_REQUIRED

        fun optionalNonNegative(field: Field, v: Double?) {
            if (v != null && v < 0.0) errors[field] = MSG_NEGATIVE
        }

        val horasTrabajo = i.horasTrabajo
        val costoHora = i.costoHoraTrabajo
        optionalNonNegative(Field.HORAS_TRABAJO, horasTrabajo)
        optionalNonNegative(Field.COSTO_HORA_TRABAJO, costoHora)
        // La mano de obra necesita ambos datos: horas y costo por hora.
        if (horasTrabajo != null && costoHora == null && !errors.containsKey(Field.COSTO_HORA_TRABAJO)) {
            errors[Field.COSTO_HORA_TRABAJO] = MSG_PAIR
        }
        if (costoHora != null && horasTrabajo == null && !errors.containsKey(Field.HORAS_TRABAJO)) {
            errors[Field.HORAS_TRABAJO] = MSG_PAIR
        }

        val merma = i.mermaPct
        if (merma != null && (merma < 0.0 || merma >= 100.0)) errors[Field.MERMA] = MSG_PCT
        optionalNonNegative(Field.MARGEN, i.margenPct)

        if (errors.isNotEmpty()) return Outcome.Failure(errors)

        // A partir de aquí todos los valores requeridos son no nulos y válidos.
        val n = piezas!!
        val horasTotales = (horas ?: 0.0) + (minutos ?: 0.0) / 60.0

        val material = (i.precioKg!! / 1000.0) * i.gramos!!
        val electricidad = (i.watts!! / 1000.0) * horasTotales * i.precioKwh!!
        val depreciacion = (i.precioImpresora!! / vida!!) * horasTotales
        val mantenimiento = i.mantenimientoPorHora!! * horasTotales
        val base = material + electricidad + depreciacion + mantenimiento

        // Extras: sin datos valen 0 y el total es idéntico al cálculo base.
        val manoObra = (horasTrabajo ?: 0.0) * (costoHora ?: 0.0)
        val costoMerma = base * ((merma ?: 0.0) / 100.0)

        val total = base + manoObra + costoMerma
        val unitario = total / n

        val margen = i.margenPct
        val precioVenta = if (margen != null) unitario * (1.0 + margen / 100.0) else null
        val precioConIva = if (i.incluirIva && precioVenta != null) precioVenta * (1.0 + IVA_RATE) else null
        val gananciaLote = if (precioVenta != null) (precioVenta - unitario) * n else null

        val all = listOfNotNull(
            material, electricidad, depreciacion, mantenimiento, manoObra, costoMerma,
            total, unitario, precioVenta, precioConIva, gananciaLote
        )
        if (all.any { !it.isFinite() }) {
            return Outcome.Failure(emptyMap(), MSG_OVERFLOW)
        }

        return Outcome.Success(
            CostBreakdown(
                horasTotales = horasTotales,
                material = material,
                electricidad = electricidad,
                depreciacion = depreciacion,
                mantenimiento = mantenimiento,
                total = total,
                unitario = unitario,
                manoObra = manoObra,
                merma = costoMerma,
                precioVenta = precioVenta,
                precioConIva = precioConIva,
                gananciaLote = gananciaLote
            )
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
