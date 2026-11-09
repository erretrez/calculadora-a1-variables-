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
        assertEquals(Field.values().size, f.fieldErrors.size)
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
    fun formatoClpNoUsaDecimalesYLlevaSimboloDePeso() {
        val s = CostCalculator.formatClp(1234567.4)
        assertTrue("Debe contener el símbolo \$: $s", s.contains("$"))
        assertFalse("No debe tener coma decimal: $s", s.contains(","))
        assertTrue("Debe contener los dígitos: $s", s.replace(".", "").contains("1234567"))
    }
}
