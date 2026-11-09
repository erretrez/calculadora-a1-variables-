package cl.erretrez.calculadora

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var inputs: Map<Field, EditText>
    private lateinit var tvStatus: TextView
    private lateinit var tvMaterial: TextView
    private lateinit var tvElectricidad: TextView
    private lateinit var tvDepreciacion: TextView
    private lateinit var tvMantenimiento: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvUnitario: TextView

    /** Evita recalcular mientras LIMPIAR vacía los campos uno por uno. */
    private var suspendWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputs = linkedMapOf(
            Field.PRECIO_KG to findViewById<EditText>(R.id.et_precio_kg),
            Field.GRAMOS to findViewById<EditText>(R.id.et_gramos),
            Field.PIEZAS to findViewById<EditText>(R.id.et_piezas),
            Field.HORAS to findViewById<EditText>(R.id.et_horas),
            Field.MINUTOS to findViewById<EditText>(R.id.et_minutos),
            Field.WATTS to findViewById<EditText>(R.id.et_watts),
            Field.PRECIO_KWH to findViewById<EditText>(R.id.et_precio_kwh),
            Field.PRECIO_IMPRESORA to findViewById<EditText>(R.id.et_precio_impresora),
            Field.VIDA_UTIL to findViewById<EditText>(R.id.et_vida_util),
            Field.MANTENIMIENTO to findViewById<EditText>(R.id.et_mantenimiento)
        )

        tvStatus = findViewById(R.id.tv_status)
        tvMaterial = findViewById(R.id.tv_material)
        tvElectricidad = findViewById(R.id.tv_electricidad)
        tvDepreciacion = findViewById(R.id.tv_depreciacion)
        tvMantenimiento = findViewById(R.id.tv_mantenimiento)
        tvTotal = findViewById(R.id.tv_total)
        tvUnitario = findViewById(R.id.tv_unitario)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suspendWatchers) recalculate(showErrors = false)
            }
        }
        inputs.values.forEach { it.addTextChangedListener(watcher) }

        findViewById<Button>(R.id.btn_calcular).setOnClickListener {
            hideKeyboard()
            recalculate(showErrors = true)
        }
        findViewById<Button>(R.id.btn_limpiar).setOnClickListener { clearAll() }
    }

    private fun readInputs(): CostInputs {
        fun value(field: Field): Double? = CostCalculator.parseNumber(inputs.getValue(field).text?.toString())
        return CostInputs(
            precioKg = value(Field.PRECIO_KG),
            gramos = value(Field.GRAMOS),
            piezas = value(Field.PIEZAS),
            horas = value(Field.HORAS),
            minutos = value(Field.MINUTOS),
            watts = value(Field.WATTS),
            precioKwh = value(Field.PRECIO_KWH),
            precioImpresora = value(Field.PRECIO_IMPRESORA),
            vidaUtilHoras = value(Field.VIDA_UTIL),
            mantenimientoPorHora = value(Field.MANTENIMIENTO)
        )
    }

    /**
     * @param showErrors true al pulsar CALCULAR: marca cada campo inválido.
     * En el cálculo automático (al escribir) solo se actualiza el resultado, sin marcar errores.
     */
    private fun recalculate(showErrors: Boolean) {
        inputs.values.forEach { it.error = null }

        when (val outcome = CostCalculator.calculate(readInputs())) {
            is Outcome.Success -> {
                showResults(outcome.breakdown)
                tvStatus.setText(R.string.status_ok)
            }
            is Outcome.Failure -> {
                clearResults()
                if (showErrors) {
                    outcome.fieldErrors.forEach { (field, message) ->
                        inputs.getValue(field).error = message
                    }
                    tvStatus.text = outcome.general ?: getString(R.string.status_incompleto)
                    outcome.fieldErrors.keys.firstOrNull()?.let { inputs.getValue(it).requestFocus() }
                } else {
                    tvStatus.text = outcome.general ?: getString(R.string.status_inicial)
                }
            }
        }
    }

    private fun showResults(b: CostBreakdown) {
        tvMaterial.text = CostCalculator.formatClp(b.material)
        tvElectricidad.text = CostCalculator.formatClp(b.electricidad)
        tvDepreciacion.text = CostCalculator.formatClp(b.depreciacion)
        tvMantenimiento.text = CostCalculator.formatClp(b.mantenimiento)
        tvTotal.text = CostCalculator.formatClp(b.total)
        tvUnitario.text = CostCalculator.formatClp(b.unitario)
    }

    private fun clearResults() {
        val empty = getString(R.string.vacio)
        listOf(tvMaterial, tvElectricidad, tvDepreciacion, tvMantenimiento, tvTotal, tvUnitario)
            .forEach { it.text = empty }
    }

    private fun clearAll() {
        suspendWatchers = true
        inputs.values.forEach {
            it.setText("")
            it.error = null
        }
        suspendWatchers = false
        clearResults()
        tvStatus.setText(R.string.status_inicial)
        hideKeyboard()
        inputs.getValue(Field.PRECIO_KG).requestFocus()
    }

    private fun hideKeyboard() {
        val view: View = currentFocus ?: window.decorView
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
