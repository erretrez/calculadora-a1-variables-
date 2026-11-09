package cl.erretrez.calculadora

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView

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
    private lateinit var cbIva: CheckBox

    // Precio del kWh: región y boleta
    private lateinit var spRegion: Spinner
    private lateinit var tvRegionInfo: TextView
    private lateinit var tvRegionRef: TextView
    private lateinit var btnUsarRegion: Button
    private lateinit var etBoletaMonto: EditText
    private lateinit var etBoletaKwh: EditText
    private lateinit var tvBoletaResultado: TextView
    private lateinit var btnUsarBoleta: Button
    private var boletaPrecio: Double? = null

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
            Field.MANTENIMIENTO to findViewById<EditText>(R.id.et_mantenimiento),
            Field.HORAS_TRABAJO to findViewById<EditText>(R.id.et_horas_trabajo),
            Field.COSTO_HORA_TRABAJO to findViewById<EditText>(R.id.et_costo_hora_trabajo),
            Field.MERMA to findViewById<EditText>(R.id.et_merma),
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
        cbIva = findViewById(R.id.cb_iva)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suspendWatchers) recalculate(showErrors = false)
            }
        }
        inputs.values.forEach { it.addTextChangedListener(watcher) }
        cbIva.setOnCheckedChangeListener { _, _ ->
            if (!suspendWatchers) recalculate(showErrors = false)
        }

        // Valores sugeridos: texto fijo de referencia + botón USAR que lo copia al campo (editable).
        findViewById<TextView>(R.id.tv_sug_precio_impresora).text = getString(
            R.string.sug_precio_impresora, CostCalculator.formatClp(Suggested.PRECIO_IMPRESORA)
        )
        findViewById<TextView>(R.id.tv_sug_vida_util).text = getString(
            R.string.sug_vida_util, Suggested.grouped(Suggested.VIDA_UTIL_HORAS)
        )
        findViewById<TextView>(R.id.tv_sug_mantenimiento).text = getString(
            R.string.sug_mantenimiento, CostCalculator.formatClp(Suggested.MANTENIMIENTO_POR_HORA)
        )
        bindUsar(R.id.btn_usar_precio_impresora, Field.PRECIO_IMPRESORA, Suggested.PRECIO_IMPRESORA)
        bindUsar(R.id.btn_usar_vida_util, Field.VIDA_UTIL, Suggested.VIDA_UTIL_HORAS)
        bindUsar(R.id.btn_usar_mantenimiento, Field.MANTENIMIENTO, Suggested.MANTENIMIENTO_POR_HORA)

        setupRegion()
        setupBoleta()

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
            mantenimientoPorHora = value(Field.MANTENIMIENTO),
            horasTrabajo = value(Field.HORAS_TRABAJO),
            costoHoraTrabajo = value(Field.COSTO_HORA_TRABAJO),
            mermaPct = value(Field.MERMA),
            margenPct = value(Field.MARGEN),
            incluirIva = cbIva.isChecked,
            invalidOptional = invalidOptional
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
    }

    private fun clearResults() {
        val empty = getString(R.string.vacio)
        listOf(
            tvMaterial, tvElectricidad, tvDepreciacion, tvMantenimiento, tvManoObra, tvMerma,
            tvTotal, tvUnitario, tvPrecioVenta, tvPrecioIva, tvGanancia
        ).forEach { it.text = empty }
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
        spRegion.setSelection(0)
        suspendWatchers = false
        refreshBoleta()
        updateRegionRef()
        tvRegionInfo.setText(R.string.region_info_inicial)
        clearResults()
        tvStatus.setText(R.string.status_inicial)
        hideKeyboard()
        inputs.getValue(Field.PRECIO_KG).requestFocus()
    }

    // ------------------------------------------------- sugeridos (impresora)

    private fun bindUsar(buttonId: Int, field: Field, value: Double) {
        findViewById<Button>(buttonId).setOnClickListener {
            putInField(field, Suggested.plain(value))
        }
    }

    private fun putInField(field: Field, text: String) {
        val editText = inputs.getValue(field)
        editText.setText(text)
        editText.setSelection(editText.text.length)
    }

    // ------------------------------------------------- precio kWh: región

    private fun setupRegion() {
        spRegion = findViewById(R.id.sp_region)
        tvRegionInfo = findViewById(R.id.tv_region_info)
        tvRegionRef = findViewById(R.id.tv_region_ref)
        btnUsarRegion = findViewById(R.id.btn_usar_region)

        val names = listOf(getString(R.string.region_placeholder)) + Regiones.todas.map { it.nombre }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spRegion.adapter = adapter
        spRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateRegionRef()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateRegionRef()
            }
        }

        btnUsarRegion.setText(R.string.btn_usar)
        btnUsarRegion.setOnClickListener {
            selectedRegion()?.referenciaKwh?.let { putInField(Field.PRECIO_KWH, Suggested.plain(it)) }
        }
        findViewById<Button>(R.id.btn_detectar).setOnClickListener {
            hideKeyboard()
            detectRegion()
        }
        updateRegionRef()
    }

    private fun selectedRegion(): Region? = Regiones.todas.getOrNull(spRegion.selectedItemPosition - 1)

    private fun updateRegionRef() {
        val region = selectedRegion()
        val ref = region?.referenciaKwh
        when {
            region == null -> {
                tvRegionRef.setText(R.string.region_ref_ninguna)
                btnUsarRegion.isEnabled = false
            }
            ref == null -> {
                tvRegionRef.setText(R.string.region_ref_sin_dato)
                btnUsarRegion.isEnabled = false
            }
            else -> {
                tvRegionRef.text = getString(
                    R.string.region_ref, CostCalculator.formatClp(ref), Regiones.REFERENCIA_FECHA
                )
                btnUsarRegion.isEnabled = true
            }
        }
    }

    // ------------------------------------------------- precio kWh: boleta

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
        tvRegionInfo.text = getString(R.string.region_detectada, region.nombre)
        updateRegionRef()
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
    }
}
