#!/usr/bin/env bash
# Prueba en emulador: instala el APK, lo abre y recorre las funciones principales
# (cálculo con valores fijos, extras, validación, LIMPIAR, watts de la A1, boleta,
# tarifa guardada y detección de región con ubicación simulada).
set -euo pipefail

APK="${1:?uso: emulator-smoke-test.sh <ruta-al-apk>}"
PKG="cl.erretrez.calculadora"
ACT="$PKG/.MainActivity"
OUT="evidence"
mkdir -p "$OUT"
H="python3 scripts/ui_helper.py"

fail() { echo "FALLO: $*"; adb logcat -d -t 400 > "$OUT/logcat.txt" || true; exit 1; }
digits() { tr -cd '0-9'; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

expect_digits() {  # <descripción> <obtenido> <esperado>
  local got; got=$(printf '%s' "$2" | digits)
  [ "$got" = "$3" ] || fail "$1: esperado '$3', obtenido '$2'"
  echo "OK: $1 -> '$2'"
}
expect_empty() {   # <descripción> <obtenido>
  [ -z "$(printf '%s' "$2" | digits)" ] || fail "$1: debía estar vacío y fue '$2'"
  echo "OK: $1 vacío"
}
expect_contains() {  # <descripción> <obtenido> <fragmento>
  case "$2" in *"$3"*) echo "OK: $1 -> '$2'";; *) fail "$1: no contiene '$3' (fue '$2')";; esac
}
fill() { $H fill "$1" "$2" || fail "no se pudo llenar $1"; }
tap()  { $H tap "$1" || fail "no se pudo tocar $1"; }
get()  { $H get "$1" "${2:-text}" || fail "no se pudo leer $1"; }

start_app() {
  adb logcat -c
  adb shell am start -W -n "$ACT" | tee "$OUT/start.txt"
  grep -q "Status: ok" "$OUT/start.txt" || fail "am start no devolvió Status: ok"
  sleep 4
  adb shell pidof "$PKG" > /dev/null || fail "el proceso de la app no está en ejecución"
  if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: .*$PKG" > "$OUT/crash.txt"; then
    cat "$OUT/crash.txt"; fail "la app produjo una excepción fatal al abrir"
  fi
}

echo "== Instalando $APK"
adb install -r "$APK" | tee "$OUT/install.txt"
grep -q "Success" "$OUT/install.txt" || fail "la instalación no fue exitosa"

echo "== Abriendo MainActivity"
start_app

echo "== Estado inicial: campos vacíos"
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml "$OUT/ui_inicial.xml" > /dev/null
shot pantalla_inicial
python3 - "$OUT/ui_inicial.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
edits = [n for n in ET.parse(sys.argv[1]).iter("node") if n.get("class") == "android.widget.EditText"]
print("EditText visibles:", len(edits))
assert len(edits) >= 5, "se esperaban al menos 5 campos visibles en la primera pantalla"
for n in edits:
    assert not re.search(r"\d", n.get("text", "")), f"campo con valor precargado: {n.get('text')!r}"
print("OK: campos vacíos al iniciar")
PY

echo "== No existen campos para los datos fijos de la impresora"
ALL_IDS=$($H ids)
# Sanidad: el recorrido debe haber visto desde el primer campo hasta el último resultado.
echo "$ALL_IDS" | grep -qx "et_precio_kg" || fail "recorrido incompleto: no vio et_precio_kg"
echo "$ALL_IDS" | grep -qx "tv_ganancia" || fail "recorrido incompleto: no llegó a tv_ganancia"
for id in et_precio_impresora et_vida_util et_mantenimiento; do
  if echo "$ALL_IDS" | grep -qx "$id"; then fail "no debería existir el campo $id"; fi
  echo "OK: no existe $id"
done

echo "== Cálculo base con los 7 datos obligatorios (impresora con valores fijos)"
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 2
fill et_horas 5
fill et_minutos 0
fill et_watts 200
fill et_precio_kwh 150
expect_digits "material" "$(get tv_material)" 2000
expect_digits "electricidad" "$(get tv_electricidad)" 150
expect_digits "depreciación (450000/5000 x 5 h)" "$(get tv_depreciacion)" 450
expect_digits "mantenimiento (100 x 5 h)" "$(get tv_mantenimiento)" 500
expect_digits "mano de obra sin extras" "$(get tv_mano_obra)" 0
expect_digits "merma sin extras" "$(get tv_merma)" 0
expect_digits "total" "$(get tv_total)" 3100
expect_digits "unitario" "$(get tv_unitario)" 1550
expect_empty "precio de venta sin margen" "$(get tv_precio_venta)"
expect_empty "ganancia sin margen" "$(get tv_ganancia)"
FIJOS=$(get tv_fijos)
expect_contains "resumen de valores fijos (impresora)" "$FIJOS" '$450.000'
expect_contains "resumen de valores fijos (vida útil)" "$FIJOS" '5.000'
expect_contains "resumen de valores fijos (mantenimiento)" "$FIJOS" '$100'
shot pantalla_resultados_base

echo "== Extras: mano de obra, merma, margen e IVA"
fill et_horas_trabajo 2
fill et_costo_hora_trabajo 3000
fill et_merma 20
fill et_margen 30
expect_digits "mano de obra" "$(get tv_mano_obra)" 6000
expect_digits "merma" "$(get tv_merma)" 620
expect_digits "total con extras" "$(get tv_total)" 9720
expect_digits "unitario con extras" "$(get tv_unitario)" 4860
expect_digits "precio de venta" "$(get tv_precio_venta)" 6318
expect_empty "precio con IVA (IVA desactivado)" "$(get tv_precio_iva)"
expect_digits "ganancia del lote" "$(get tv_ganancia)" 2916
tap cb_iva
expect_digits "precio con IVA (IVA activado)" "$(get tv_precio_iva)" 7518
shot pantalla_extras

echo "== Validación: piezas = 0 no debe dar resultado ni dividir por cero"
fill et_piezas 0
tap btn_calcular
expect_contains "estado con piezas 0" "$(get tv_status)" "Faltan datos"
expect_empty "total con piezas 0" "$(get tv_total)"
expect_empty "unitario con piezas 0" "$(get tv_unitario)"
fill et_piezas 2
expect_digits "total tras corregir piezas" "$(get tv_total)" 9720

echo "== LIMPIAR vacía todo (incluye extras, boleta e IVA)"
tap btn_limpiar
for id in et_precio_kg et_gramos et_piezas et_horas et_minutos et_watts et_precio_kwh \
          et_horas_trabajo et_costo_hora_trabajo et_merma et_margen et_boleta_monto et_boleta_kwh; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
for id in tv_material tv_electricidad tv_depreciacion tv_mantenimiento tv_mano_obra tv_merma \
          tv_total tv_unitario tv_precio_venta tv_precio_iva tv_ganancia; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
[ "$(get cb_iva checked)" = "false" ] || fail "el IVA debía quedar desmarcado tras LIMPIAR"
echo "OK: IVA desmarcado"

echo "== Consumo de la Bambu Lab A1 Combo: rango estándar y alto"
expect_digits "rango estándar visible (70 a 95 W)" "$(get tv_watts_estandar)" 7095
expect_digits "consumo alto visible (200 W)" "$(get tv_watts_alto)" 200
tap btn_usar_watts_estandar
expect_digits "campo watts con USAR estándar" "$(get et_watts)" 95
tap btn_usar_watts_alto
expect_digits "campo watts con USAR alto (editable, reemplaza)" "$(get et_watts)" 200
fill et_watts 120
expect_digits "campo watts sigue editable" "$(get et_watts)" 120
shot pantalla_watts

echo "== Precio del kWh desde la boleta"
fill et_boleta_monto 30000
fill et_boleta_kwh 200
expect_digits "precio efectivo de la boleta" "$(get tv_boleta_resultado)" 150
tap btn_usar_boleta
expect_digits "campo precio kWh desde boleta" "$(get et_precio_kwh)" 150
fill et_boleta_kwh 0
expect_contains "kWh 0 en boleta" "$(get tv_boleta_resultado)" "mayores que 0"
shot pantalla_boleta

echo "== Tarifa guardada"
expect_contains "sin tarifa guardada al inicio" "$(get tv_tarifa_guardada)" "aún no guardas"
tap btn_guardar_tarifa
expect_digits "tarifa guardada (150)" "$(get tv_tarifa_guardada)" 150
tap btn_limpiar
expect_empty "campo kWh vacío tras LIMPIAR" "$(get et_precio_kwh)"
expect_digits "la tarifa guardada sobrevive a LIMPIAR" "$(get tv_tarifa_guardada)" 150
tap btn_usar_tarifa
expect_digits "campo kWh con la tarifa guardada" "$(get et_precio_kwh)" 150

echo "== Detección de región con ubicación simulada (Santiago)"
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION || true
adb shell settings put secure location_mode 3 || true
adb shell cmd location set-location-enabled true || true
FOUND=""
for i in $(seq 1 8); do
  # El emulador parte con una ubicación por defecto fuera de Chile: se fija Santiago
  # (longitud primero) y se vuelve a tocar DETECTAR en cada intento.
  adb emu geo fix -70.6693 -33.4489 || true
  sleep 3
  tap btn_detectar
  sleep 4
  INFO=$(get tv_region_info)
  echo "intento $i: $INFO"
  case "$INFO" in *"Metropolitana"*) FOUND=1; break;; esac
done
shot pantalla_region
[ -n "$FOUND" ] || fail "no se detectó la región Metropolitana con la ubicación simulada"
expect_contains "referencia de la región" "$(get tv_region_ref)" '$160'
tap btn_usar_region
expect_digits "campo precio kWh desde la región" "$(get et_precio_kwh)" 160
tap btn_guardar_tarifa
expect_digits "tarifa guardada con región (160)" "$(get tv_tarifa_guardada)" 160
expect_contains "tarifa guardada con su región" "$(get tv_tarifa_guardada)" "Metropolitana"

echo "== Reinicio completo de la app: la tarifa guardada se conserva, los campos parten vacíos"
adb shell am force-stop "$PKG"
sleep 2
start_app
expect_digits "tarifa guardada tras reiniciar" "$(get tv_tarifa_guardada)" 160
expect_contains "región guardada tras reiniciar" "$(get tv_tarifa_guardada)" "Metropolitana"
expect_contains "región restaurada en el selector" "$(get tv_region_ref)" '$160'
expect_empty "campo kWh vacío al reiniciar" "$(get et_precio_kwh)"
expect_empty "campo kg vacío al reiniciar" "$(get et_precio_kg)"
shot pantalla_reinicio

echo "== Verificando que sigue viva y sin fallos"
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró durante la prueba"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "excepción fatal durante la prueba"; fi

adb logcat -d -t 400 > "$OUT/logcat.txt" || true
echo "PRUEBA DE EMULADOR: OK"
