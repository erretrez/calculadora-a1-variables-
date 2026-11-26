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
    MARGEN(true),

    // v1.5
    OTROS_PIEZA(true),

    // v2.2: tiempo global (una sola hora para toda la impresión)
    HORAS_GLOBAL,
    MINUTOS_GLOBAL,

    // v2.3: cobro por hora de impresión
    TARIFA_HORA(true),

    // v2.5: parámetros de la máquina editables (vacío = valor propuesto)
    DEPRECIACION_HORA(true),
    MANTENIMIENTO_HORA(true)
}

/**
 * Una pieza del producto: lo que se imprime en un ciclo de la máquina (una cama). Un objeto más grande
 * que la cama se parte en varias piezas; cada una tiene su filamento, sus gramos, su tiempo y su consumo.
 * Los gramos y el tiempo son los de esa impresión completa (la cama entera), tal como los entrega el laminador
 * (p. ej. Bambu Studio). La cantidad de unidades NO los multiplica: solo reparte el costo total entre las figuras.
 */
data class Parte(
    val precioKg: Double?,
    val gramos: Double?,
    val horas: Double?,
    val minutos: Double?,
    val watts: Double?,
    /** Id del perfil de filamento elegido (solo para llevar el consumo; no cambia el costo). */
    val perfil: String? = null
)

/** Costo de una pieza, o sea de una impresión completa (sin mano de obra, fallidas ni otros: esos son del trabajo completo). */
data class ParteDetalle(
    val gramos: Double,
    val horas: Double,
    val material: Double,
    val electricidad: Double,
    val depreciacion: Double,
    val mantenimiento: Double,
    /** Cobro por hora de esta pieza (tarifa x horas). Con tarifa activa reemplaza depreciación y mantenimiento. */
    val cobroHoras: Double = 0.0,
    /** Depreciación + mantenimiento reales de esta pieza (piso interno), aunque la tarifa los reemplace. */
    val costoOperativo: Double = 0.0
) {
    val subtotal: Double get() = material + electricidad + depreciacion + mantenimiento + cobroHoras

    /** Porcentaje de un kilo de filamento que representan los gramos de esta pieza. */
    val pctKilo: Double get() = Perfiles.porcentajeDelKilo(gramos)
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
    /**
     * Cobro por hora de impresión (v2.3, todo incluido desde la v2.5): precio de una hora de máquina, independiente
     * del filamento. Se multiplica por las horas totales y REEMPLAZA la depreciación y el mantenimiento (no se
     * cobran dos veces). La electricidad sigue aparte, con sus watts y su precio del kWh editables, porque sube
     * por razones ajenas. Entra en el costo unitario, el margen y el IVA. Una unidad fallida se costea con el
     * costo operativo real, no con la tarifa. Vacío o 0 = no aplica.
     */
    val tarifaHora: Double? = null,
    /** Depreciación por hora de máquina (v2.5). null = precio de la impresora / vida útil. */
    val depreciacionHora: Double? = null,
    /** Mantenimiento por hora de máquina (v2.5). null = el valor propuesto de [mantenimientoPorHora]. */
    val mantenimientoHoraManual: Double? = null,
    /**
     * Piezas fallidas (cantidad que se imprimió y se perdió). Cada una cuesta lo mismo que una pieza
     * buena, con material, electricidad, depreciación, mantenimiento y mano de obra; ese costo se suma
     * al total y queda repartido entre las piezas buenas. No incluye "otros costos por pieza"
     * (embalaje, envío), que solo se gastan en piezas buenas. Vacío = ninguna.
     */
    val piezasFallidas: Double? = null,
    val margenPct: Double? = null,
    /** Otros costos por pieza (embalaje, envío, etiqueta…). Vacío = no aplica. */
    val otrosPorPieza: Double? = null,
    val incluirIva: Boolean = false,
    /** Campos opcionales con texto escrito pero que no es un número válido. */
    val invalidOptional: Set<Field> = emptySet(),
    /**
     * Piezas adicionales (2.ª en adelante). La 1.ª pieza son los campos de arriba (precioKg, gramos,
     * horas, minutos, watts). El costo total suma todas las piezas: cada una es un ciclo más de la máquina.
     */
    val partesExtra: List<Parte> = emptyList(),
    /**
     * Tiempo global (v2.2): una sola hora y minutos para toda la impresión, sin importar las piezas.
     * Se reparte entre las piezas en proporción a sus gramos (así la electricidad usa los watts de cada
     * material). Con [tiempoGlobal] en false cada pieza trae su propio tiempo.
     */
    val tiempoGlobal: Boolean = false,
    val horasGlobal: Double? = null,
    val minutosGlobal: Double? = null,
    /** Perfil de filamento de la 1.ª pieza (opcional; solo lleva el consumo, no cambia el costo). */
    val perfil: String? = null
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
    /** Cobro por hora de impresión: tarifa por hora x horas totales (v2.3). */
    val cobroHoras: Double = 0.0,
    /** Depreciación + mantenimiento reales del lote (piso interno), aunque la tarifa los reemplace (v2.5). */
    val costoOperativo: Double = 0.0,
    /** Costo operativo por hora de máquina: (depreciación + mantenimiento + electricidad) / horas (v2.5). */
    val costoOperativoPorHora: Double = 0.0,
    /** Ingreso por hora de máquina: (venta del lote - material - electricidad - mano de obra - fallidas - otros) / horas. */
    val gananciaPorHora: Double? = null,
    val merma: Double = 0.0,
    /** Precio de venta unitario sin IVA (costo unitario + margen). null si no se indicó margen. */
    val precioVenta: Double? = null,
    /** Precio de venta unitario con IVA. null si no se indicó margen o no se pidió IVA. */
    val precioConIva: Double? = null,
    /** Ganancia estimada del lote completo, sin IVA. null si no se indicó margen. */
    val gananciaLote: Double? = null,
    /** Otros costos del lote (otros por pieza x piezas). */
    val otros: Double = 0.0,
    /** Costo total por gramo de filamento usado. null si los gramos son 0. */
    val costoPorGramo: Double? = null,
    /** Costo total por hora de impresión. */
    val costoPorHora: Double = 0.0,
    /** Ganancia por pieza = costo unitario x margen. null si no se indicó margen. */
    val gananciaUnitaria: Double? = null,
    /** Precio de venta de todo el lote sin IVA. null si no se indicó margen. */
    val precioVentaLote: Double? = null,
    /** Margen aplicado (%), para mostrarlo junto a la ganancia. */
    val margenPct: Double? = null,
    /** Piezas fallidas consideradas y costo de cada una (para mostrar el detalle). */
    val piezasFallidas: Double = 0.0,
    val costoPiezaFallida: Double = 0.0,
    /** Detalle por pieza (la 1.ª primero). Con una sola pieza queda con 1 elemento. */
    val partes: List<ParteDetalle> = emptyList(),
    val gramosTotales: Double = 0.0,
    /** Porcentaje de un kilo que representan todos los gramos del lote. */
    val pctKiloTotal: Double = Perfiles.porcentajeDelKilo(gramosTotales),
    /** Cantidad de unidades que salen del lote (reparte el costo total; no lo multiplica). */
    val unidades: Double = 1.0
)

sealed class Outcome {
    data class Success(val breakdown: CostBreakdown) : Outcome()
    data class Failure(
        val fieldErrors: Map<Field, String>,
        val general: String? = null,
        /** Errores de las piezas adicionales: índice 0 = 2.ª pieza, 1 = 3.ª, etc. */
        val parteErrors: Map<Int, Map<Field, String>> = emptyMap()
    ) : Outcome()
}

/**
 * Material de impresión con el consumo promedio de la Bambu Lab A1 Combo con ese material.
 * [watts] = null en la opción "Otro": el usuario escribe su propio valor.
 * [estimado] = true cuando no hay una cifra publicada o medida y el valor es una estimación
 * (se marca así en pantalla para no hacerla pasar por un dato medido).
 */
data class Material(
    val nombre: String,
    val watts: Double?,
    val estimado: Boolean,
    val fuente: String
)

/**
 * Consumo eléctrico promedio por material de la Bambu Lab A1 Combo (ver README, sección Fuentes).
 * El pico de calentamiento (hasta 1.300 W a 220 V según la hoja técnica) dura poco y NO es
 * un consumo promedio, por eso no se ofrece como valor. El PC no se incluye: la A1 no tiene
 * cámara cerrada y Bambu Lab no lo recomienda en ella.
 */
object Materiales {
    val todos: List<Material> = listOf(
        Material("PLA", 95.0, false, "promedio publicado en la wiki de Bambu Lab (220 V)"),
        Material("PETG", 120.0, true, "estimado: la cama y la boquilla trabajan más calientes que en PLA"),
        Material("TPU", 95.0, true, "estimado: temperaturas similares al PLA"),
        Material("ABS", 200.0, false, "promedio publicado en la wiki de Bambu Lab (220 V)"),
        Material("ASA", 200.0, true, "estimado: se imprime con temperaturas similares al ABS"),
        Material("Otro (ingresar watts)", null, false, "valor que ingreses tú")
    )

    /** Texto de cada opción del menú: "PLA — 95 W", "PETG — 120 W (estimado)". */
    fun etiqueta(m: Material): String {
        val w = m.watts ?: return m.nombre
        return m.nombre + " \u2014 " + Suggested.plain(w) + " W" + if (m.estimado) " (estimado)" else ""
    }
}

/**
 * Valores fijos de la impresora: se suman automáticamente al costo (depreciación y
 * mantenimiento) y solo aparecen en el resumen; no se editan en pantalla.
 */
object Suggested {
    /**
     * Bambu Lab A1 Combo (con AMS Lite), precio de mercado en Chile con IVA, 15-nov-2026:
     * $489.990 en todotoner.cl (disponible). No se pudo leer pcfactory.cl (bloquea la lectura
     * automática); otra tienda (makerschile.cl) lo listaba en $567.000 sin stock.
     */
    const val PRECIO_IMPRESORA = 489990.0
    const val PRECIO_FECHA = "15-nov-2026"

    /**
     * Vida útil estimada para depreciar. Bambu Lab no publica una vida útil; en su foro un usuario
     * toma 5.000 h como un año de uso muy intenso. Es una estimación, no un dato del fabricante.
     */
    const val VIDA_UTIL_HORAS = 5000.0

    /**
     * Mantenimiento por hora (estimación): reserva para repuestos que se gastan. Único precio
     * verificado: hotend A1 de Bambu Lab, 10,99 EUR (tienda oficial UE), de duración de meses con
     * PLA; el resto (placa PEI, grasa, piezas menores) es estimado.
     * Desglose: hotend ~$12/h + placa PEI ~$17/h + lubricación y piezas menores ~$21/h.
     */
    const val MANTENIMIENTO_POR_HORA = 50.0

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
    val referenciaKwh: Double?,
    /** true = no hay dato propio de la región: se usa la tarifa de referencia de la distribuidora que la atiende. */
    val estimado: Boolean = false
)

object Regiones {
    /** Fecha de los valores referenciales. NO son oficiales y se desactualizan: ver boleta. */
    const val REFERENCIA_FECHA = "noviembre 2026"

    /** Más lejos que esto de cualquier capital regional se considera fuera de Chile. */
    const val MAX_KM = 700.0

    /**
     * Precio efectivo del kWh (cuenta mensual con cargos fijos e IVA / kWh) de un hogar con tarifa BT1
     * y 250 kWh al mes, vigente desde el 1-nov-2026, según los pliegos de cada distribuidora recopilados
     * en mapache.ai (sitio no oficial) y leídos el 17-nov-2026. Son valores de una comuna de referencia:
     * dentro de una región varían bastante por comuna (p. ej. Chilquinta, de $205 a $298).
     * Los datos directos son: Valparaíso (Chilquinta, Viña del Mar), Metropolitana (Enel, Maipú: $246,51; CGE,
     * Puente Alto: $247,68), La Araucanía (Frontel, Temuco), Los Lagos (Saesa, Puerto Montt), Aysén (Edelaysén,
     * Coihaique) y Magallanes (Edelmag, Punta Arenas). Para las demás regiones esa fuente no trae dato:
     * se usa el valor de referencia de CGE (Puente Alto, $247,68 -> $248), marcado como estimado, salvo
     * Los Ríos: Valdivia la atiende Saesa (rango $231 a $293) y tuvo la mayor alza de julio de 2026
     * (16% a 23% según la fuente), así que se estima el extremo alto del rango, $293.
     * Antes de la alza de julio de 2026 la tabla traía valores más bajos ($140 a $170).
     */
    val todas: List<Region> = listOf(
        Region("Arica y Parinacota", -18.4783, -70.3126, 248.0, true),
        Region("Tarapacá", -20.2307, -70.1357, 248.0, true),
        Region("Antofagasta", -23.6509, -70.3975, 248.0, true),
        Region("Atacama", -27.3668, -70.3314, 248.0, true),
        Region("Coquimbo", -29.9027, -71.2519, 248.0, true),
        Region("Valparaíso", -33.0472, -71.6127, 298.0),
        Region("Metropolitana de Santiago", -33.4489, -70.6693, 247.0),
        Region("O'Higgins", -34.1708, -70.7444, 248.0, true),
        Region("Maule", -35.4264, -71.6554, 248.0, true),
        Region("Ñuble", -36.6063, -72.1034, 248.0, true),
        Region("Biobío", -36.8201, -73.0444, 248.0, true),
        Region("La Araucanía", -38.7359, -72.5904, 309.0),
        Region("Los Ríos", -39.8142, -73.2459, 293.0, true),
        Region("Los Lagos", -41.4693, -72.9424, 261.0),
        Region("Aysén", -45.5752, -72.0662, 293.0),
        Region("Magallanes y la Antártica Chilena", -53.1638, -70.9171, 265.0)
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

/** Suma rápida a una casilla (botones "+ 1 pieza", "+ 1 hora", "+ 10 min"). */
object Rapidos {
    /** Suma [delta] al número escrito (vacío o inválido = 0) y devuelve el texto para la casilla. */
    fun sumar(texto: String?, delta: Double): String =
        Suggested.plainRounded((CostCalculator.parseNumber(texto) ?: 0.0) + delta)

    /** Resultado de sumar minutos: [horas] es null si las horas no cambian. */
    data class Tiempo(val horas: String?, val minutos: String)

    /** Suma minutos y, al llegar a 60, pasa la hora completa a la casilla de horas. */
    fun sumarMinutos(horasTexto: String?, minutosTexto: String?, delta: Double = 10.0): Tiempo {
        val total = (CostCalculator.parseNumber(minutosTexto) ?: 0.0) + delta
        if (total < 60.0) return Tiempo(null, Suggested.plainRounded(total))
        val horasExtra = Math.floor(total / 60.0)
        val h = (CostCalculator.parseNumber(horasTexto) ?: 0.0) + horasExtra
        return Tiempo(Suggested.plainRounded(h), Suggested.plainRounded(total - horasExtra * 60.0))
    }
}

/** Estilo de una línea del resumen (para el texto y para la imagen). */
enum class Estilo { TITULO, SUBTITULO_ENC, SECCION, NORMAL, TOTAL, GANANCIA, VENTA, NOTA, SEPARADOR }

data class Fila(val etiqueta: String, val valor: String = "", val estilo: Estilo = Estilo.NORMAL)

/** Resumen del cálculo: el mismo contenido sirve para el texto copiado y para la imagen guardada. */
object Compartir {
    const val SIN_NOMBRE = "Cálculo sin nombre"

    fun filas(
        b: CostBreakdown,
        i: CostInputs,
        nombre: String?,
        material: String?,
        region: String?,
        fechaHora: String
    ): List<Fila> {
        val f = CostCalculator::formatClp
        val l = mutableListOf<Fila>()
        l += Fila(nombre?.trim().takeUnless { it.isNullOrEmpty() } ?: SIN_NOMBRE, estilo = Estilo.TITULO)
        l += Fila("Costo de impresión 3D · $fechaHora", estilo = Estilo.SUBTITULO_ENC)
        l += Fila("Calculado con: " + Resumen.entradas(i, material, region), estilo = Estilo.NOTA)
        if (b.partes.size > 1) {
            l += Fila("Piezas del lote (" + b.partes.size + " impresiones)", estilo = Estilo.SECCION)
            b.partes.forEachIndexed { k, p ->
                l += Fila(
                    "Pieza " + (k + 1) + " · " + Suggested.plainRounded(p.gramos) + " g (" +
                        Perfiles.textoPct(p.pctKilo) + " del kilo) · " + Resumen.tiempo(p.horas),
                    f(p.subtotal)
                )
            }
        }
        l += Fila("Costos", estilo = Estilo.SECCION)
        l += Fila("Material", f(b.material))
        l += Fila("Electricidad", f(b.electricidad))
        if (b.cobroHoras > 0.0) {
            // La tarifa reemplaza a la depreciación y al mantenimiento: no se muestran ni se cobran dos veces.
            l += Fila(
                "Cobro por hora de impresión (" + Resumen.tiempo(b.horasTotales) + " x " +
                    f(b.cobroHoras / b.horasTotales) + ", incluye depreciación y mantenimiento)",
                f(b.cobroHoras)
            )
        } else {
            l += Fila("Depreciación de la impresora", f(b.depreciacion))
            l += Fila("Mantenimiento", f(b.mantenimiento))
        }
        if (b.manoObra > 0.0) l += Fila("Mano de obra", f(b.manoObra))
        if (b.merma > 0.0) {
            l += Fila(
                "Unidades fallidas (" + Suggested.plainRounded(b.piezasFallidas) + " x " + f(b.costoPiezaFallida) + ")",
                f(b.merma)
            )
        }
        if (b.otros > 0.0) l += Fila("Otros costos", f(b.otros))
        l += Fila("COSTO DEL LOTE (" + Suggested.plainRounded(b.unidades) + (if (b.unidades == 1.0) " unidad)" else " unidades)"), f(b.total), Estilo.TOTAL)
        l += Fila("COSTO UNITARIO (lote ÷ unidades)", f(b.unitario), Estilo.TOTAL)
        l += Fila(
            "Filamento total del lote",
            Suggested.plainRounded(b.gramosTotales) + " g (" + Perfiles.textoPct(b.pctKiloTotal) + " de 1 kg)"
        )
        l += Fila("Tiempo total de impresión del lote", Resumen.tiempo(b.horasTotales))
        b.costoPorGramo?.let { l += Fila("Costo por gramo de filamento", f(it)) }
        l += Fila("Costo por hora de impresión", f(b.costoPorHora))
        l += Fila("Costo operativo por hora de máquina (piso interno)", f(b.costoOperativoPorHora))
        b.gananciaPorHora?.let { l += Fila("Ingreso por hora de máquina (venta sin material, mano de obra ni otros)", f(it)) }
        val pv = b.precioVenta
        if (pv != null) {
            val pct = b.margenPct?.let { Suggested.plainRounded(it) + "% " } ?: ""
            l += Fila("Ganancia estimada (" + pct + "sobre el costo)", estilo = Estilo.SECCION)
            b.gananciaUnitaria?.let { l += Fila("Ganancia por unidad", f(it), Estilo.GANANCIA) }
            b.gananciaLote?.let { l += Fila("Ganancia del lote", f(it), Estilo.GANANCIA) }
            l += Fila("Precio de venta unitario (sin IVA)", f(pv), Estilo.VENTA)
            b.precioConIva?.let { l += Fila("Precio de venta unitario con IVA", f(it), Estilo.VENTA) }
            b.precioVentaLote?.let { l += Fila("Precio de venta del lote (sin IVA)", f(it)) }
        }
        val tabla = PorCantidad.tabla(i)
        if (tabla.size > 1) {
            l += Fila("Precio por unidad según la cantidad del lote", estilo = Estilo.SECCION)
            tabla.forEach { l += Fila(PorCantidad.etiqueta(it), PorCantidad.valor(it)) }
        }
        val dh = if (b.horasTotales > 0.0) b.costoOperativo / b.horasTotales else 0.0
        l += Fila(
            if (b.cobroHoras > 0.0) {
                "El cobro por hora incluye la depreciación y el mantenimiento (costo operativo real de " +
                    f(dh) + " por hora). La electricidad va aparte. Valores en pesos chilenos."
            } else {
                "Incluye depreciación y mantenimiento (" + f(dh) + " por hora entre ambos). Valores en pesos chilenos."
            },
            estilo = Estilo.NOTA
        )
        return l
    }

    fun texto(
        b: CostBreakdown, i: CostInputs, nombre: String?, material: String?, region: String?, fechaHora: String
    ): String = filas(b, i, nombre, material, region, fechaHora).joinToString("\n") {
        when (it.estilo) {
            Estilo.SECCION -> "\n" + it.etiqueta.uppercase(Locale.forLanguageTag("es-CL"))
            Estilo.TITULO, Estilo.SUBTITULO_ENC, Estilo.NOTA -> it.etiqueta
            else -> it.etiqueta + ": " + it.valor
        }
    }

    /** Nombre de archivo seguro: solo letras, números y guion bajo, con la marca de tiempo al final. */
    fun nombreArchivo(nombre: String?, marca: String): String {
        val limpio = (nombre ?: "").trim()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(40)
        return (if (limpio.isEmpty()) "calculo" else limpio) + "_" + marca
    }
}

/** Textos y comprobaciones que ayudan a ver con qué datos se hizo cada cálculo. */
object Resumen {
    /** Tiempo legible: 5.5 -> "5 h 30 min". */
    fun tiempo(horas: Double): String {
        val totalMin = Math.round(horas * 60.0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (m == 0L) "$h h" else "$h h $m min"
    }

    /** Las partes del desglose deben sumar exactamente el total (control interno). */
    fun partesSumanElTotal(b: CostBreakdown): Boolean {
        val suma = b.material + b.electricidad + b.depreciacion + b.mantenimiento + b.manoObra + b.cobroHoras + b.merma + b.otros
        return Math.abs(suma - b.total) <= 1e-9 * Math.max(1.0, Math.abs(b.total))
    }

    /**
     * Una línea con los datos que entraron al cálculo, para compararlos de un vistazo con lo
     * que se escribió. [material] y [region] son opcionales (solo para mostrar).
     */
    fun entradas(i: CostInputs, material: String?, region: String?): String {
        val partes = mutableListOf<String>()
        if (i.partesExtra.isEmpty()) {
            i.precioKg?.let { partes += CostCalculator.formatClp(it) + "/kg" }
            i.gramos?.let { partes += Suggested.plainRounded(it) + " g" + (material?.let { m -> " de $m" } ?: "") }
        } else {
            // Producto de varias piezas: se muestran los totales (el detalle de cada una va aparte).
            val todas = listOf(Parte(i.precioKg, i.gramos, i.horas, i.minutos, i.watts)) + i.partesExtra
            partes += todas.size.toString() + " piezas"
            partes += Suggested.plainRounded(todas.sumOf { it.gramos ?: 0.0 }) + " g en total"
        }
        i.piezas?.let { partes += Suggested.plainRounded(it) + (if (it == 1.0) " unidad" else " unidades") }
        if (i.partesExtra.isEmpty() && i.tiempoGlobal) {
            partes += tiempo((i.horasGlobal ?: 0.0) + (i.minutosGlobal ?: 0.0) / 60.0) + " (tiempo global)"
            i.watts?.let { partes += Suggested.plainRounded(it) + " W" }
        } else if (i.partesExtra.isEmpty()) {
            if (i.horas != null || i.minutos != null) {
                partes += Suggested.plainRounded(i.horas ?: 0.0) + " h " + Suggested.plainRounded(i.minutos ?: 0.0) + " min"
            }
            i.watts?.let { partes += Suggested.plainRounded(it) + " W" }
        } else {
            val todas = listOf(Parte(i.precioKg, i.gramos, i.horas, i.minutos, i.watts)) + i.partesExtra
            partes += tiempo(
                if (i.tiempoGlobal) (i.horasGlobal ?: 0.0) + (i.minutosGlobal ?: 0.0) / 60.0
                else todas.sumOf { (it.horas ?: 0.0) + (it.minutos ?: 0.0) / 60.0 }
            ) + " en total"
        }
        i.precioKwh?.let { partes += CostCalculator.formatClp(it) + "/kWh" + (region?.let { r -> " ($r)" } ?: "") }
        i.tarifaHora?.let { partes += "cobro " + CostCalculator.formatClp(it) + "/h de impresión" }
        if (i.horasTrabajo != null && i.costoHoraTrabajo != null) {
            partes += "mano de obra " + Suggested.plainRounded(i.horasTrabajo) + " h x " +
                CostCalculator.formatClp(i.costoHoraTrabajo)
        }
        i.otrosPorPieza?.let { partes += "otros " + CostCalculator.formatClp(it) + "/unidad" }
        i.piezasFallidas?.let { partes += Suggested.plainRounded(it) + (if (it == 1.0) " unidad fallida" else " unidades fallidas") }
        i.margenPct?.let { partes += "margen " + Suggested.plainRounded(it) + "%" }
        if (i.incluirIva) partes += "con IVA"
        return partes.joinToString(" · ")
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

    private fun nonNeg(errors: MutableMap<Field, String>, field: Field, v: Double?) {
        when {
            v == null -> errors[field] = MSG_REQUIRED
            v < 0.0 -> errors[field] = MSG_NEGATIVE
        }
    }

    /** Valida los datos propios de una pieza: filamento, gramos, consumo y tiempo (horas y/o minutos). */
    private fun validarParte(p: Parte, errors: MutableMap<Field, String>, conTiempo: Boolean = true) {
        nonNeg(errors, Field.PRECIO_KG, p.precioKg)
        nonNeg(errors, Field.GRAMOS, p.gramos)
        nonNeg(errors, Field.WATTS, p.watts)
        if (conTiempo) validarTiempo(p.horas, p.minutos, Field.HORAS, Field.MINUTOS, errors)
    }

    /** Tiempo: un campo vacío cuenta como 0 si el otro tiene valor; el total debe ser mayor que 0. */
    private fun validarTiempo(
        horas: Double?, minutos: Double?, fHoras: Field, fMinutos: Field, errors: MutableMap<Field, String>
    ) {
        if (horas == null && minutos == null) {
            errors[fHoras] = MSG_TIME_REQUIRED
            errors[fMinutos] = MSG_TIME_REQUIRED
        } else {
            if (horas != null && horas < 0.0) errors[fHoras] = MSG_NEGATIVE
            if (minutos != null && (minutos < 0.0 || minutos >= 60.0)) {
                errors[fMinutos] = MSG_MINUTES
            }
            if (!errors.containsKey(fHoras) && !errors.containsKey(fMinutos)) {
                val total = (horas ?: 0.0) + (minutos ?: 0.0) / 60.0
                if (total <= 0.0) {
                    errors[fHoras] = MSG_TIME_POSITIVE
                    errors[fMinutos] = MSG_TIME_POSITIVE
                }
            }
        }
    }

    fun calculate(i: CostInputs): Outcome {
        val errors = LinkedHashMap<Field, String>()

        val primera = Parte(i.precioKg, i.gramos, i.horas, i.minutos, i.watts, i.perfil)
        validarParte(primera, errors, conTiempo = !i.tiempoGlobal)
        val parteErrors = LinkedHashMap<Int, Map<Field, String>>()
        i.partesExtra.forEachIndexed { idx, p ->
            val e = LinkedHashMap<Field, String>()
            validarParte(p, e, conTiempo = !i.tiempoGlobal)
            if (e.isNotEmpty()) parteErrors[idx] = e
        }
        if (i.tiempoGlobal) {
            validarTiempo(i.horasGlobal, i.minutosGlobal, Field.HORAS_GLOBAL, Field.MINUTOS_GLOBAL, errors)
        }

        nonNeg(errors, Field.PRECIO_KWH, i.precioKwh)
        nonNeg(errors, Field.PRECIO_IMPRESORA, i.precioImpresora)
        nonNeg(errors, Field.MANTENIMIENTO, i.mantenimientoPorHora)

        // Unidades: entero mayor que 0 (evita división por cero).
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

        optionalNonNegative(Field.TARIFA_HORA, i.tarifaHora)
        optionalNonNegative(Field.DEPRECIACION_HORA, i.depreciacionHora)
        optionalNonNegative(Field.MANTENIMIENTO_HORA, i.mantenimientoHoraManual)
        optionalNonNegative(Field.MERMA, i.piezasFallidas)
        optionalNonNegative(Field.MARGEN, i.margenPct)
        optionalNonNegative(Field.OTROS_PIEZA, i.otrosPorPieza)

        if (errors.isNotEmpty() || parteErrors.isNotEmpty()) return Outcome.Failure(errors, null, parteErrors)

        // A partir de aquí todos los valores requeridos son no nulos y válidos.
        val n = piezas!!

        // Cada pieza es un ciclo de la máquina: su propio filamento, consumo y tiempo. El costo del
        // producto es la suma de todas (la electricidad usa los watts del material de cada pieza).
        val todas = listOf(primera) + i.partesExtra
        val gramosDeTodas = todas.sumOf { it.gramos!! }
        val horasGlobales = (i.horasGlobal ?: 0.0) + (i.minutosGlobal ?: 0.0) / 60.0
        val detalle = todas.map { p ->
            val h = if (i.tiempoGlobal) {
                // Tiempo global: se reparte según los gramos de cada pieza (a partes iguales si todas son 0 g).
                horasGlobales * (if (gramosDeTodas > 0.0) p.gramos!! / gramosDeTodas else 1.0 / todas.size)
            } else {
                (p.horas ?: 0.0) + (p.minutos ?: 0.0) / 60.0
            }
            val depOp = (i.depreciacionHora ?: (i.precioImpresora!! / vida!!)) * h
            val mantOp = (i.mantenimientoHoraManual ?: i.mantenimientoPorHora!!) * h
            val tarifa = i.tarifaHora ?: 0.0
            val conTarifa = tarifa > 0.0
            ParteDetalle(
                gramos = p.gramos!!,
                horas = h,
                material = (p.precioKg!! / 1000.0) * p.gramos,
                electricidad = (p.watts!! / 1000.0) * h * i.precioKwh!!,
                // Con tarifa por hora, ella reemplaza a la depreciación y al mantenimiento.
                depreciacion = if (conTarifa) 0.0 else depOp,
                mantenimiento = if (conTarifa) 0.0 else mantOp,
                cobroHoras = if (conTarifa) tarifa * h else 0.0,
                costoOperativo = depOp + mantOp
            )
        }
        // Cada pieza es una impresión completa (gramos y tiempo del laminador): el costo del lote es la suma de
        // todas. Las n unidades solo reparten ese costo (costo unitario = lote / n); nunca lo multiplican.
        val horasTotales = if (i.tiempoGlobal) horasGlobales else detalle.sumOf { it.horas }
        val gramosTotales = detalle.sumOf { it.gramos }
        val material = detalle.sumOf { it.material }
        val electricidad = detalle.sumOf { it.electricidad }
        val depreciacion = detalle.sumOf { it.depreciacion }
        val mantenimiento = detalle.sumOf { it.mantenimiento }
        val costoOperativo = detalle.sumOf { it.costoOperativo }
        val base = material + electricidad + depreciacion + mantenimiento

        // Extras: sin datos valen 0 y el total es idéntico al cálculo base.
        val manoObra = (horasTrabajo ?: 0.0) * (costoHora ?: 0.0)
        // Pieza fallida = costo completo de una pieza (con mano de obra) x cantidad de fallidas.
        val fallidas = i.piezasFallidas ?: 0.0
        // Cobro por hora: tarifa x horas totales; reemplaza a la depreciación y al mantenimiento.
        val cobroHoras = detalle.sumOf { it.cobroHoras }
        // Una unidad fallida cuesta lo que realmente se perdió (material, electricidad, desgaste real y mano de
        // obra), no la tarifa comercial. Sin tarifa es idéntico al costo completo de una unidad.
        val costoPiezaFallida = (material + electricidad + costoOperativo + manoObra) / n
        val costoMerma = costoPiezaFallida * fallidas

        val otros = (i.otrosPorPieza ?: 0.0) * n
        val total = base + manoObra + cobroHoras + costoMerma + otros
        val unitario = total / n

        val margen = i.margenPct
        val precioVenta = if (margen != null) unitario * (1.0 + margen / 100.0) else null
        val precioConIva = if (i.incluirIva && precioVenta != null) precioVenta * (1.0 + IVA_RATE) else null
        val gananciaLote = if (precioVenta != null) (precioVenta - unitario) * n else null
        val gananciaUnitaria = if (precioVenta != null) precioVenta - unitario else null
        val precioVentaLote = if (precioVenta != null) precioVenta * n else null
        val costoPorGramo = if (gramosTotales > 0.0) total / gramosTotales else null
        val costoPorHora = total / horasTotales
        val costoOperativoPorHora = (costoOperativo + electricidad) / horasTotales
        val gananciaPorHora = precioVentaLote?.let {
            (it - material - electricidad - manoObra - costoMerma - otros) / horasTotales
        }

        val all = listOfNotNull(
            material, electricidad, depreciacion, mantenimiento, manoObra, cobroHoras, costoMerma,
            total, unitario, precioVenta, precioConIva, gananciaLote, otros, costoPorHora, gananciaUnitaria, precioVentaLote
        ) + listOfNotNull(costoPorGramo)
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
                cobroHoras = cobroHoras,
                costoOperativo = costoOperativo,
                costoOperativoPorHora = costoOperativoPorHora,
                gananciaPorHora = gananciaPorHora,
                merma = costoMerma,
                precioVenta = precioVenta,
                precioConIva = precioConIva,
                gananciaLote = gananciaLote,
                otros = otros,
                costoPorGramo = costoPorGramo,
                costoPorHora = costoPorHora,
                gananciaUnitaria = gananciaUnitaria,
                precioVentaLote = precioVentaLote,
                margenPct = margen,
                piezasFallidas = fallidas,
                costoPiezaFallida = costoPiezaFallida,
                partes = detalle,
                gramosTotales = gramosTotales,
                unidades = n
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


/** Costo y precio unitario si el mismo lote saliera con otra cantidad de unidades (v2.4). */
data class FilaCantidad(
    val unidades: Double,
    val costoUnitario: Double,
    val precioVenta: Double?,
    val precioConIva: Double?,
    val esActual: Boolean
)

/**
 * Tabla de precio por cantidad: recalcula el mismo trabajo con otras cantidades de unidades. El costo del lote
 * (material, máquina, cobro por hora, mano de obra) es el mismo; solo cambia cómo se reparte, y lo que depende de
 * la cantidad (otros costos por unidad) se recalcula. Sirve para cotizar por tramos cuando aún no se sabe el lote.
 */
object PorCantidad {
    val CANTIDADES = listOf(5.0, 10.0, 25.0, 50.0, 100.0)

    fun tabla(i: CostInputs, cantidades: List<Double> = CANTIDADES): List<FilaCantidad> {
        val actual = i.piezas ?: 1.0
        val todas = (listOf(actual) + cantidades.filter { it != actual }).distinct()
        return todas.sortedBy { it }.mapNotNull { n ->
            val r = CostCalculator.calculate(i.copy(piezas = n))
            (r as? Outcome.Success)?.breakdown?.let {
                FilaCantidad(n, it.unitario, it.precioVenta, it.precioConIva, n == actual)
            }
        }
    }

    fun etiqueta(f: FilaCantidad): String =
        Suggested.plainRounded(f.unidades) + (if (f.unidades == 1.0) " unidad" else " unidades") +
            (if (f.esActual) " (actual)" else "")

    fun valor(f: FilaCantidad): String {
        val c = CostCalculator::formatClp
        return "costo " + c(f.costoUnitario) + (f.precioVenta?.let { " · venta " + c(it) } ?: "") +
            (f.precioConIva?.let { " · con IVA " + c(it) } ?: "")
    }
}
