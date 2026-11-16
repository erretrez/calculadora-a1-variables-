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
                piezasFallidas = 1.0, margenPct = 30.0, incluirIva = true
            )
        )
        assertEquals(6000.0, b.manoObra, 1e-6)          // 2 h x 3000
        assertEquals(4525.0, b.costoPiezaFallida, 1e-6) // (3050 + 6000) / 2 piezas: costo completo de una pieza
        assertEquals(4525.0, b.merma, 1e-6)             // 1 pieza fallida x 4525
        assertEquals(13575.0, b.total, 1e-6)            // 3050 + 6000 + 4525
        assertEquals(6787.5, b.unitario, 1e-6)          // / 2 piezas buenas
        assertEquals(8823.75, b.precioVenta!!, 1e-6)    // 6787,5 x 1,30
        assertEquals(10500.4125, b.precioConIva!!, 1e-6) // x 1,19
        assertEquals(4072.5, b.gananciaLote!!, 1e-6)    // (8823,75 - 6787,5) x 2
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
    fun piezasFallidasNoPuedenSerNegativasPeroPuedenSerCeroODecimales() {
        assertEquals(CostCalculator.MSG_NEGATIVE, failure(valid().copy(piezasFallidas = -1.0)).fieldErrors[Field.MERMA])
        success(valid().copy(piezasFallidas = 0.0))
        success(valid().copy(piezasFallidas = 0.5))
        success(valid().copy(piezasFallidas = 25.0))
        assertEquals(CostCalculator.MSG_REQUIRED,
            failure(valid().copy(invalidOptional = setOf(Field.MERMA))).fieldErrors[Field.MERMA])
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

    // ---------- v1.3: valores fijos de la impresora y referencias de la A1 ----------

    private fun sinDatosDeImpresora() = CostInputs(
        precioKg = 20000.0,
        gramos = 100.0,
        piezas = 2.0,
        horas = 5.0,
        minutos = 0.0,
        watts = 200.0,
        precioKwh = 150.0
    )

    @Test
    fun sinDatosDeImpresoraSeUsanLosValoresFijos() {
        val b = success(sinDatosDeImpresora())
        assertEquals(489.99, b.depreciacion, 1e-9)   // 489990 / 5000 x 5 h
        assertEquals(250.0, b.mantenimiento, delta)  // 50 x 5 h
        assertEquals(2889.99, b.total, 1e-9)         // 2000 + 150 + 489,99 + 250
        assertEquals(1444.995, b.unitario, 1e-9)
    }

    @Test
    fun valoresFijosMasExtrasDanElTotalEsperado() {
        val b = success(
            sinDatosDeImpresora().copy(
                horasTrabajo = 2.0, costoHoraTrabajo = 3000.0,
                piezasFallidas = 1.0, margenPct = 30.0, incluirIva = true
            )
        )
        assertEquals(4444.995, b.merma, 1e-6)          // 1 fallida x (2889,99 + 6000) / 2
        assertEquals(13334.985, b.total, 1e-6)         // 2889,99 + 6000 + 4444,995
        assertEquals(6667.4925, b.unitario, 1e-6)
        assertEquals(8667.74025, b.precioVenta!!, 1e-6) // 6667,4925 x 1,30
        assertEquals(10314.6108975, b.precioConIva!!, 1e-6) // x 1,19
        assertEquals(4000.4955, b.gananciaLote!!, 1e-6)    // (8667,74025 - 6667,4925) x 2
        assertEquals(2000.24775, b.gananciaUnitaria!!, 1e-6)  // 30% del costo unitario
        assertEquals(17335.4805, b.precioVentaLote!!, 1e-6)   // 8667,74025 x 2 piezas
        assertEquals(30.0, b.margenPct!!, 1e-9)
    }

    @Test
    fun losValoresFijosNoPermitenDivisionPorCero() {
        assertTrue(Suggested.VIDA_UTIL_HORAS > 0.0)
        assertTrue(Suggested.PRECIO_IMPRESORA >= 0.0)
        assertTrue(Suggested.MANTENIMIENTO_POR_HORA >= 0.0)
    }

    @Test
    fun siSeEntreganOtrosDatosDeImpresoraElMotorLosSigueValidando() {
        val f = failure(sinDatosDeImpresora().copy(vidaUtilHoras = 0.0))
        assertEquals(CostCalculator.MSG_POSITIVE, f.fieldErrors[Field.VIDA_UTIL])
    }

    // ---------- v1.4: materiales, tabla por región e indicadores ----------

    private fun wattsDe(nombre: String): Double =
        Materiales.todos.first { it.nombre == nombre }.watts!!

    @Test
    fun materialesDeLaA1TienenSusWatts() {
        assertEquals(95.0, wattsDe("PLA"), delta)    // wiki de Bambu Lab
        assertEquals(200.0, wattsDe("ABS"), delta)   // wiki de Bambu Lab
        assertEquals(120.0, wattsDe("PETG"), delta)  // estimado
        assertEquals(95.0, wattsDe("TPU"), delta)    // estimado
        assertEquals(200.0, wattsDe("ASA"), delta)   // estimado
        assertTrue(Materiales.todos.none { it.nombre == "PC" })
    }

    @Test
    fun soloLosValoresPublicadosSeMarcanComoNoEstimados() {
        for (m in Materiales.todos.filter { it.watts != null }) {
            val esperadoEstimado = m.nombre !in setOf("PLA", "ABS")
            assertEquals(m.nombre, esperadoEstimado, m.estimado)
            assertEquals(m.nombre, esperadoEstimado, Materiales.etiqueta(m).contains("(estimado)"))
        }
    }

    @Test
    fun laUltimaOpcionEsOtroSinWatts() {
        assertNull(Materiales.todos.last().watts)
        assertTrue(Materiales.todos.dropLast(1).all { it.watts != null })
        assertEquals("PLA \u2014 95 W", Materiales.etiqueta(Materiales.todos.first { it.nombre == "PLA" }))
    }

    @Test
    fun losWattsDeCadaMaterialSeInterpretanComoElMismoNumero() {
        for (m in Materiales.todos.mapNotNull { it.watts }) {
            assertEquals(m, CostCalculator.parseNumber(Suggested.plain(m))!!, delta)
        }
    }

    @Test
    fun consumoAltoDaMasElectricidadQueElEstandar() {
        val estandar = success(sinDatosDeImpresora().copy(watts = wattsDe("PLA")))
        val alto = success(sinDatosDeImpresora().copy(watts = wattsDe("ABS")))
        assertTrue(alto.electricidad > estandar.electricidad)
        assertEquals(0.095 * 5.0 * 150.0, estandar.electricidad, 1e-9)
    }

    @Test
    fun tablaDeRegionesPrecioParaLaMetropolitana() {
        val santiago = Regiones.todas.first { it.nombre == "Metropolitana de Santiago" }
        assertEquals(160.0, santiago.referenciaKwh!!, delta)
        // Con el precio de la tabla el cálculo coincide con el de 150/kWh escalado: 0,2 kW x 5 h x 160 = 160
        val b = success(sinDatosDeImpresora().copy(precioKwh = santiago.referenciaKwh))
        assertEquals(160.0, b.electricidad, 1e-9)
    }

    @Test
    fun lasPartesSumanElTotalConYSinExtras() {
        assertTrue(Resumen.partesSumanElTotal(success(sinDatosDeImpresora())))
        val conExtras = success(
            sinDatosDeImpresora().copy(horasTrabajo = 2.0, costoHoraTrabajo = 3000.0, piezasFallidas = 1.0, margenPct = 30.0)
        )
        assertTrue(Resumen.partesSumanElTotal(conExtras))
    }

    @Test
    fun laVerificacionDetectaUnTotalIncorrecto() {
        val b = success(sinDatosDeImpresora())
        assertFalse(Resumen.partesSumanElTotal(b.copy(total = b.total + 1.0)))
    }

    @Test
    fun resumenDeEntradasMuestraLosDatosUsados() {
        val t = Resumen.entradas(sinDatosDeImpresora(), "PLA", "Metropolitana de Santiago")
        assertTrue(t, t.contains("100 g de PLA"))
        assertTrue(t, t.contains("2 piezas"))
        assertTrue(t, t.contains("5 h 0 min"))
        assertTrue(t, t.contains("200 W"))
        assertTrue(t, t.contains("/kWh (Metropolitana de Santiago)"))
        assertFalse(t, t.contains("fallida"))
        assertFalse(t, t.contains("IVA"))
    }

    @Test
    fun resumenDeEntradasIncluyeLosExtrasSoloSiSeIndican() {
        val t = Resumen.entradas(
            sinDatosDeImpresora().copy(horasTrabajo = 2.0, costoHoraTrabajo = 3000.0, piezasFallidas = 1.0, margenPct = 30.0, incluirIva = true),
            null, null
        )
        assertTrue(t, t.contains("mano de obra 2 h"))
        assertTrue(t, t.contains("1 pieza fallida"))
        assertTrue(t, t.contains("margen 30%"))
        assertTrue(t, t.contains("con IVA"))
    }

    // ---------- v1.5: otros costos por pieza, costo por gramo y por hora, compartir ----------

    @Test
    fun otrosCostosPorPiezaSeMultiplicanPorLasPiezas() {
        val b = success(sinDatosDeImpresora().copy(otrosPorPieza = 500.0))
        assertEquals(1000.0, b.otros, 1e-9)       // 500 x 2 piezas
        assertEquals(3889.99, b.total, 1e-9)      // 2889,99 + 1000
        assertEquals(1944.995, b.unitario, 1e-9)
        assertTrue(Resumen.partesSumanElTotal(b))
    }

    @Test
    fun laPiezaFallidaNoIncluyeLosOtrosCostosDeEmbalaje() {
        val b = success(sinDatosDeImpresora().copy(otrosPorPieza = 500.0, piezasFallidas = 1.0))
        assertEquals(1444.995, b.costoPiezaFallida, 1e-9)  // 2889,99 / 2: sin los 500 de embalaje
        assertEquals(1444.995, b.merma, 1e-9)
        assertEquals(5334.985, b.total, 1e-9)              // 2889,99 + 1444,995 + 1000
        assertTrue(Resumen.partesSumanElTotal(b))
    }

    @Test
    fun cadaPiezaFallidaSumaElCostoCompletoDeUnaPiezaYSeRepartePorLasBuenas() {
        // 4 piezas buenas cuestan 2889,99 + 6000 de mano de obra = 8889,99; una pieza = 2222,4975.
        val i = sinDatosDeImpresora().copy(piezas = 4.0, horasTrabajo = 2.0, costoHoraTrabajo = 3000.0)
        val sin = success(i)
        val con = success(i.copy(piezasFallidas = 2.0))
        assertEquals(2222.4975, con.costoPiezaFallida, 1e-9)
        assertEquals(sin.total + 2 * 2222.4975, con.total, 1e-9)           // se suma al total
        assertEquals(con.total / 4.0, con.unitario, 1e-9)                  // y se reparte entre las 4 buenas
        assertEquals(sin.unitario * 1.5, con.unitario, 1e-9)               // 2 fallidas sobre 4 = +50% por pieza
        assertEquals(sin.total, success(i.copy(piezasFallidas = 0.0)).total, 1e-9)
    }

    @Test
    fun unaPiezaFallidaConUnaBuenaDuplicaElCostoUnitario() {
        val sin = success(sinDatosDeImpresora().copy(piezas = 1.0))
        val con = success(sinDatosDeImpresora().copy(piezas = 1.0, piezasFallidas = 1.0))
        assertEquals(sin.unitario * 2.0, con.unitario, 1e-9)
    }

    @Test
    fun sinOtrosCostosElTotalNoCambia() {
        assertEquals(2889.99, success(sinDatosDeImpresora()).total, 1e-9)
        assertEquals(0.0, success(sinDatosDeImpresora()).otros, 1e-9)
    }

    @Test
    fun otrosCostosNegativosOInvalidosSeRechazan() {
        val neg = failure(sinDatosDeImpresora().copy(otrosPorPieza = -1.0))
        assertEquals(CostCalculator.MSG_NEGATIVE, neg.fieldErrors[Field.OTROS_PIEZA])
        val inv = failure(sinDatosDeImpresora().copy(invalidOptional = setOf(Field.OTROS_PIEZA)))
        assertEquals(CostCalculator.MSG_REQUIRED, inv.fieldErrors[Field.OTROS_PIEZA])
    }

    @Test
    fun costoPorGramoYPorHora() {
        val b = success(sinDatosDeImpresora())
        assertEquals(28.8999, b.costoPorGramo!!, 1e-9)   // 2889,99 / 100 g
        assertEquals(577.998, b.costoPorHora, 1e-9)      // 2889,99 / 5 h
    }

    @Test
    fun conCeroGramosNoHayCostoPorGramoNiDivisionPorCero() {
        val b = success(sinDatosDeImpresora().copy(gramos = 0.0))
        assertNull(b.costoPorGramo)
        assertTrue(b.costoPorHora.isFinite())
    }

    @Test
    fun textoParaCompartirTraeLosTotales() {
        val i = sinDatosDeImpresora().copy(otrosPorPieza = 500.0)
        val t = Compartir.texto(success(i), i, "Soporte", "PLA", "Metropolitana de Santiago", "15-11-2026 14:30:00")
        assertTrue(t, t.contains("COSTO TOTAL: " + CostCalculator.formatClp(3889.99)))
        assertTrue(t, t.contains("COSTO UNITARIO: " + CostCalculator.formatClp(1944.995)))
        assertTrue(t, t.contains("Otros costos: " + CostCalculator.formatClp(1000.0)))
        assertTrue(t, t.contains("100 g de PLA"))
        assertFalse(t, t.contains("Mano de obra"))
        assertFalse(t, t.contains("Precio de venta"))
    }

    // ---------- v1.6: ganancia aparte, nombre del cálculo, imagen ----------

    private val fecha = "15-11-2026 14:30:00"

    @Test
    fun laGananciaSeCalculaSobreElCostoYNoCambiaElCostoTotal() {
        val sin = success(sinDatosDeImpresora())
        val con = success(sinDatosDeImpresora().copy(margenPct = 25.0))
        assertEquals(sin.total, con.total, 1e-9)                       // el costo no cambia
        assertEquals(sin.unitario * 0.25, con.gananciaUnitaria!!, 1e-9) // la ganancia es 25% del costo unitario
        assertEquals(sin.unitario * 1.25, con.precioVenta!!, 1e-9)
        assertEquals(con.gananciaUnitaria!! * 2.0, con.gananciaLote!!, 1e-9)
        assertNull(sin.gananciaUnitaria)
        assertNull(sin.precioVenta)
    }

    @Test
    fun conCeroPorCientoDeGananciaElPrecioDeVentaEsElCosto() {
        val b = success(sinDatosDeImpresora().copy(margenPct = 0.0))
        assertEquals(b.unitario, b.precioVenta!!, 1e-9)
        assertEquals(0.0, b.gananciaUnitaria!!, 1e-9)
    }

    @Test
    fun elImporteDeGananciaApareceAparteEnElResumen() {
        val i = sinDatosDeImpresora().copy(margenPct = 30.0)
        val filas = Compartir.filas(success(i), i, "Soporte", "PLA", null, fecha)
        val etiquetas = filas.map { it.etiqueta }
        assertTrue(etiquetas.any { it.contains("30% sobre el costo") })
        val gan = filas.first { it.etiqueta == "Ganancia por pieza" }
        assertEquals(Estilo.GANANCIA, gan.estilo)
        assertEquals(CostCalculator.formatClp(1444.995 * 0.3), gan.valor)
        assertTrue(filas.indexOfFirst { it.etiqueta == "COSTO UNITARIO" } < filas.indexOf(gan))
        assertTrue(etiquetas.contains("Precio de venta unitario (sin IVA)"))
        assertTrue(etiquetas.contains("Precio de venta del lote (sin IVA)"))
    }

    @Test
    fun sinPorcentajeDeGananciaElResumenNoTrajeSeccionDeGanancia() {
        val i = sinDatosDeImpresora()
        val etiquetas = Compartir.filas(success(i), i, "Soporte", "PLA", null, fecha).map { it.etiqueta }
        assertTrue(etiquetas.none { it.contains("Ganancia") })
    }

    @Test
    fun elNombreDelCalculoEncabezaElResumen() {
        val i = sinDatosDeImpresora()
        val filas = Compartir.filas(success(i), i, "  Soporte de audífonos  ", "PLA", null, fecha)
        assertEquals("Soporte de audífonos", filas.first().etiqueta)
        assertEquals(Estilo.TITULO, filas.first().estilo)
        assertTrue(Compartir.texto(success(i), i, "Soporte de audífonos", "PLA", null, fecha).startsWith("Soporte de audífonos"))
        assertEquals(Compartir.SIN_NOMBRE, Compartir.filas(success(i), i, "   ", null, null, fecha).first().etiqueta)
        assertEquals(Compartir.SIN_NOMBRE, Compartir.filas(success(i), i, null, null, null, fecha).first().etiqueta)
    }

    @Test
    fun nombreDeArchivoIdentificaElObjetoYEsSeguro() {
        assertEquals("Soporte_de_audífonos_20261115_143000", Compartir.nombreArchivo("Soporte de audífonos", "20261115_143000"))
        assertEquals("Llavero_Erretrez_20261115_143000", Compartir.nombreArchivo("Llavero/Erretrez?*", "20261115_143000"))
        assertEquals("calculo_20261115_143000", Compartir.nombreArchivo("   ", "20261115_143000"))
        assertEquals("calculo_x", Compartir.nombreArchivo(null, "x"))
        assertTrue(Compartir.nombreArchivo("a".repeat(200), "x").length <= 42)
    }

    @Test
    fun laNotaDelResumenDiceQueImpresoraSeUso() {
        val i = sinDatosDeImpresora()
        val nota = Compartir.filas(success(i), i, "X", null, null, fecha).last().etiqueta
        assertTrue(nota, nota.contains("489.990"))
        assertTrue(nota, nota.contains("5.000 h"))
    }

    // ---------- v1.8: sumas rápidas ----------

    @Test
    fun sumarUnaPiezaPartiendoVacioOdesdeUnValor() {
        assertEquals("1", Rapidos.sumar("", 1.0))
        assertEquals("1", Rapidos.sumar(null, 1.0))
        assertEquals("3", Rapidos.sumar("2", 1.0))
        assertEquals("1", Rapidos.sumar("texto", 1.0))   // un texto inválido cuenta como 0
        assertEquals("3.5", Rapidos.sumar("2,5", 1.0))   // admite coma y decimales
    }

    @Test
    fun sumarDiezMinutosSinLlegarASesenta() {
        assertEquals(Rapidos.Tiempo(null, "10"), Rapidos.sumarMinutos("", ""))
        assertEquals(Rapidos.Tiempo(null, "40"), Rapidos.sumarMinutos("2", "30"))
        assertEquals(Rapidos.Tiempo(null, "59"), Rapidos.sumarMinutos("", "49"))
    }

    @Test
    fun alLlegarASesentaMinutosPasaUnaHoraALaCasillaDeHoras() {
        assertEquals(Rapidos.Tiempo("1", "0"), Rapidos.sumarMinutos("", "50"))
        assertEquals(Rapidos.Tiempo("6", "5"), Rapidos.sumarMinutos("5", "55"))
        assertEquals(Rapidos.Tiempo("3", "0"), Rapidos.sumarMinutos("2", "50"))
        assertEquals(Rapidos.Tiempo("3", "50"), Rapidos.sumarMinutos("2", "100"))  // 110 min = 1 h 50 min
    }

    @Test
    fun elTiempoSumadoConBotonesEsUnTiempoValido() {
        var h = ""
        var m = ""
        repeat(8) {
            val t = Rapidos.sumarMinutos(h, m)
            t.horas?.let { h = it }
            m = t.minutos
        }
        assertEquals("1", h)      // 80 min = 1 h 20 min
        assertEquals("20", m)
        val b = success(sinDatosDeImpresora().copy(
            horas = CostCalculator.parseNumber(h), minutos = CostCalculator.parseNumber(m)))
        assertEquals(80.0 / 60.0, b.horasTotales, 1e-9)
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
        assertEquals(489990.0, Suggested.PRECIO_IMPRESORA, delta)
        assertEquals(5000.0, Suggested.VIDA_UTIL_HORAS, delta)
        assertEquals(50.0, Suggested.MANTENIMIENTO_POR_HORA, delta)
    }

    @Test
    fun textoPlanoDeSugeridosSeInterpretaComoElMismoNumero() {
        assertEquals("489990", Suggested.plain(Suggested.PRECIO_IMPRESORA))
        assertEquals("5000", Suggested.plain(Suggested.VIDA_UTIL_HORAS))
        assertEquals("50", Suggested.plain(Suggested.MANTENIMIENTO_POR_HORA))
        assertEquals(489990.0, CostCalculator.parseNumber(Suggested.plain(Suggested.PRECIO_IMPRESORA))!!, delta)
        assertEquals(5000.0, CostCalculator.parseNumber(Suggested.plain(Suggested.VIDA_UTIL_HORAS))!!, delta)
        assertEquals(50.0, CostCalculator.parseNumber(Suggested.plain(Suggested.MANTENIMIENTO_POR_HORA))!!, delta)
    }

    @Test
    fun calcularConLosSugeridosEsValidoYCorrecto() {
        // 10 h: depreciación = 489990/5000*10 = 979,98; mantenimiento = 50*10 = 500
        val b = success(
            valid().copy(
                horas = 10.0,
                minutos = 0.0,
                precioImpresora = Suggested.PRECIO_IMPRESORA,
                vidaUtilHoras = Suggested.VIDA_UTIL_HORAS,
                mantenimientoPorHora = Suggested.MANTENIMIENTO_POR_HORA
            )
        )
        assertEquals(979.98, b.depreciacion, 1e-9)
        assertEquals(500.0, b.mantenimiento, delta)
    }

    @Test
    fun sugeridosConSeparadorDeMilesChileno() {
        assertEquals("5.000", Suggested.grouped(5000.0))
        assertEquals("489.990", Suggested.grouped(489990.0))
        assertEquals("50", Suggested.grouped(50.0))
    }

    @Test
    fun formatoClpNoUsaDecimalesYLlevaSimboloDePeso() {
        val s = CostCalculator.formatClp(1234567.4)
        assertTrue("Debe contener el símbolo \$: $s", s.contains("$"))
        assertFalse("No debe tener coma decimal: $s", s.contains(","))
        assertTrue("Debe contener los dígitos: $s", s.replace(".", "").contains("1234567"))
    }
}
