package cl.erretrez.calculadora

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var inputs: Map<Field, EditText>
    private lateinit var tvStatus: TextView
    private lateinit var tvMaterial: TextView
    private lateinit var tvElectricidad: TextView
    private lateinit var tvDepreciacion: TextView
    private lateinit var tvMantenimiento: TextView
    private lateinit var tvManoObra: TextView
    private lateinit var tvMerma: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvUnitario: TextView
    private lateinit var tvPrecioVenta: TextView
    private lateinit var tvPrecioIva: TextView
    private lateinit var tvGanancia: TextView
    private lateinit var tvCalculadoCon: TextView
    private lateinit var tvOtros: TextView
    private lateinit var tvPorGramo: TextView
    private lateinit var tvPorHora: TextView
    private lateinit var tvBarraTotal: TextView
    private lateinit var tvBarraDetalle: TextView
    private lateinit var tvBarraHora: TextView
    private lateinit var btnCompartir: Button
    private var textoCompartir: String? = null
    private lateinit var tvCuadra: TextView
    private lateinit var cbIva: CheckBox
    private lateinit var prefs: SharedPreferences

    // Material (consumo de la impresora)
    private lateinit var spMaterial: Spinner
    private lateinit var tvMaterialInfo: TextView
    private lateinit var llWattsOtro: View
    private var wattsAuto = false

    // Precio del kWh: región (tabla) y, solo si hace falta, precio manual / boleta
    private lateinit var spRegion: Spinner
    private lateinit var tvRegionInfo: TextView
    private lateinit var tvRegionRef: TextView
    private lateinit var llKwhManual: View
    private lateinit var llTabla: LinearLayout
    private lateinit var btnTabla: Button
    private lateinit var etBoletaMonto: EditText
    private lateinit var etBoletaKwh: EditText
    private lateinit var tvBoletaResultado: TextView
    private lateinit var btnUsarBoleta: Button
    private var boletaPrecio: Double? = null
    private var kwhAuto = false
    private var settingPrice = false
    private val filasTabla = mutableListOf<Pair<TextView, TextView>>()

    private var calcCount = 0
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("es-CL"))

    // Ubicación (solo se usa al tocar DETECTAR MI REGIÓN)
    private val handler = Handler(Looper.getMainLooper())
    private var searchingLocation = false
    private var staleLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            stopLocationSearch()
            applyLocation(location)
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val locationTimeout = Runnable {
        stopLocationSearch()
        val stale = staleLocation
        if (stale != null) {
            applyLocation(stale)
            tvRegionInfo.text = tvRegionInfo.text.toString() + "\n" + getString(R.string.ubicacion_antigua)
        } else {
            tvRegionInfo.setText(R.string.ubicacion_timeout)
        }
    }

    /** Evita recalcular mientras LIMPIAR vacía los campos uno por uno. */
    private var suspendWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dark = if (prefs.contains(KEY_DARK)) {
            prefs.getBoolean(KEY_DARK, false)
        } else {
            // Primera vez: se sigue el modo del teléfono; desde que el usuario toca el interruptor se recuerda su elección.
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        setTheme(if (dark) R.style.TemaOscuro else R.style.TemaClaro)
        setContentView(R.layout.activity_main)

        inputs = linkedMapOf(
            Field.PRECIO_KG to findViewById<EditText>(R.id.et_precio_kg),
            Field.GRAMOS to findViewById<EditText>(R.id.et_gramos),
            Field.PIEZAS to findViewById<EditText>(R.id.et_piezas),
            Field.HORAS to findViewById<EditText>(R.id.et_horas),
            Field.MINUTOS to findViewById<EditText>(R.id.et_minutos),
            Field.WATTS to findViewById<EditText>(R.id.et_watts),
            Field.PRECIO_KWH to findViewById<EditText>(R.id.et_precio_kwh),
            Field.HORAS_TRABAJO to findViewById<EditText>(R.id.et_horas_trabajo),
            Field.COSTO_HORA_TRABAJO to findViewById<EditText>(R.id.et_costo_hora_trabajo),
            Field.MERMA to findViewById<EditText>(R.id.et_merma),
            Field.OTROS_PIEZA to findViewById<EditText>(R.id.et_otros),
            Field.MARGEN to findViewById<EditText>(R.id.et_margen)
        )

        tvStatus = findViewById(R.id.tv_status)
        tvMaterial = findViewById(R.id.tv_material)
        tvElectricidad = findViewById(R.id.tv_electricidad)
        tvDepreciacion = findViewById(R.id.tv_depreciacion)
        tvMantenimiento = findViewById(R.id.tv_mantenimiento)
        tvManoObra = findViewById(R.id.tv_mano_obra)
        tvMerma = findViewById(R.id.tv_merma)
        tvTotal = findViewById(R.id.tv_total)
        tvUnitario = findViewById(R.id.tv_unitario)
        tvPrecioVenta = findViewById(R.id.tv_precio_venta)
        tvPrecioIva = findViewById(R.id.tv_precio_iva)
        tvGanancia = findViewById(R.id.tv_ganancia)
        tvCalculadoCon = findViewById(R.id.tv_calculado_con)
        tvOtros = findViewById(R.id.tv_otros)
        tvPorGramo = findViewById(R.id.tv_por_gramo)
        tvPorHora = findViewById(R.id.tv_por_hora)
        tvBarraTotal = findViewById(R.id.tv_barra_total)
        tvBarraDetalle = findViewById(R.id.tv_barra_detalle)
        tvBarraHora = findViewById(R.id.tv_barra_hora)
        btnCompartir = findViewById(R.id.btn_compartir)
        btnCompartir.setOnClickListener { compartir() }
        showBar(null, 0, null)
        tvCuadra = findViewById(R.id.tv_cuadra)
        cbIva = findViewById(R.id.cb_iva)

        val swOscuro = findViewById<Switch>(R.id.sw_oscuro)
        swOscuro.isChecked = dark
        swOscuro.setOnCheckedChangeListener { _, checked ->
            // commit(): queda en disco antes de recrear la pantalla con el otro tema.
            prefs.edit().putBoolean(KEY_DARK, checked).commit()
            recreate()
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suspendWatchers) recalculate(showErrors = false)
            }
        }
        inputs.values.forEach { it.addTextChangedListener(watcher) }

        // Un campo con valor cambia de color (estado "activado" del fondo): se ve de un vistazo qué ya se ingresó.
        val marcarLlenos = listOf(
            findViewById<EditText>(R.id.et_boleta_monto), findViewById<EditText>(R.id.et_boleta_kwh)
        ) + inputs.values
        marcarLlenos.forEach { box ->
            box.isActivated = !box.text.isNullOrBlank()
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    box.isActivated = !s.isNullOrBlank()
                }
            })
        }
        cbIva.setOnCheckedChangeListener { _, _ ->
            if (!suspendWatchers) recalculate(showErrors = false)
        }

        // Valores fijos de la impresora: solo se muestran en el resumen de resultados.
        findViewById<TextView>(R.id.tv_fijos).text = getString(
            R.string.fijos_resumen,
            CostCalculator.formatClp(Suggested.PRECIO_IMPRESORA),
            Suggested.grouped(Suggested.VIDA_UTIL_HORAS),
            CostCalculator.formatClp(Suggested.MANTENIMIENTO_POR_HORA)
        )

        setupMaterial()
        setupBoleta()
        setupRegion()

        findViewById<Button>(R.id.btn_calcular).setOnClickListener {
            hideKeyboard()
            recalculate(showErrors = true)
        }
        findViewById<Button>(R.id.btn_limpiar).setOnClickListener { clearAll() }
    }

    override fun onStop() {
        stopLocationSearch()
        super.onStop()
    }

    // ------------------------------------------------------------------ cálculo

    private fun readInputs(): CostInputs {
        fun raw(field: Field): String? = inputs.getValue(field).text?.toString()
        fun value(field: Field): Double? = CostCalculator.parseNumber(raw(field))

        // Un opcional con texto que no es un número válido se informa; no se ignora en silencio.
        val invalidOptional = Field.values()
            .filter { it.optional && !raw(it).isNullOrBlank() && value(it) == null }
            .toSet()

        // precioImpresora, vidaUtilHoras y mantenimientoPorHora usan sus valores fijos.
        return CostInputs(
            precioKg = value(Field.PRECIO_KG),
            gramos = value(Field.GRAMOS),
            piezas = value(Field.PIEZAS),
            horas = value(Field.HORAS),
            minutos = value(Field.MINUTOS),
            watts = value(Field.WATTS),
            precioKwh = value(Field.PRECIO_KWH),
            horasTrabajo = value(Field.HORAS_TRABAJO),
            costoHoraTrabajo = value(Field.COSTO_HORA_TRABAJO),
            mermaPct = value(Field.MERMA),
            margenPct = value(Field.MARGEN),
            otrosPorPieza = value(Field.OTROS_PIEZA),
            incluirIva = cbIva.isChecked,
            invalidOptional = invalidOptional
        )
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun selectedMaterial(): Material? = Materiales.todos.getOrNull(spMaterial.selectedItemPosition - 1)

    /** Nombre para mostrar en "Calculado con": el material elegido (no la opción "Otro"). */
    private fun materialName(): String? = selectedMaterial()?.takeIf { it.watts != null }?.nombre

    private fun regionName(): String? = when (spRegion.selectedItemPosition) {
        0 -> null
        MANUAL_POS -> getString(R.string.region_otra).substringBefore(" (")
        else -> selectedRegion()?.nombre
    }

    /**
     * @param showErrors true al pulsar CALCULAR: marca cada campo inválido.
     * En el cálculo automático (al escribir) solo se actualiza el resultado, sin marcar errores.
     */
    private fun recalculate(showErrors: Boolean) {
        inputs.values.forEach { it.error = null }
        val inputsNow = readInputs()
        showRegionRefText(error = false)
        showMaterialInfo(error = false)

        when (val outcome = CostCalculator.calculate(inputsNow)) {
            is Outcome.Success -> {
                val b = outcome.breakdown
                showResults(b)
                calcCount++
                tvStatus.text = getString(R.string.status_ok, calcCount, clock.format(Date()))
                tvCalculadoCon.text = getString(
                    R.string.calculado_con, Resumen.entradas(inputsNow, materialName(), regionName())
                )
                val ok = Resumen.partesSumanElTotal(b)
                tvCuadra.setText(if (ok) R.string.cuadra_ok else R.string.cuadra_mal)
                flashResults()
                showBar(b, calcCount, clock.format(Date()))
                textoCompartir = Compartir.texto(b, inputsNow, materialName(), regionName())
                btnCompartir.isEnabled = true
            }
            is Outcome.Failure -> {
                clearResults()
                if (showErrors) {
                    outcome.fieldErrors.forEach { (field, message) ->
                        val box = inputs[field]
                        when {
                            box != null && box.isShown -> box.error = message
                            field == Field.WATTS -> showMaterialInfo(error = true)
                            field == Field.PRECIO_KWH -> showRegionRefText(error = true)
                        }
                    }
                    tvStatus.text = outcome.general ?: getString(R.string.status_incompleto)
                    outcome.fieldErrors.keys.firstNotNullOfOrNull { f -> inputs[f]?.takeIf { it.isShown } }?.requestFocus()
                } else {
                    tvStatus.text = outcome.general ?: getString(R.string.status_inicial)
                }
            }
        }
    }

    /** Parpadeo breve de los totales: señal visual de que se acaba de recalcular. */
    private fun flashResults() {
        listOf(tvTotal, tvUnitario).forEach {
            it.animate().cancel()
            it.alpha = 0.25f
            it.animate().alpha(1f).setDuration(450).start()
        }
    }

    private fun showResults(b: CostBreakdown) {
        val empty = getString(R.string.vacio)
        tvMaterial.text = CostCalculator.formatClp(b.material)
        tvElectricidad.text = CostCalculator.formatClp(b.electricidad)
        tvDepreciacion.text = CostCalculator.formatClp(b.depreciacion)
        tvMantenimiento.text = CostCalculator.formatClp(b.mantenimiento)
        tvManoObra.text = CostCalculator.formatClp(b.manoObra)
        tvMerma.text = CostCalculator.formatClp(b.merma)
        tvTotal.text = CostCalculator.formatClp(b.total)
        tvUnitario.text = CostCalculator.formatClp(b.unitario)
        tvPrecioVenta.text = b.precioVenta?.let { CostCalculator.formatClp(it) } ?: empty
        tvPrecioIva.text = b.precioConIva?.let { CostCalculator.formatClp(it) } ?: empty
        tvGanancia.text = b.gananciaLote?.let { CostCalculator.formatClp(it) } ?: empty
        tvOtros.text = CostCalculator.formatClp(b.otros)
        tvPorGramo.text = b.costoPorGramo?.let { CostCalculator.formatClp(it) } ?: empty
        tvPorHora.text = CostCalculator.formatClp(b.costoPorHora)
    }

    /** Barra fija inferior: total y unitario siempre visibles, con el número de cálculo y su hora. */
    private fun showBar(b: CostBreakdown?, n: Int, hora: String?) {
        if (b == null) {
            tvBarraTotal.setText(R.string.barra_vacia)
            tvBarraDetalle.setText(R.string.barra_sin_calculo)
            tvBarraHora.text = ""
        } else {
            tvBarraTotal.text = getString(
                R.string.barra_total, CostCalculator.formatClp(b.total), CostCalculator.formatClp(b.unitario)
            )
            tvBarraDetalle.text = getString(R.string.barra_detalle, n)
            tvBarraHora.text = hora
        }
    }

    private fun compartir() {
        val text = textoCompartir ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.compartir_titulo)))
    }

    private fun clearResults() {
        val empty = getString(R.string.vacio)
        listOf(
            tvMaterial, tvElectricidad, tvDepreciacion, tvMantenimiento, tvManoObra, tvMerma,
            tvTotal, tvUnitario, tvPrecioVenta, tvPrecioIva, tvGanancia, tvOtros, tvPorGramo, tvPorHora
        ).forEach { it.text = empty }
        showBar(null, 0, null)
        textoCompartir = null
        btnCompartir.isEnabled = false
        tvCalculadoCon.text = ""
        tvCuadra.text = ""
    }

    private fun clearAll() {
        stopLocationSearch()
        suspendWatchers = true
        inputs.values.forEach {
            it.setText("")
            it.error = null
        }
        etBoletaMonto.setText("")
        etBoletaKwh.setText("")
        cbIva.isChecked = false
        // El material es de cada impresión: se vacía. La región es un ajuste que se recuerda: se conserva.
        spMaterial.setSelection(0)
        suspendWatchers = false
        applyMaterialState()
        refreshBoleta()
        applyRegionState()
        tvRegionInfo.setText(R.string.region_info_inicial)
        clearResults()
        tvStatus.setText(R.string.status_inicial)
        hideKeyboard()
        inputs.getValue(Field.PRECIO_KG).requestFocus()
    }

    private fun putInField(field: Field, text: String) {
        val editText = inputs.getValue(field)
        editText.setText(text)
        editText.setSelection(editText.text.length)
    }

    // ------------------------------------------------- material → consumo (watts)

    private fun setupMaterial() {
        spMaterial = findViewById(R.id.sp_material)
        tvMaterialInfo = findViewById(R.id.tv_material_info)
        llWattsOtro = findViewById(R.id.ll_watts_otro)

        val names = listOf(getString(R.string.material_placeholder)) + Materiales.todos.map { Materiales.etiqueta(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMaterial.adapter = adapter
        spMaterial.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyMaterialState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                applyMaterialState()
            }
        }
        showMaterialInfo(error = false)
    }

    /** Aplica el material elegido: pone sus watts, o muestra el campo propio con "Otro". */
    private fun applyMaterialState() {
        val m = selectedMaterial()
        val watts = m?.watts
        val field = inputs.getValue(Field.WATTS)
        when {
            m == null -> {
                llWattsOtro.visibility = View.GONE
                if (wattsAuto || field.text.isNullOrEmpty().not()) field.setText("")
                wattsAuto = false
            }
            watts != null -> {
                llWattsOtro.visibility = View.GONE
                val text = Suggested.plain(watts)
                if (field.text?.toString() != text) field.setText(text)
                wattsAuto = true
            }
            else -> {
                llWattsOtro.visibility = View.VISIBLE
                if (wattsAuto) field.setText("")
                wattsAuto = false
            }
        }
        showMaterialInfo(error = false)
    }

    private fun showMaterialInfo(error: Boolean) {
        val m = selectedMaterial()
        val watts = m?.watts
        tvMaterialInfo.text = when {
            error -> getString(R.string.material_falta)
            m == null -> getString(R.string.material_info_ninguno)
            watts == null -> getString(R.string.material_info_otro)
            else -> getString(R.string.material_info, Suggested.plain(watts), m.fuente)
        }
        tvMaterialInfo.setTextColor(themeColor(if (error) R.attr.cError else R.attr.cTextoSuave))
    }

    // ------------------------------------------------- precio kWh: tabla por región

    private fun setupRegion() {
        spRegion = findViewById(R.id.sp_region)
        tvRegionInfo = findViewById(R.id.tv_region_info)
        tvRegionRef = findViewById(R.id.tv_region_ref)
        llKwhManual = findViewById(R.id.ll_kwh_manual)
        llTabla = findViewById(R.id.ll_tabla)
        btnTabla = findViewById(R.id.btn_tabla)

        val sinDato = getString(R.string.region_sin_dato_corto)
        val names = listOf(getString(R.string.region_placeholder)) +
            Regiones.todas.map { r ->
                r.nombre + " \u2014 " + (r.referenciaKwh?.let { CostCalculator.formatClp(it) } ?: sinDato)
            } + getString(R.string.region_otra)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRegion.adapter = adapter

        // La región elegida se recuerda entre usos (así no hay que elegirla en cada cálculo).
        val saved = prefs.getString(KEY_REGION, null)
        val pos = when {
            saved == MANUAL_TOKEN -> MANUAL_POS
            else -> Regiones.todas.indexOfFirst { it.nombre == saved }.let { if (it >= 0) it + 1 else 0 }
        }
        spRegion.setSelection(pos)
        spRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyRegionState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                applyRegionState()
            }
        }

        // Precio manual: se recuerda solo mientras se usa el modo manual.
        inputs.getValue(Field.PRECIO_KWH).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (settingPrice || kwhAuto || llKwhManual.visibility != View.VISIBLE) return
                val text = s?.toString()?.trim().orEmpty()
                prefs.edit().apply {
                    if (text.isEmpty()) remove(KEY_MANUAL) else putString(KEY_MANUAL, text)
                }.commit()
            }
        })

        findViewById<Button>(R.id.btn_detectar).setOnClickListener {
            hideKeyboard()
            detectRegion()
        }
        buildTable()
        btnTabla.setOnClickListener {
            val show = llTabla.visibility != View.VISIBLE
            llTabla.visibility = if (show) View.VISIBLE else View.GONE
            btnTabla.setText(if (show) R.string.btn_ocultar_tabla else R.string.btn_ver_tabla)
        }
        applyRegionState()
    }

    private fun selectedRegion(): Region? = Regiones.todas.getOrNull(spRegion.selectedItemPosition - 1)

    private fun setPrice(text: String) {
        val field = inputs.getValue(Field.PRECIO_KWH)
        if (field.text?.toString() == text) return
        settingPrice = true
        field.setText(text)
        settingPrice = false
    }

    /** Pone el precio del kWh según la región elegida y muestra u oculta el campo manual. */
    private fun applyRegionState() {
        val pos = spRegion.selectedItemPosition
        val region = selectedRegion()
        val ref = region?.referenciaKwh
        val manualMode = pos == MANUAL_POS || (region != null && ref == null)
        llKwhManual.visibility = if (manualMode) View.VISIBLE else View.GONE

        when {
            ref != null -> {
                setPrice(Suggested.plain(ref))
                kwhAuto = true
            }
            manualMode -> {
                if (kwhAuto || inputs.getValue(Field.PRECIO_KWH).text.isNullOrEmpty()) {
                    setPrice(prefs.getString(KEY_MANUAL, null).orEmpty())
                }
                kwhAuto = false
            }
            else -> {
                setPrice("")
                kwhAuto = false
            }
        }

        // Se recuerda la región (o "Otra tarifa"); sin elección no se guarda nada.
        val token = when {
            pos == MANUAL_POS -> MANUAL_TOKEN
            region != null -> region.nombre
            else -> null
        }
        prefs.edit().apply { if (token == null) remove(KEY_REGION) else putString(KEY_REGION, token) }.commit()

        showRegionRefText(error = false)
        refreshTable()
        if (!suspendWatchers) recalculate(showErrors = false)
    }

    private fun showRegionRefText(error: Boolean) {
        val region = selectedRegion()
        val ref = region?.referenciaKwh
        tvRegionRef.text = when {
            error -> getString(R.string.region_falta)
            spRegion.selectedItemPosition == MANUAL_POS -> getString(R.string.region_ref_otra)
            region == null -> getString(R.string.region_ref_ninguna)
            ref == null -> getString(R.string.region_ref_sin_dato)
            else -> getString(R.string.region_ref, CostCalculator.formatClp(ref), Regiones.REFERENCIA_FECHA)
        }
        tvRegionRef.setTextColor(themeColor(if (error) R.attr.cError else R.attr.cTextoSuave))
    }

    /** Cuadro de valores por región: una fila por región; tocarla la elige. */
    private fun buildTable() {
        val dp = resources.displayMetrics.density
        llTabla.removeAllViews()
        filasTabla.clear()
        val title = TextView(this).apply {
            text = getString(R.string.tabla_titulo, Regiones.REFERENCIA_FECHA)
            setTextColor(themeColor(R.attr.cTextoSuave))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        llTabla.addView(title)
        Regiones.todas.forEachIndexed { index, r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (44 * dp).toInt()
                isClickable = true
                setOnClickListener { spRegion.setSelection(index + 1) }
            }
            val name = TextView(this).apply {
                text = r.nombre
                setTextColor(themeColor(R.attr.cTexto))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val price = TextView(this).apply {
                text = r.referenciaKwh?.let { CostCalculator.formatClp(it) } ?: getString(R.string.tabla_sin_dato)
                setTextColor(themeColor(if (r.referenciaKwh != null) R.attr.cTexto else R.attr.cTextoSuave))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.END
            }
            row.addView(name)
            row.addView(price)
            llTabla.addView(row)
            filasTabla += name to price
        }
    }

    /** Resalta en negrita la fila de la región elegida. */
    private fun refreshTable() {
        val selected = spRegion.selectedItemPosition - 1
        filasTabla.forEachIndexed { i, (name, price) ->
            val style = if (i == selected) Typeface.BOLD else Typeface.NORMAL
            name.setTypeface(null, style)
            price.setTypeface(null, style)
        }
    }

    // ------------------------------------------------- precio kWh: boleta (solo en modo manual)

    private fun setupBoleta() {
        etBoletaMonto = findViewById(R.id.et_boleta_monto)
        etBoletaKwh = findViewById(R.id.et_boleta_kwh)
        tvBoletaResultado = findViewById(R.id.tv_boleta_resultado)
        btnUsarBoleta = findViewById(R.id.btn_usar_boleta)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suspendWatchers) refreshBoleta()
            }
        }
        etBoletaMonto.addTextChangedListener(watcher)
        etBoletaKwh.addTextChangedListener(watcher)

        btnUsarBoleta.setText(R.string.btn_usar)
        btnUsarBoleta.setOnClickListener {
            boletaPrecio?.let { putInField(Field.PRECIO_KWH, Suggested.plainRounded(it)) }
        }
        refreshBoleta()
    }

    private fun refreshBoleta() {
        val result = Boleta.precioKwh(
            CostCalculator.parseNumber(etBoletaMonto.text?.toString()),
            CostCalculator.parseNumber(etBoletaKwh.text?.toString())
        )
        boletaPrecio = result.precioKwh
        val price = result.precioKwh
        tvBoletaResultado.text = when {
            result.error != null -> result.error
            price != null -> getString(R.string.boleta_resultado, CostCalculator.formatClp(price))
            else -> getString(R.string.boleta_ayuda)
        }
        btnUsarBoleta.isEnabled = price != null
    }

    // ------------------------------------------------- ubicación

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun detectRegion() {
        if (hasLocationPermission()) {
            startLocationSearch()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startLocationSearch()
        } else {
            tvRegionInfo.setText(R.string.ubicacion_sin_permiso)
        }
    }

    private fun startLocationSearch() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        stopLocationSearch()
        staleLocation = null

        val enabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try {
                lm.isProviderEnabled(it)
            } catch (e: Exception) {
                false
            }
        }
        if (enabled.isEmpty()) {
            tvRegionInfo.setText(R.string.ubicacion_desactivada)
            return
        }

        // 1) Última ubicación conocida: si es reciente basta (solo se necesita la región).
        var best: Location? = null
        for (p in enabled + LocationManager.PASSIVE_PROVIDER) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (e: Exception) {
                // sin permiso para ese proveedor: se ignora
            }
        }
        val known = best
        if (known != null) {
            if (System.currentTimeMillis() - known.time < RECENT_MS) {
                applyLocation(known)
                return
            }
            staleLocation = known
        }

        // 2) Pedir una ubicación nueva (con tiempo máximo de espera).
        var registered = 0
        for (p in enabled) {
            try {
                lm.requestLocationUpdates(p, 0L, 0f, locationListener, Looper.getMainLooper())
                registered++
            } catch (e: Exception) {
                // p. ej. GPS sin permiso preciso: se usa solo la ubicación aproximada
            }
        }
        if (registered == 0) {
            tvRegionInfo.setText(R.string.ubicacion_sin_permiso)
            return
        }
        searchingLocation = true
        tvRegionInfo.setText(R.string.ubicacion_buscando)
        handler.postDelayed(locationTimeout, SEARCH_TIMEOUT_MS)
    }

    private fun stopLocationSearch() {
        handler.removeCallbacks(locationTimeout)
        if (searchingLocation) {
            try {
                (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locationListener)
            } catch (e: Exception) {
                // nada que quitar
            }
            searchingLocation = false
        }
    }

    private fun applyLocation(location: Location) {
        val region = Regiones.masCercana(location.latitude, location.longitude)
        if (region == null) {
            tvRegionInfo.setText(R.string.ubicacion_fuera_chile)
            return
        }
        spRegion.setSelection(Regiones.todas.indexOf(region) + 1)
        applyRegionState()
        tvRegionInfo.text = getString(R.string.region_detectada, region.nombre)
    }

    private fun hideKeyboard() {
        val view: View = currentFocus ?: window.decorView
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private companion object {
        const val REQ_LOCATION = 1001
        const val RECENT_MS = 2L * 60L * 60L * 1000L   // 2 horas
        const val SEARCH_TIMEOUT_MS = 30_000L
        const val PREFS = "ajustes"
        const val KEY_DARK = "oscuro"
        const val KEY_REGION = "region"
        const val KEY_MANUAL = "precio_manual"
        const val MANUAL_TOKEN = "__manual__"
        /** Última opción del menú de regiones: 0 = sin elegir, 1..16 = regiones, 17 = otra tarifa. */
        val MANUAL_POS = Regiones.todas.size + 1
    }
}
