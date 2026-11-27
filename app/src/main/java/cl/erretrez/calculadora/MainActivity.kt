package cl.erretrez.calculadora

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File
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
import android.widget.ScrollView
import android.content.res.ColorStateList
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
    private lateinit var tvCobroHoras: TextView
    private lateinit var tvPorCantidad: TextView
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
    private lateinit var tvGananciaUnit: TextView
    private lateinit var tvVentaLote: TextView
    private lateinit var tvVentaAyuda: TextView
    private lateinit var etNombre: EditText
    private lateinit var btnGuardarImagen: Button
    private lateinit var btnCompartir: Button
    private lateinit var btnCopiar: Button
    private var ultimoResultado: Triple<CostBreakdown, CostInputs, String>? = null
    private var accionPendiente: Int = 0
    private lateinit var tvCuadra: TextView
    private lateinit var cbIva: CheckBox
    private lateinit var prefs: SharedPreferences

    // Material (consumo de la impresora)
    private lateinit var spMaterial: Spinner
    private lateinit var tvMaterialInfo: TextView
    private lateinit var llWattsOtro: View
    private var wattsAuto = false
    private var materialAplicado: String? = null

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

    // Piezas adicionales (2.ª en adelante): cada una es otro ciclo de la máquina con su filamento, tiempo y consumo.
    private inner class Card(val slot: Int, val root: View) {
        private fun <T : View> bind(tag: String): T {
            val v = root.findViewWithTag<T>(tag)
            v.id = resources.getIdentifier("p${slot}_$tag", "id", packageName)
            return v
        }

        init {
            root.id = resources.getIdentifier("p${slot}_card", "id", packageName)
        }

        val titulo: TextView = bind("titulo")
        val quitar: Button = bind("quitar")
        val precio: EditText = bind("precio_kg")
        val gramos: EditText = bind("gramos")
        val horas: EditText = bind("horas")
        val minutos: EditText = bind("minutos")
        val watts: EditText = bind("watts")
        val material: Spinner = bind("material")
        val info: TextView = bind("info")
        val wattsBox: View = bind("watts_box")
        val masHoras: Button = bind("mas_horas")
        val masMinutos: Button = bind("mas_minutos")
        val tiempoBox: View = bind("tiempo_box")
        val perfil: Spinner = bind("perfil")
        var wattsAuto = false
        var materialAplicado: String? = null
        val cajas: List<EditText> get() = listOf(precio, gramos, horas, minutos, watts)
    }

    private val cards = mutableListOf<Card>()
    private lateinit var llPartesExtra: LinearLayout
    private lateinit var llDetalle: LinearLayout
    private lateinit var btnAgregarPieza: Button
    private lateinit var tvAyudaPiezas: TextView
    private lateinit var tvGramosTotal: TextView
    private lateinit var tvHorasTotal: TextView
    private lateinit var btnCalcular: Button

    // v2.2: tiempo global y perfiles de filamento
    private lateinit var swTiempoGlobal: Switch
    private lateinit var llTiempoPieza1: View
    private lateinit var llTiempoGlobal: View
    private lateinit var tvAyudaTiempo: TextView
    private lateinit var spPerfil: Spinner
    private lateinit var tvPctKilo: TextView
    private lateinit var tvPerfilesEstado: TextView
    private lateinit var btnRegistrar: Button
    private var perfiles: List<Perfil> = emptyList()
    private lateinit var svPrincipal: ScrollView
    private var calcTextColors: ColorStateList? = null

    private var calcCount = 0
    /** Hora del móvil, en su zona horaria y con su formato (24 h o 12 h). Se lee de nuevo en cada uso. */
    private fun horaActual(): String {
        val patron = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm:ss" else "hh:mm:ss a"
        return SimpleDateFormat(patron, Locale.getDefault()).format(Date())
    }

    /** Reloj en vivo de la barra inferior: permite comparar de un vistazo con la hora del teléfono. */
    private val ticker = object : Runnable {
        override fun run() {
            tvBarraHora.text = getString(R.string.barra_hora, horaActual())
            handler.postDelayed(this, 1000)
        }
    }

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

    private val recalcWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!suspendWatchers) recalculate(showErrors = false)
        }
    }

    /** Un campo con valor cambia de color (estado "activado" del fondo): se ve de un vistazo qué ya se ingresó. */
    private fun marcarLleno(box: EditText) {
        box.isActivated = !box.text.isNullOrBlank()
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                box.isActivated = !s.isNullOrBlank()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Qué piezas adicionales existían: al recrear la pantalla (p. ej. modo oscuro) se vuelven a crear
        // con los mismos ids y Android les devuelve el texto y el material.
        outState.putIntArray(KEY_SLOTS, cards.map { it.slot }.toIntArray())
    }

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
            Field.HORAS_GLOBAL to findViewById<EditText>(R.id.et_horas_global),
            Field.MINUTOS_GLOBAL to findViewById<EditText>(R.id.et_minutos_global),
            Field.WATTS to findViewById<EditText>(R.id.et_watts),
            Field.PRECIO_KWH to findViewById<EditText>(R.id.et_precio_kwh),
            Field.TARIFA_HORA to findViewById<EditText>(R.id.et_tarifa_hora),
            Field.DEPRECIACION_HORA to findViewById<EditText>(R.id.et_depreciacion_hora),
            Field.MANTENIMIENTO_HORA to findViewById<EditText>(R.id.et_mantenimiento_hora),
            Field.HORAS_TRABAJO to findViewById<EditText>(R.id.et_horas_trabajo),
            Field.COSTO_HORA_TRABAJO to findViewById<EditText>(R.id.et_costo_hora_trabajo),
            Field.MERMA to findViewById<EditText>(R.id.et_merma),
            Field.OTROS_PIEZA to findViewById<EditText>(R.id.et_otros),
            Field.MARGEN to findViewById<EditText>(R.id.et_margen)
        )

        btnCalcular = findViewById(R.id.btn_calcular)
        calcTextColors = btnCalcular.textColors
        svPrincipal = findViewById(R.id.sv_principal)
        tvGramosTotal = findViewById(R.id.tv_gramos_total)
        tvPctKilo = findViewById(R.id.tv_pct_kilo)
        tvPerfilesEstado = findViewById(R.id.tv_perfiles_estado)
        btnRegistrar = findViewById(R.id.btn_registrar_consumo)
        tvHorasTotal = findViewById(R.id.tv_horas_total)
        llPartesExtra = findViewById(R.id.ll_partes_extra)
        llDetalle = findViewById(R.id.ll_detalle_partes)
        btnAgregarPieza = findViewById(R.id.btn_agregar_pieza)
        tvAyudaPiezas = findViewById(R.id.tv_ayuda_piezas)
        btnAgregarPieza.setOnClickListener {
            hideKeyboard()
            if (cards.size + 1 < MAX_PIEZAS) {
                val slot = (2..MAX_PIEZAS).first { sl -> cards.none { it.slot == sl } }
                agregarPieza(slot, prefill = true).gramos.requestFocus()
            }
        }

        tvStatus = findViewById(R.id.tv_status)
        tvMaterial = findViewById(R.id.tv_material)
        tvElectricidad = findViewById(R.id.tv_electricidad)
        tvDepreciacion = findViewById(R.id.tv_depreciacion)
        tvMantenimiento = findViewById(R.id.tv_mantenimiento)
        tvManoObra = findViewById(R.id.tv_mano_obra)
        tvCobroHoras = findViewById(R.id.tv_cobro_horas)
        tvPorCantidad = findViewById(R.id.tv_por_cantidad)
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
        tvGananciaUnit = findViewById(R.id.tv_ganancia_unit)
        tvVentaLote = findViewById(R.id.tv_venta_lote)
        tvVentaAyuda = findViewById(R.id.tv_venta_ayuda)
        etNombre = findViewById(R.id.et_nombre)
        btnGuardarImagen = findViewById(R.id.btn_guardar_imagen)
        btnCompartir = findViewById(R.id.btn_compartir)
        btnCopiar = findViewById(R.id.btn_copiar)
        btnGuardarImagen.setOnClickListener { respaldo(ACCION_GUARDAR) }
        btnCompartir.setOnClickListener { respaldo(ACCION_COMPARTIR) }
        btnCopiar.setOnClickListener { copiarTexto() }
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

        inputs.values.forEach { it.addTextChangedListener(recalcWatcher) }

        val marcarLlenos = listOf(
            findViewById<EditText>(R.id.et_boleta_monto), findViewById<EditText>(R.id.et_boleta_kwh), etNombre
        ) + inputs.values
        marcarLlenos.forEach { marcarLleno(it) }
        cbIva.setOnCheckedChangeListener { _, _ ->
            if (!suspendWatchers) recalculate(showErrors = false)
        }

        // Valores fijos de la impresora: solo se muestran en el resumen de resultados.
        findViewById<TextView>(R.id.tv_fijos).text = getString(
            R.string.fijos_resumen,
            CostCalculator.formatClp(Suggested.PRECIO_IMPRESORA),
            Suggested.grouped(Suggested.VIDA_UTIL_HORAS),
            CostCalculator.formatClp(Suggested.MANTENIMIENTO_POR_HORA),
            Suggested.PRECIO_FECHA
        )

        // Botones de suma rápida: un toque suma una unidad a la casilla de arriba.
        findViewById<Button>(R.id.btn_mas_pieza).setOnClickListener { rapido { putInField(Field.PIEZAS, Rapidos.sumar(texto(Field.PIEZAS), 1.0)) } }
        findViewById<Button>(R.id.btn_mas_horas).setOnClickListener { rapido { putInField(Field.HORAS, Rapidos.sumar(texto(Field.HORAS), 1.0)) } }
        findViewById<Button>(R.id.btn_mas_minutos).setOnClickListener {
            rapido {
                val t = Rapidos.sumarMinutos(texto(Field.HORAS), texto(Field.MINUTOS))
                t.horas?.let { putInField(Field.HORAS, it) }
                putInField(Field.MINUTOS, t.minutos)
            }
        }

        findViewById<Button>(R.id.btn_mas_horas_global).setOnClickListener {
            rapido { putInField(Field.HORAS_GLOBAL, Rapidos.sumar(texto(Field.HORAS_GLOBAL), 1.0)) }
        }
        findViewById<Button>(R.id.btn_mas_minutos_global).setOnClickListener {
            rapido {
                val t = Rapidos.sumarMinutos(texto(Field.HORAS_GLOBAL), texto(Field.MINUTOS_GLOBAL))
                t.horas?.let { putInField(Field.HORAS_GLOBAL, it) }
                putInField(Field.MINUTOS_GLOBAL, t.minutos)
            }
        }

        setupParametros()
        setupTiempoGlobal()
        setupPerfiles()
        setupMaterial()
        setupBoleta()
        setupRegion()
        savedInstanceState?.getIntArray(KEY_SLOTS)?.forEach { agregarPieza(it, prefill = false) }
        actualizarBotonAgregar()

        btnCalcular.setOnClickListener {
            hideKeyboard()
            if (recalculate(showErrors = true)) {
                // El botón queda marcado hasta que se cambie algún dato, y la pantalla baja a los totales.
                marcarCalculado()
                scrollATotales()
            }
        }
        findViewById<Button>(R.id.btn_limpiar).setOnClickListener { clearAll() }
        findViewById<Button>(R.id.btn_subir).setOnClickListener { svPrincipal.smoothScrollTo(0, 0) }
    }

    override fun onResume() {
        super.onResume()
        ticker.run()
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
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
            tarifaHora = value(Field.TARIFA_HORA),
            depreciacionHora = value(Field.DEPRECIACION_HORA),
            mantenimientoHoraManual = value(Field.MANTENIMIENTO_HORA),
            horasTrabajo = value(Field.HORAS_TRABAJO),
            costoHoraTrabajo = value(Field.COSTO_HORA_TRABAJO),
            piezasFallidas = value(Field.MERMA),
            margenPct = value(Field.MARGEN),
            otrosPorPieza = value(Field.OTROS_PIEZA),
            incluirIva = cbIva.isChecked,
            invalidOptional = invalidOptional,
            tiempoGlobal = swTiempoGlobal.isChecked,
            horasGlobal = value(Field.HORAS_GLOBAL),
            minutosGlobal = value(Field.MINUTOS_GLOBAL),
            perfil = perfilDe(spPerfil),
            partesExtra = cards.map { c ->
                fun v(e: EditText) = CostCalculator.parseNumber(e.text?.toString())
                Parte(v(c.precio), v(c.gramos), v(c.horas), v(c.minutos), v(c.watts), perfilDe(c.perfil))
            }
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
    private fun recalculate(showErrors: Boolean): Boolean {
        var ok = false
        if (!showErrors) desmarcarCalculado()
        inputs.values.forEach { it.error = null }
        cards.forEach { c -> c.cajas.forEach { it.error = null }; showCardInfo(c, error = false) }
        val inputsNow = readInputs()
        showRegionRefText(error = false)
        showMaterialInfo(error = false)

        when (val outcome = CostCalculator.calculate(inputsNow)) {
            is Outcome.Success -> {
                ok = true
                val b = outcome.breakdown
                showResults(b, inputsNow)
                calcCount++
                val hora = horaActual()
                tvStatus.text = getString(R.string.status_ok, calcCount, hora)
                tvCalculadoCon.text = getString(
                    R.string.calculado_con, Resumen.entradas(inputsNow, materialName(), regionName())
                )
                val ok = Resumen.partesSumanElTotal(b)
                tvCuadra.setText(if (ok) R.string.cuadra_ok else R.string.cuadra_mal)
                flashResults()
                showBar(b, calcCount, hora)
                ultimoResultado = Triple(b, inputsNow, hora)
                mostrarPerfiles(inputsNow)
                listOf(btnGuardarImagen, btnCompartir, btnCopiar).forEach { it.isEnabled = true }
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
                    outcome.parteErrors.forEach { (idx, errs) ->
                        val c = cards.getOrNull(idx) ?: return@forEach
                        errs.forEach { (field, message) ->
                            when (field) {
                                Field.PRECIO_KG -> c.precio.error = message
                                Field.GRAMOS -> c.gramos.error = message
                                Field.HORAS -> c.horas.error = message
                                Field.MINUTOS -> c.minutos.error = message
                                Field.WATTS -> showCardInfo(c, error = true)
                                else -> Unit
                            }
                        }
                    }
                    tvStatus.text = outcome.general ?: getString(R.string.status_incompleto)
                    val primero = outcome.fieldErrors.keys.firstNotNullOfOrNull { f -> inputs[f]?.takeIf { it.isShown } }
                        ?: outcome.parteErrors.keys.firstOrNull()?.let { cards.getOrNull(it) }
                            ?.cajas?.firstOrNull { it.error != null }
                    primero?.requestFocus()
                } else {
                    tvStatus.text = outcome.general ?: getString(R.string.status_inicial)
                }
            }
        }
        return ok
    }

    /** CALCULAR queda en verde con una marca mientras el resultado corresponda a los datos escritos. */
    private fun marcarCalculado() {
        btnCalcular.setText(R.string.btn_calculado)
        btnCalcular.backgroundTintList = ColorStateList.valueOf(themeColor(R.attr.cBordeLleno))
        btnCalcular.setTextColor(themeColor(R.attr.cSobreAcento))
    }

    private fun desmarcarCalculado() {
        if (btnCalcular.text.toString() == getString(R.string.btn_calcular)) return
        btnCalcular.setText(R.string.btn_calcular)
        btnCalcular.backgroundTintList = null
        calcTextColors?.let { btnCalcular.setTextColor(it) }
    }

    /** Baja la pantalla hasta los totales (costo del lote y costo unitario). */
    private fun scrollATotales() {
        svPrincipal.post {
            val contenido = svPrincipal.getChildAt(0)
            var y = 0
            var v: View = tvTotal
            while (v !== contenido) {
                y += v.top
                v = v.parent as? View ?: break
            }
            // Un poco más arriba, para ver también las últimas filas del desglose.
            val margen = (150 * resources.displayMetrics.density).toInt()
            svPrincipal.smoothScrollTo(0, (y - margen).coerceAtLeast(0))
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

    private fun showResults(b: CostBreakdown, inputsNow: CostInputs) {
        val empty = getString(R.string.vacio)
        tvMaterial.text = CostCalculator.formatClp(b.material)
        tvElectricidad.text = CostCalculator.formatClp(b.electricidad)
        tvDepreciacion.text = CostCalculator.formatClp(b.depreciacion)
        tvMantenimiento.text = CostCalculator.formatClp(b.mantenimiento)
        tvManoObra.text = CostCalculator.formatClp(b.manoObra)
        tvCobroHoras.text = CostCalculator.formatClp(b.cobroHoras)
        tvPorCantidad.text = PorCantidad.tabla(inputsNow).joinToString("\n") {
            PorCantidad.etiqueta(it) + " · " + PorCantidad.valor(it)
        }
        tvMerma.text = CostCalculator.formatClp(b.merma)
        tvTotal.text = CostCalculator.formatClp(b.total)
        tvUnitario.text = CostCalculator.formatClp(b.unitario)
        tvPrecioVenta.text = b.precioVenta?.let { CostCalculator.formatClp(it) } ?: empty
        tvPrecioIva.text = b.precioConIva?.let { CostCalculator.formatClp(it) } ?: empty
        tvGanancia.text = b.gananciaLote?.let { CostCalculator.formatClp(it) } ?: empty
        tvGananciaUnit.text = b.gananciaUnitaria?.let { CostCalculator.formatClp(it) } ?: empty
        tvVentaLote.text = b.precioVentaLote?.let { CostCalculator.formatClp(it) } ?: empty
        tvVentaAyuda.text = b.margenPct?.let { getString(R.string.venta_aplicada, Suggested.plainRounded(it)) }
            ?: getString(R.string.venta_ayuda)
        tvOtros.text = CostCalculator.formatClp(b.otros)
        tvPorGramo.text = b.costoPorGramo?.let { CostCalculator.formatClp(it) } ?: empty
        tvPorHora.text = CostCalculator.formatClp(b.costoPorHora)
        tvGramosTotal.text = Suggested.plainRounded(b.gramosTotales) + " g"
        tvHorasTotal.text = Resumen.tiempo(b.horasTotales)
        tvPctKilo.text = Perfiles.textoPct(b.pctKiloTotal) + " (" + Suggested.plainRounded(b.gramosTotales) + " g)"
        showDetallePartes(b)
    }

    /** Con 2 o más piezas, lista el costo de cada una arriba de los totales. */
    private fun showDetallePartes(b: CostBreakdown) {
        llDetalle.removeAllViews()
        if (b.partes.size < 2) {
            llDetalle.visibility = View.GONE
            return
        }
        llDetalle.visibility = View.VISIBLE
        llDetalle.addView(TextView(this).apply {
            text = getString(R.string.sub_detalle_piezas)
            setTextColor(themeColor(R.attr.cAcento))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
        })
        b.partes.forEachIndexed { k, p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
            }
            row.addView(TextView(this, null, 0, R.style.ResultadoEtiqueta).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "Pieza " + (k + 1) + " · " + Suggested.plainRounded(p.gramos) + " g (" + Perfiles.textoPct(p.pctKilo) + ") · " + Resumen.tiempo(p.horas)
            })
            row.addView(TextView(this, null, 0, R.style.ResultadoValor).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                text = CostCalculator.formatClp(p.subtotal)
            })
            llDetalle.addView(row)
        }
    }

    /** Barra fija inferior: total y unitario siempre visibles, con el número de cálculo y su hora. */
    private fun showBar(b: CostBreakdown?, n: Int, hora: String?) {
        if (b == null) {
            tvBarraTotal.setText(R.string.barra_vacia)
            tvBarraDetalle.setText(R.string.barra_sin_calculo)
        } else {
            tvBarraTotal.text = getString(
                R.string.barra_total, CostCalculator.formatClp(b.total), CostCalculator.formatClp(b.unitario)
            )
            tvBarraDetalle.text = b.precioVenta?.let {
                getString(R.string.barra_venta, n, hora, CostCalculator.formatClp(it))
            } ?: getString(R.string.barra_detalle, n, hora)
        }
    }

    // ------------------------------------------------- respaldo: imagen, compartir, copiar

    private fun filasActuales(): List<Fila>? {
        val (b, i, hora) = ultimoResultado ?: return null
        val fecha = SimpleDateFormat("dd-MM-yyyy", Locale.forLanguageTag("es-CL")).format(Date()) + " " + hora
        return Compartir.filas(b, i, etNombre.text?.toString(), materialName(), regionName(), fecha)
    }

    private fun copiarTexto() {
        val (b, i, hora) = ultimoResultado ?: return
        val fecha = SimpleDateFormat("dd-MM-yyyy", Locale.forLanguageTag("es-CL")).format(Date()) + " " + hora
        val text = Compartir.texto(b, i, etNombre.text?.toString(), materialName(), regionName(), fecha)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.compartir_titulo), text))
        Toast.makeText(this, R.string.texto_copiado, Toast.LENGTH_SHORT).show()
    }

    /** Guarda la imagen del detalle en la galería y, si se pidió, la comparte junto con el texto. */
    private fun respaldo(accion: Int) {
        if (ultimoResultado == null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            accionPendiente = accion
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        val filas = filasActuales() ?: return
        val marca = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val archivo = Compartir.nombreArchivo(etNombre.text?.toString(), marca) + ".png"
        val uri = try {
            guardarPng(ResumenImagen.render(filas), archivo)
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            Toast.makeText(this, R.string.imagen_error, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.imagen_guardada, archivo), Toast.LENGTH_LONG).show()
        if (accion == ACCION_COMPARTIR) {
            val (b, i, hora) = ultimoResultado ?: return
            val texto = Compartir.texto(b, i, etNombre.text?.toString(), materialName(), regionName(), hora)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, texto)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.compartir_titulo)))
        }
    }

    private fun guardarPng(bmp: android.graphics.Bitmap, nombre: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CalculadoraCostos3D")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CalculadoraCostos3D")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, nombre).absolutePath)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } ?: false
        if (!ok) {
            contentResolver.delete(uri, null, null)
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }

    private fun clearResults() {
        val empty = getString(R.string.vacio)
        listOf(
            tvMaterial, tvElectricidad, tvDepreciacion, tvMantenimiento, tvManoObra, tvCobroHoras, tvMerma,
            tvTotal, tvUnitario, tvPrecioVenta, tvPrecioIva, tvGanancia, tvOtros, tvPorGramo, tvPorHora,
            tvGananciaUnit, tvVentaLote, tvGramosTotal, tvHorasTotal, tvPctKilo, tvPorCantidad
        ).forEach { it.text = empty }
        tvPerfilesEstado.visibility = View.GONE
        btnRegistrar.visibility = View.GONE
        tvVentaAyuda.setText(R.string.venta_ayuda)
        llDetalle.removeAllViews()
        llDetalle.visibility = View.GONE
        showBar(null, 0, null)
        ultimoResultado = null
        listOf(btnGuardarImagen, btnCompartir, btnCopiar).forEach { it.isEnabled = false }
        tvCalculadoCon.text = ""
        tvCuadra.text = ""
    }

    private fun clearAll() {
        stopLocationSearch()
        suspendWatchers = true
        inputs.entries.forEach { (campo, caja) ->
            // La tarifa, la depreciación y el mantenimiento son parámetros que se recuerdan al limpiar.
            if (campo !in PARAMETROS) caja.setText("")
            caja.error = null
        }
        etBoletaMonto.setText("")
        etBoletaKwh.setText("")
        etNombre.setText("")
        cbIva.isChecked = false
        desmarcarCalculado()
        cards.forEach { llPartesExtra.removeView(it.root) }
        cards.clear()
        prefs.edit().remove(KEY_REGISTRO).apply()
        spPerfil.setSelection(0)
        actualizarBotonAgregar()
        // El material y la región son ajustes que se recuerdan: se conservan al limpiar.
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

    private fun texto(field: Field): String? = inputs.getValue(field).text?.toString()

    /** Ejecuta una suma rápida con una vibración corta y sin teclado abierto. */
    private fun rapido(accion: () -> Unit) {
        hideKeyboard()
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        accion()
    }

    private fun putInField(field: Field, text: String) {
        val editText = inputs.getValue(field)
        editText.setText(text)
        editText.setSelection(editText.text.length)
    }

    // ------------------------------------------------- piezas adicionales

    /** Crea la tarjeta de una pieza adicional. [prefill] copia el precio del filamento y el material de la anterior. */
    private fun agregarPieza(slot: Int, prefill: Boolean): Card {
        val c = Card(slot, layoutInflater.inflate(R.layout.parte_extra, llPartesExtra, false))
        cards += c
        llPartesExtra.addView(c.root)

        val names = listOf(getString(R.string.material_placeholder)) + Materiales.todos.map { Materiales.etiqueta(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        c.material.adapter = adapter
        c.material.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyCardMaterial(c)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                applyCardMaterial(c)
            }
        }
        poblarPerfil(c.perfil, null)
        c.perfil.onItemSelectedListener = perfilListener()
        c.tiempoBox.visibility = if (swTiempoGlobal.isChecked) View.GONE else View.VISIBLE
        c.cajas.forEach { it.addTextChangedListener(recalcWatcher); marcarLleno(it) }
        c.masHoras.setOnClickListener {
            rapido { c.horas.setText(Rapidos.sumar(c.horas.text?.toString(), 1.0)); c.horas.setSelection(c.horas.text.length) }
        }
        c.masMinutos.setOnClickListener {
            rapido {
                val t = Rapidos.sumarMinutos(c.horas.text?.toString(), c.minutos.text?.toString())
                t.horas?.let { c.horas.setText(it) }
                c.minutos.setText(t.minutos)
                c.minutos.setSelection(c.minutos.text.length)
            }
        }
        c.quitar.setOnClickListener { quitarPieza(c) }

        if (prefill) {
            // Lo más común: mismo filamento y mismo material en todas las piezas; solo cambian gramos y tiempo.
            val previa = cards.getOrNull(cards.size - 2)
            c.precio.setText(previa?.precio?.text?.toString() ?: texto(Field.PRECIO_KG).orEmpty())
            c.material.setSelection(previa?.material?.selectedItemPosition ?: spMaterial.selectedItemPosition)
            // Y el mismo perfil de filamento.
            c.perfil.setSelection(previa?.perfil?.selectedItemPosition ?: spPerfil.selectedItemPosition)
        }
        renumerarPiezas()
        actualizarBotonAgregar()
        applyCardMaterial(c)
        return c
    }

    private fun quitarPieza(c: Card) {
        hideKeyboard()
        cards.remove(c)
        llPartesExtra.removeView(c.root)
        renumerarPiezas()
        actualizarBotonAgregar()
        recalculate(showErrors = false)
    }

    private fun renumerarPiezas() {
        cards.forEachIndexed { k, c ->
            c.titulo.text = getString(R.string.pieza_titulo, k + 2)
            c.quitar.contentDescription = getString(R.string.desc_quitar_pieza, k + 2)
        }
    }

    private fun actualizarBotonAgregar() {
        val lleno = cards.size + 1 >= MAX_PIEZAS
        btnAgregarPieza.isEnabled = !lleno
        tvAyudaPiezas.text = if (lleno) getString(R.string.max_piezas, MAX_PIEZAS) else getString(R.string.ayuda_piezas)
    }

    /** Aplica el material de la pieza: pone sus watts o muestra el campo propio con "Otro". */
    private fun applyCardMaterial(c: Card) {
        val m = Materiales.todos.getOrNull(c.material.selectedItemPosition - 1)
        val watts = m?.watts
        when {
            m == null -> {
                c.wattsBox.visibility = View.GONE
                if (c.wattsAuto || !c.watts.text.isNullOrEmpty()) c.watts.setText("")
                c.wattsAuto = false
            }
            watts != null -> {
                c.wattsBox.visibility = View.VISIBLE
                val cambioMaterial = c.materialAplicado != null && c.materialAplicado != m.nombre
                if (c.watts.text.isNullOrEmpty() || cambioMaterial) c.watts.setText(Suggested.plain(watts))
                c.wattsAuto = true
            }
            else -> {
                c.wattsBox.visibility = View.VISIBLE
                if (c.wattsAuto) c.watts.setText("")
                c.wattsAuto = false
            }
        }
        c.materialAplicado = m?.nombre
        showCardInfo(c, error = false)
    }

    private fun showCardInfo(c: Card, error: Boolean) {
        val m = Materiales.todos.getOrNull(c.material.selectedItemPosition - 1)
        val watts = m?.watts
        c.info.text = when {
            error -> getString(R.string.material_falta)
            m == null -> getString(R.string.material_info_ninguno)
            watts == null -> getString(R.string.material_info_otro)
            else -> getString(R.string.material_info, Suggested.plain(watts), m.fuente)
        }
        c.info.setTextColor(themeColor(if (error) R.attr.cError else R.attr.cTextoSuave))
    }

    // ------------------------------------------------- parámetros de la máquina (v2.5)

    /** Carga y guarda la tarifa, la depreciación y el mantenimiento: se recuerdan entre usos y al limpiar. */
    private fun setupParametros() {
        PARAMETROS.forEach { campo ->
            val caja = inputs.getValue(campo)
            val clave = "par_" + campo.name
            val guardado = prefs.getString(clave, null)
            if (!guardado.isNullOrEmpty() && caja.text.isNullOrEmpty()) {
                // Sin recalcular: aún no existen todas las vistas (recalcular aquí cerraba la app al abrir).
                suspendWatchers = true
                caja.setText(guardado)
                suspendWatchers = false
            }
            caja.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(clave, s?.toString().orEmpty()).apply()
                }
            })
        }
    }

    // ------------------------------------------------- tiempo global (v2.2)

    private fun setupTiempoGlobal() {
        swTiempoGlobal = findViewById(R.id.sw_tiempo_global)
        llTiempoPieza1 = findViewById(R.id.ll_tiempo_pieza1)
        llTiempoGlobal = findViewById(R.id.ll_tiempo_global)
        tvAyudaTiempo = findViewById(R.id.tv_ayuda_tiempo)
        swTiempoGlobal.isChecked = prefs.getBoolean(KEY_TIEMPO_GLOBAL, false)
        aplicarModoTiempo()
        swTiempoGlobal.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_TIEMPO_GLOBAL, checked).apply()
            aplicarModoTiempo()
            recalculate(showErrors = false)
        }
    }

    /** Con tiempo global se oculta el tiempo de cada pieza y se muestra el tiempo único de la impresión. */
    private fun aplicarModoTiempo() {
        val global = swTiempoGlobal.isChecked
        llTiempoPieza1.visibility = if (global) View.GONE else View.VISIBLE
        cards.forEach { it.tiempoBox.visibility = if (global) View.GONE else View.VISIBLE }
        llTiempoGlobal.visibility = if (global) View.VISIBLE else View.GONE
        tvAyudaTiempo.setText(if (global) R.string.ayuda_tiempo_global else R.string.ayuda_tiempo_pieza)
    }

    // ------------------------------------------------- perfiles de filamento (v2.2)

    private fun setupPerfiles() {
        spPerfil = findViewById(R.id.sp_perfil)
        perfiles = Perfiles.deserializar(prefs.getString(KEY_PERFILES, null))
        poblarPerfil(spPerfil, prefs.getString(KEY_PERFIL1, null))
        spPerfil.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val elegido = perfiles.getOrNull(position - 1)?.id
                prefs.edit().apply { if (elegido == null) remove(KEY_PERFIL1) else putString(KEY_PERFIL1, elegido) }.apply()
                if (!suspendWatchers) recalculate(showErrors = false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btn_perfiles).setOnClickListener { hideKeyboard(); mostrarListaPerfiles() }
        btnRegistrar.setOnClickListener { registrarConsumo() }
    }

    private fun perfilListener() = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (!suspendWatchers) recalculate(showErrors = false)
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun perfilDe(sp: Spinner): String? = perfiles.getOrNull(sp.selectedItemPosition - 1)?.id

    /** Llena el menú de perfiles y deja elegido [idElegido] (o "Sin perfil"). */
    private fun poblarPerfil(sp: Spinner, idElegido: String?) {
        val nombres = listOf(getString(R.string.perfil_ninguno)) + perfiles.map { it.nombre }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nombres)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = adapter
        sp.setSelection(perfiles.indexOfFirst { it.id == idElegido } + 1)
    }

    /** Vuelve a llenar todos los menús tras crear, editar o borrar perfiles, conservando lo elegido. */
    private fun refrescarMenusPerfil(anteriores: Map<Spinner, String?>) {
        suspendWatchers = true
        anteriores.forEach { (sp, id) -> poblarPerfil(sp, id) }
        suspendWatchers = false
        prefs.edit().apply {
            perfilDe(spPerfil)?.let { putString(KEY_PERFIL1, it) } ?: remove(KEY_PERFIL1)
        }.apply()
        recalculate(showErrors = false)
    }

    private fun guardarPerfiles(nuevos: List<Perfil>) {
        val antes = (listOf(spPerfil) + cards.map { it.perfil }).associateWith { perfilDe(it) }
        perfiles = nuevos
        prefs.edit().putString(KEY_PERFILES, Perfiles.serializar(nuevos)).commit()
        refrescarMenusPerfil(antes)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mostrarListaPerfiles() {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        lateinit var dialogo: AlertDialog
        if (perfiles.isEmpty()) {
            caja.addView(TextView(this).apply { setText(R.string.perfiles_vacio); setPadding(0, dp(8), 0, dp(8)) })
        }
        perfiles.forEach { p ->
            val pr = Perfiles.proyectar(p, 0.0)
            caja.addView(Button(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                text = p.nombre + "\n" + Suggested.plainRounded(p.usadoG) + " de " +
                    Suggested.plainRounded(p.capacidadG) + " g (" + Perfiles.textoPct(pr.pct) + ")" +
                    when (pr.estado) {
                        EstadoPerfil.OK -> ""
                        EstadoPerfil.CASI -> " · casi completo"
                        EstadoPerfil.COMPLETO -> " · completo"
                        EstadoPerfil.SOBREPASA -> " · sobrepasado"
                    }
                setOnClickListener { dialogo.dismiss(); editarPerfil(p) }
            })
        }
        caja.addView(Button(this).apply {
            setText(R.string.perfil_nuevo)
            setOnClickListener { dialogo.dismiss(); editarPerfil(null) }
        })
        dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.perfiles_titulo)
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton(R.string.perfil_cerrar, null)
            .create()
        dialogo.show()
    }

    /** Crea ([existente] null) o edita un perfil: nombre, peso del carrete y gramos ya consumidos. */
    private fun editarPerfil(existente: Perfil?) {
        val caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val etNom = EditText(this).apply {
            setHint(R.string.perfil_hint_nombre)
            setText(existente?.nombre.orEmpty())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val etCap = EditText(this).apply {
            setText(Suggested.plainRounded(existente?.capacidadG ?: Perfiles.KILO_G))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etUsado = EditText(this).apply {
            setText(Suggested.plainRounded(existente?.usadoG ?: 0.0))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        caja.addView(etNom)
        caja.addView(TextView(this).apply { setText(R.string.perfil_lbl_capacidad); setPadding(0, dp(10), 0, 0) })
        caja.addView(etCap)
        caja.addView(TextView(this).apply { setText(R.string.perfil_lbl_usado); setPadding(0, dp(10), 0, 0) })
        caja.addView(etUsado)
        val b = AlertDialog.Builder(this)
            .setTitle(if (existente == null) R.string.perfil_nuevo else R.string.perfiles_titulo)
            .setView(ScrollView(this).apply { addView(caja) })
            .setPositiveButton(R.string.perfil_guardar, null)
            .setNegativeButton(R.string.perfil_cancelar) { _, _ -> mostrarListaPerfiles() }
        if (existente != null) {
            b.setNeutralButton(R.string.perfil_eliminar) { _, _ ->
                guardarPerfiles(perfiles.filter { it.id != existente.id })
                mostrarListaPerfiles()
            }
        }
        val d = b.create()
        d.show()
        // GUARDAR valida sin cerrar el cuadro si hay un número inválido.
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cap = CostCalculator.parseNumber(etCap.text?.toString())
            val usado = CostCalculator.parseNumber(etUsado.text?.toString()) ?: 0.0
            if (cap == null || cap <= 0.0 || usado < 0.0) {
                Toast.makeText(this, R.string.perfil_dato_invalido, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val nombre = Perfiles.nombreOPorDefecto(etNom.text?.toString(), perfiles)
            val nuevo = Perfil(existente?.id ?: Perfiles.nuevoId(perfiles), nombre, cap, usado)
            guardarPerfiles(if (existente == null) perfiles + nuevo else perfiles.map { if (it.id == nuevo.id) nuevo else it })
            d.dismiss()
            mostrarListaPerfiles()
        }
        // Atajo del carrete nuevo: deja lo consumido en 0.
        if (existente != null) {
            caja.addView(Button(this).apply {
                setText(R.string.perfil_reiniciar)
                setOnClickListener { etUsado.setText("0") }
            })
        }
    }

    /** Firma de los consumos del cálculo actual: sirve para no registrar dos veces el mismo. */
    private fun firmaConsumos(c: Map<String, Double>): String =
        c.entries.joinToString(";") { it.key + "=" + Suggested.plainRounded(it.value) }

    private fun consumosActuales(i: CostInputs): Map<String, Double> =
        Perfiles.consumosPorPerfil(
            listOf(Parte(i.precioKg, i.gramos, i.horas, i.minutos, i.watts, i.perfil)) + i.partesExtra
        )

    /** Estado de cada perfil elegido: lo usado, más lo de este cálculo, con aviso al completar o pasar del carrete. */
    private fun mostrarPerfiles(i: CostInputs) {
        val consumos = consumosActuales(i)
        if (consumos.isEmpty()) {
            tvPerfilesEstado.visibility = View.GONE
            btnRegistrar.visibility = View.GONE
            return
        }
        val registrado = prefs.getString(KEY_REGISTRO, null) == firmaConsumos(consumos)
        var alerta = false
        val lineas = consumos.mapNotNull { (id, g) ->
            val p = perfiles.firstOrNull { it.id == id } ?: return@mapNotNull null
            if (registrado) {
                val e = Perfiles.proyectar(p, 0.0).estado
                alerta = alerta || e != EstadoPerfil.OK
                Perfiles.mensajeRegistrado(p)
            } else {
                val pr = Perfiles.proyectar(p, g)
                alerta = alerta || pr.estado != EstadoPerfil.OK
                Perfiles.mensaje(pr)
            }
        }
        tvPerfilesEstado.text = lineas.joinToString("\n")
        tvPerfilesEstado.setTextColor(themeColor(if (alerta) R.attr.cError else R.attr.cTexto))
        tvPerfilesEstado.visibility = View.VISIBLE
        btnRegistrar.visibility = View.VISIBLE
        btnRegistrar.isEnabled = !registrado
        btnRegistrar.setText(if (registrado) R.string.btn_consumo_registrado else R.string.btn_registrar_consumo)
    }

    /** Suma los gramos de este cálculo a los perfiles elegidos y avisa si alguno se completa o se sobrepasa. */
    private fun registrarConsumo() {
        val i = ultimoResultado?.second ?: return
        val consumos = consumosActuales(i)
        if (consumos.isEmpty()) return
        val firma = firmaConsumos(consumos)
        if (prefs.getString(KEY_REGISTRO, null) == firma) return
        val avisos = consumos.mapNotNull { (id, g) ->
            perfiles.firstOrNull { it.id == id }?.let { Perfiles.proyectar(it, g) }
        }.filter { it.estado != EstadoPerfil.OK }
        val nuevos = Perfiles.registrar(perfiles, consumos)
        prefs.edit().putString(KEY_REGISTRO, firma).commit()
        guardarPerfiles(nuevos)
        if (avisos.isEmpty()) {
            Toast.makeText(this, R.string.btn_consumo_registrado, Toast.LENGTH_SHORT).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.perfil_alerta_titulo)
                .setMessage(avisos.joinToString("\n\n") { Perfiles.mensaje(it) })
                .setPositiveButton(R.string.perfil_ok, null)
                .show()
        }
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
        // El último material usado se recuerda (como la región): la mayoría de las impresiones usan el mismo.
        prefs.getString(KEY_MATERIAL, null)?.let { guardado ->
            val idx = Materiales.todos.indexOfFirst { it.nombre == guardado && it.watts != null }
            if (idx >= 0) spMaterial.setSelection(idx + 1)
        }
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
                // Los watts del material son una propuesta: el campo queda visible para editarlo.
                llWattsOtro.visibility = View.VISIBLE
                val cambioMaterial = materialAplicado != null && materialAplicado != m.nombre
                if (field.text.isNullOrEmpty() || cambioMaterial) field.setText(Suggested.plain(watts))
                wattsAuto = true
            }
            else -> {
                llWattsOtro.visibility = View.VISIBLE
                if (wattsAuto) field.setText("")
                wattsAuto = false
            }
        }
        materialAplicado = m?.nombre
        showMaterialInfo(error = false)
        // Recordar la elección (solo materiales con watts propios; "Otro" y sin elegir no se guardan).
        prefs.edit().apply {
            if (m != null && watts != null) putString(KEY_MATERIAL, m.nombre) else if (m == null) remove(KEY_MATERIAL)
        }.commit()
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
                r.nombre + " \u2014 " + (r.referenciaKwh?.let { CostCalculator.formatClp(it) + (if (r.estimado) " (" + getString(R.string.est_corto) + ")" else "") } ?: sinDato)
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
            region.estimado -> getString(R.string.region_ref_estimado, CostCalculator.formatClp(ref), Regiones.REFERENCIA_FECHA)
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
                text = r.referenciaKwh?.let { CostCalculator.formatClp(it) + (if (r.estimado) " " + getString(R.string.est_corto) else "") }
                    ?: getString(R.string.tabla_sin_dato)
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
        if (requestCode == REQ_STORAGE) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                respaldo(accionPendiente)
            } else {
                Toast.makeText(this, R.string.imagen_sin_permiso, Toast.LENGTH_LONG).show()
            }
            return
        }
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
        const val REQ_STORAGE = 1002
        const val ACCION_GUARDAR = 1
        const val ACCION_COMPARTIR = 2
        const val RECENT_MS = 2L * 60L * 60L * 1000L   // 2 horas
        const val SEARCH_TIMEOUT_MS = 30_000L
        const val PREFS = "ajustes"
        const val KEY_DARK = "oscuro"
        const val KEY_SLOTS = "piezas_extra"
        /** Máximo de piezas por producto (la 1.ª más hasta 5 adicionales: hay ids fijos para cada una). */
        const val MAX_PIEZAS = 6
        const val KEY_REGION = "region"
        const val KEY_MATERIAL = "material"
        /** Parámetros de la máquina que se recuerdan (clave de preferencias = "par_" + nombre del campo). */
        val PARAMETROS = setOf(Field.TARIFA_HORA, Field.DEPRECIACION_HORA, Field.MANTENIMIENTO_HORA)
        const val KEY_TIEMPO_GLOBAL = "tiempo_global"
        const val KEY_PERFILES = "perfiles"
        const val KEY_PERFIL1 = "perfil_pieza1"
        const val KEY_REGISTRO = "consumo_registrado"
        const val KEY_MANUAL = "precio_manual"
        const val MANUAL_TOKEN = "__manual__"
        /** Última opción del menú de regiones: 0 = sin elegir, 1..16 = regiones, 17 = otra tarifa. */
        val MANUAL_POS = Regiones.todas.size + 1
    }
}
