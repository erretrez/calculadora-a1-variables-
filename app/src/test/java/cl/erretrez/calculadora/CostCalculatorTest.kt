package cl.erretrez.calculadora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostCalculatorTest {

    private val delta = 1e-9

    private fun valid() = CostInputs(
        precioKg = 20000.0,
        gramos = 100.0,
        piezas = 2.0,
        horas = 5.0,
        minutos = 0.0,
        watts = 200.0,
        precioKwh = 150.0,
        precioImpresora = 400000.0,
        vidaUtilHoras = 5000.0,
        mantenimientoPorHora = 100.0
    )

    private fun success(i: CostInputs): CostBreakdown {
        val outcome = CostCalculator.calculate(i)
        assertTrue("Se esperaba Success pero fue $outcome", outcome is Outcome.Success)
        return (outcome as Outcome.Success).breakdown
    }

    private fun failure(i: CostInputs): Outcome.Failure {
        val outcome = CostCalculator.calculate(i)
        assertTrue("Se esperaba Failure pero fue $outcome", outcome is Outcome.Failure)
        return outcome as Outcome.Failure
    }

    @Test
    fun calculaCadaComponenteSegunLasFormulas() {
        val b = success(valid())
        assertEquals(2000.0, b.material, delta)        // 20000/1000 * 100
        assertEquals(150.0, b.electricidad, delta)     // 200/1000 * 5 * 150
        assertEquals(400.0, b.depreciacion, delta)     // 400000/5000 * 5
        assertEquals(500.0, b.mantenimiento, delta)    // 100 * 5
        assertEquals(3050.0, b.total, delta)
        assertEquals(1525.0, b.unitario, delta)        // 3050 / 2
    }

    @Test
    fun horasYMinutosSeConvierteAHorasDecimales() {
        val b = success(valid().copy(horas = 1.0, minutos = 30.0))
        assertEquals(1.5, b.horasTotales, delta)
        assertEquals(100.0 * 1.5, b.mantenimiento, delta)
    }

    @Test
    fun soloMinutosEsValido() {
        val b = success(valid().copy(horas = null, minutos = 45.0))
        assertEquals(0.75, b.horasTotales, delta)
    }

    @Test
    fun soloHorasEsValido() {
        val b = success(valid().copy(horas = 2.0, minutos = null))
        assertEquals(2.0, b.horasTotales, delta)
    }

    @Test
    fun cantidadDePiezasCeroONegativaEsInvalida() {
        assertEquals(CostCalculator.MSG_POSITIVE, failure(valid().copy(piezas = 0.0)).fieldErrors[Field.PIEZAS])
        assertEquals(CostCalculator.MSG_POSITIVE, failure(valid().copy(piezas = -3.0)).fieldErrors[Field.PIEZAS])
    }

    @Test
    fun cantidadDePiezasDebeSerEntera() {
        assertEquals(CostCalculator.MSG_INTEGER, failure(valid().copy(piezas = 2.5)).fieldErrors[Field.PIEZAS])
    }

    @Test
    fun vidaUtilCeroONegativaEsInvalida() {
        assertEquals(CostCalculator.MSG_POSITIVE, failure(valid().copy(vidaUtilHoras = 0.0)).fieldErrors[Field.VIDA_UTIL])
        assertEquals(CostCalculator.MSG_POSITIVE, failure(valid().copy(vidaUtilHoras = -1.0)).fieldErrors[Field.VIDA_UTIL])
    }

    @Test
    fun tiempoTotalCeroEsInvalido() {
        val f = failure(valid().copy(horas = 0.0, minutos = 0.0))
        assertEquals(CostCalculator.MSG_TIME_POSITIVE, f.fieldErrors[Field.HORAS])
        assertEquals(CostCalculator.MSG_TIME_POSITIVE, f.fieldErrors[Field.MINUTOS])
    }

    @Test
    fun sinHorasNiMinutosEsInvalido() {
        val f = failure(valid().copy(horas = null, minutos = null))
        assertEquals(CostCalculator.MSG_TIME_REQUIRED, f.fieldErrors[Field.HORAS])
        assertEquals(CostCalculator.MSG_TIME_REQUIRED, f.fieldErrors[Field.MINUTOS])
    }

    @Test
    fun minutosFueraDeRangoSonInvalidos() {
        assertEquals(CostCalculator.MSG_MINUTES, failure(valid().copy(minutos = 60.0)).fieldErrors[Field.MINUTOS])
        assertEquals(CostCalculator.MSG_MINUTES, failure(valid().copy(minutos = -1.0)).fieldErrors[Field.MINUTOS])
    }

    @Test
    fun valoresNegativosSonInvalidos() {
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(precioKg = -1.0)).fieldErrors[Field.PRECIO_KG])
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(gramos = -1.0)).fieldErrors[Field.GRAMOS])
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(watts = -1.0)).fieldErrors[Field.WATTS])
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(precioKwh = -1.0)).fieldErrors[Field.PRECIO_KWH])
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(precioImpresora = -1.0)).fieldErrors[Field.PRECIO_IMPRESORA])
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(mantenimientoPorHora = -1.0)).fieldErrors[Field.MANTENIMIENTO])
    }

    @Test
    fun camposVaciosSonInvalidosYSeInformanTodos() {
        val empty = CostInputs(null, null, null, null, null, null, null, null, null, null)
        val f = failure(empty)
        // Solo los 10 campos obligatorios; los opcionales vacíos no son error.
        assertEquals(Field.values().count { !it.optional }, f.fieldErrors.size)
        assertEquals(10, f.fieldErrors.size)
        assertTrue(f.fieldErrors.keys.none { it.optional })
    }

    // ---------- v1.2: extras opcionales ----------

    @Test
    fun sinExtrasElResultadoEsIgualAlCalculoBase() {
        val b = success(valid())
        assertEquals(0.0, b.manoObra, delta)
        assertEquals(0.0, b.merma, delta)
        assertEquals(3050.0, b.total, delta)
        assertNull(b.precioVenta)
        assertNull(b.precioConIva)
        assertNull(b.gananciaLote)
    }

    @Test
    fun calculaManoObraMermaMargenEIva() {
        val b = success(
            valid().copy(
                horasTrabajo = 2.0, costoHoraTrabajo = 3000.0,
                mermaPct = 20.0, margenPct = 30.0, incluirIva = true
            )
        )
        assertEquals(6000.0, b.manoObra, 1e-6)          // 2 h x 3000
        assertEquals(610.0, b.merma, 1e-6)              // 20% de 3050 (sin mano de obra)
        assertEquals(9660.0, b.total, 1e-6)             // 3050 + 6000 + 610
        assertEquals(4830.0, b.unitario, 1e-6)          // / 2 piezas
        assertEquals(6279.0, b.precioVenta!!, 1e-6)     // 4830 x 1,30
        assertEquals(7472.01, b.precioConIva!!, 1e-6)   // 6279 + 19% (1193,01)
        assertEquals(2898.0, b.gananciaLote!!, 1e-6)    // (6279 - 4830) x 2
    }

    @Test
    fun ivaSinMargenNoGeneraPrecio() {
        val b = success(valid().copy(incluirIva = true))
        assertNull(b.precioVenta)
        assertNull(b.precioConIva)
    }

    @Test
    fun margenCeroEsValidoYLaGananciaEsCero() {
        val b = success(valid().copy(margenPct = 0.0))
        assertEquals(b.unitario, b.precioVenta!!, delta)
        assertEquals(0.0, b.gananciaLote!!, delta)
    }

    @Test
    fun ivaDesactivadoNoCalculaPrecioConIva() {
        val b = success(valid().copy(margenPct = 10.0, incluirIva = false))
        assertNull(b.precioConIva)
    }

    @Test
    fun mermaDebeEstarEntreCeroYMenosDeCien() {
        assertEquals(CostCalculator.MSG_PCT, failure(valid().copy(mermaPct = 100.0)).fieldErrors[Field.MERMA])
        assertEquals(CostCalculator.MSG_PCT, failure(valid().copy(mermaPct = -1.0)).fieldErrors[Field.MERMA])
        success(valid().copy(mermaPct = 99.99))
        success(valid().copy(mermaPct = 0.0))
    }

    @Test
    fun margenNegativoEsInvalido() {
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(margenPct = -5.0)).fieldErrors[Field.MARGEN])
    }

    @Test
    fun manoDeObraExigeHorasYCostoPorHora() {
        assertEquals(
            CostCalculator.MSG_PAIR,
            failure(valid().copy(horasTrabajo = 2.0)).fieldErrors[Field.COSTO_HORA_TRABAJO]
        )
        assertEquals(
            CostCalculator.MSG_PAIR,
            failure(valid().copy(costoHoraTrabajo = 3000.0)).fieldErrors[Field.HORAS_TRABAJO]
        )
        assertEquals(
            CostCalculator.MSG_NEGATIVE,
            failure(valid().copy(horasTrabajo = -1.0, costoHoraTrabajo = 10.0)).fieldErrors[Field.HORAS_TRABAJO]
        )
    }

    @Test
    fun textoInvalidoEnUnOpcionalSeInformaYNoSeIgnora() {
        val f = failure(valid().copy(invalidOptional = setOf(Field.MARGEN, Field.MERMA)))
        assertEquals(CostCalculator.MSG_REQUIRED, f.fieldErrors[Field.MARGEN])
        assertEquals(CostCalculator.MSG_REQUIRED, f.fieldErrors[Field.MERMA])
    }

    @Test
    fun conPiezasInvalidasNoHayPrecioDeVenta() {
        failure(valid().copy(piezas = 0.0, margenPct = 30.0))
    }

    // ---------- v1.2: precio del kWh desde la boleta ----------

    @Test
    fun boletaCalculaPrecioEfectivoDelKwh() {
        val r = Boleta.precioKwh(30000.0, 200.0)
        assertEquals(150.0, r.precioKwh!!, delta)
        assertNull(r.error)
    }

    @Test
    fun boletaIncompletaNoDaResultadoNiError() {
        assertNull(Boleta.precioKwh(null, 200.0).precioKwh)
        assertNull(Boleta.precioKwh(null, 200.0).error)
        assertNull(Boleta.precioKwh(30000.0, null).precioKwh)
        assertNull(Boleta.precioKwh(30000.0, null).error)
    }

    @Test
    fun boletaConKwhCeroONegativoNoDivide() {
        assertEquals(Boleta.MSG_KWH_POSITIVO, Boleta.precioKwh(30000.0, 0.0).error)
        assertEquals(Boleta.MSG_KWH_POSITIVO, Boleta.precioKwh(30000.0, -5.0).error)
        assertNull(Boleta.precioKwh(30000.0, 0.0).precioKwh)
    }

    @Test
    fun boletaConMontoNegativoEsInvalida() {
        assertEquals(Boleta.MSG_MONTO_NEGATIVO, Boleta.precioKwh(-1.0, 100.0).error)
    }

    @Test
    fun boletaConResultadoNoFinitoSeRechaza() {
        assertEquals(Boleta.MSG_FUERA_DE_RANGO, Boleta.precioKwh(Double.MAX_VALUE, 1e-300).error)
    }

    @Test
    fun textoRedondeadoParaCopiarAlCampo() {
        assertEquals("150", Suggested.plainRounded(150.0))
        assertEquals("151.67", Suggested.plainRounded(151.6666666))
        assertEquals("0", Suggested.plainRounded(0.0))
        assertEquals(150.0, CostCalculator.parseNumber(Suggested.plainRounded(150.0))!!, delta)
        assertEquals(151.67, CostCalculator.parseNumber(Suggested.plainRounded(151.6666666))!!, delta)
    }

    // ---------- v1.2: regiones y ubicación ----------

    @Test
    fun hayDieciseisRegionesYOchoConReferencia() {
        assertEquals(16, Regiones.todas.size)
        assertEquals(8, Regiones.todas.count { it.referenciaKwh != null })
        assertEquals(16, Regiones.todas.map { it.nombre }.toSet().size)
    }

    @Test
    fun ubicaLasCapitalesRegionalesEnSuRegion() {
        for (r in Regiones.todas) {
            assertEquals(r.nombre, Regiones.masCercana(r.lat, r.lon)!!.nombre)
        }
    }

    @Test
    fun santiagoEsMetropolitanaConReferencia160() {
        val r = Regiones.masCercana(-33.45, -70.65)!!
        assertEquals("Metropolitana de Santiago", r.nombre)
        assertEquals(160.0, r.referenciaKwh!!, delta)
    }

    @Test
    fun puntosDelSurYDelNorteSeUbicanBien() {
        assertEquals("Magallanes y la Antártica Chilena", Regiones.masCercana(-53.0, -70.9)!!.nombre)
        assertEquals("Arica y Parinacota", Regiones.masCercana(-18.5, -70.3)!!.nombre)
        assertEquals("Los Lagos", Regiones.masCercana(-41.47, -72.94)!!.nombre)
    }

    @Test
    fun regionSinDatoReferencialNoInventaUnPrecio() {
        assertNull(Regiones.masCercana(-39.81, -73.25)!!.referenciaKwh) // Los Ríos
        assertNull(Regiones.masCercana(-35.43, -71.65)!!.referenciaKwh) // Maule
    }

    @Test
    fun ubicacionFueraDeChileOInvalidaDevuelveNull() {
        assertNull(Regiones.masCercana(-34.6, -58.4))        // Buenos Aires
        assertNull(Regiones.masCercana(-27.1, -109.4))       // Isla de Pascua (fuera del continente)
        assertNull(Regiones.masCercana(40.4, -3.7))          // Madrid
        assertNull(Regiones.masCercana(Double.NaN, -70.0))
        assertNull(Regiones.masCercana(-33.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun distanciaEntreSantiagoYValparaisoEsRazonable() {
        val km = Regiones.distanciaKm(-33.4489, -70.6693, -33.0472, -71.6127)
        assertTrue("Distancia inesperada: $km", km in 85.0..105.0)
        assertEquals(0.0, Regiones.distanciaKm(-33.0, -70.0, -33.0, -70.0), delta)
    }

    @Test
    fun cerosSonValidosEnCamposQueNoDividen() {
        val b = success(valid().copy(precioKg = 0.0, watts = 0.0, precioImpresora = 0.0, mantenimientoPorHora = 0.0))
        assertEquals(0.0, b.total, delta)
        assertEquals(0.0, b.unitario, delta)
    }

    @Test
    fun resultadoNoFinitoSeRechaza() {
        val f = failure(valid().copy(precioKg = Double.MAX_VALUE, gramos = Double.MAX_VALUE))
        assertEquals(CostCalculator.MSG_OVERFLOW, f.general)
    }

    @Test
    fun parseNumberAceptaPuntoYComa() {
        assertEquals(12.5, CostCalculator.parseNumber("12.5")!!, delta)
        assertEquals(12.5, CostCalculator.parseNumber("12,5")!!, delta)
        assertEquals(7.0, CostCalculator.parseNumber(" 7 ")!!, delta)
    }

    @Test
    fun parseNumberRechazaVacioYBasura() {
        assertNull(CostCalculator.parseNumber(null))
        assertNull(CostCalculator.parseNumber(""))
        assertNull(CostCalculator.parseNumber("   "))
        assertNull(CostCalculator.parseNumber("abc"))
        assertNull(CostCalculator.parseNumber("1.2.3"))
        assertNull(CostCalculator.parseNumber("."))
        assertNull(CostCalculator.parseNumber("NaN"))
        assertNull(CostCalculator.parseNumber("Infinity"))
        assertNull(CostCalculator.parseNumber("1e5"))
        assertNull(CostCalculator.parseNumber("12f"))
        assertNull(CostCalculator.parseNumber("-"))
    }

    @Test
    fun valoresSugeridosSonLosPedidos() {
        assertEquals(450000.0, Suggested.PRECIO_IMPRESORA, delta)
        assertEquals(5000.0, Suggested.VIDA_UTIL_HORAS, delta)
        assertEquals(100.0, Suggested.MANTENIMIENTO_POR_HORA, delta)
    }

    @Test
    fun textoPlanoDeSugeridosSeInterpretaComoElMismoNumero() {
        assertEquals("450000", Suggested.plain(Suggested.PRECIO_IMPRESORA))
        assertEquals("5000", Suggested.plain(Suggested.VIDA_UTIL_HORAS))
        assertEquals("100", Suggested.plain(Suggested.MANTENIMIENTO_POR_HORA))
        assertEquals(450000.0, CostCalculator.parseNumber(Suggested.plain(Suggested.PRECIO_IMPRESORA))!!, delta)
        assertEquals(5000.0, CostCalculator.parseNumber(Suggested.plain(Suggested.VIDA_UTIL_HORAS))!!, delta)
        assertEquals(100.0, CostCalculator.parseNumber(Suggested.plain(Suggested.MANTENIMIENTO_POR_HORA))!!, delta)
    }

    @Test
    fun calcularConLosSugeridosEsValidoYCorrecto() {
        // 10 h: depreciación = 450000/5000*10 = 900; mantenimiento = 100*10 = 1000
        val b = success(
            valid().copy(
                horas = 10.0,
                minutos = 0.0,
                precioImpresora = Suggested.PRECIO_IMPRESORA,
                vidaUtilHoras = Suggested.VIDA_UTIL_HORAS,
                mantenimientoPorHora = Suggested.MANTENIMIENTO_POR_HORA
            )
        )
        assertEquals(900.0, b.depreciacion, delta)
        assertEquals(1000.0, b.mantenimiento, delta)
    }

    @Test
    fun sugeridosConSeparadorDeMilesChileno() {
        assertEquals("5.000", Suggested.grouped(5000.0))
        assertEquals("450.000", Suggested.grouped(450000.0))
        assertEquals("100", Suggested.grouped(100.0))
    }

    @Test
    fun formatoClpNoUsaDecimalesYLlevaSimboloDePeso() {
        val s = CostCalculator.formatClp(1234567.4)
        assertTrue("Debe contener el símbolo \$: $s", s.contains("$"))
        assertFalse("No debe tener coma decimal: $s", s.contains(","))
        assertTrue("Debe contener los dígitos: $s", s.replace(".", "").contains("1234567"))
    }
}
