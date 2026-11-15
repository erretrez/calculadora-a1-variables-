#!/usr/bin/env bash
# Prueba en emulador: instala el APK, lo abre y recorre las funciones principales
# (menú de materiales, tabla de precios por región, cálculo con valores fijos, extras,
# validación, indicadores de cálculo, modo oscuro persistente, LIMPIAR, boleta y
# detección de región con ubicación simulada).
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
pick() {  # <id del menú> <texto de la opción>: abre el menú y toca la opción
  tap "$1"
  $H taptext "$2" || fail "no se encontró la opción '$2' en el menú $1"
  sleep 1
}
has_text() { $H hastext "$1" || fail "no se encontró el texto '$1' en pantalla"; echo "OK: hay texto '$1'"; }
count_of() { printf '%s' "$1" | grep -oE '[0-9]+ actualizado' | grep -oE '[0-9]+' | head -1; }
expect_light() { local l; l=$($H luma); [ "$l" -gt 180 ] || fail "$1: se esperaba fondo claro y la luminosidad fue $l"; echo "OK: $1 (fondo claro, luminosidad $l)"; }
expect_dark()  { local l; l=$($H luma); [ "$l" -lt 80 ] || fail "$1: se esperaba fondo oscuro y la luminosidad fue $l"; echo "OK: $1 (fondo oscuro, luminosidad $l)"; }
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

echo "== Estado inicial: campos vacíos y sin campos redundantes"
adb shell uiautomator dump /sdcard/ui.xml > /dev/null
adb pull /sdcard/ui.xml "$OUT/ui_inicial.xml" > /dev/null
shot pantalla_inicial
python3 - "$OUT/ui_inicial.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
edits = [n for n in ET.parse(sys.argv[1]).iter("node") if n.get("class") == "android.widget.EditText"]
print("EditText visibles:", len(edits))
assert len(edits) >= 4, "se esperaban al menos 4 campos visibles en la primera pantalla"
for n in edits:
    assert not re.search(r"\d", n.get("text", "")), f"campo con valor precargado: {n.get('text')!r}"
print("OK: campos vacíos al iniciar")
PY
expect_light "tema inicial (el emulador parte en modo claro)"

ALL_IDS=$($H ids)
echo "$ALL_IDS" | grep -qx "et_precio_kg" || fail "recorrido incompleto: no vio et_precio_kg"
echo "$ALL_IDS" | grep -qx "tv_ganancia" || fail "recorrido incompleto: no llegó a tv_ganancia"
for id in sp_material sp_region btn_tabla sw_oscuro tv_calculado_con tv_barra_total btn_compartir; do
  echo "$ALL_IDS" | grep -qx "$id" || fail "debería existir $id"; echo "OK: existe $id"
done
for id in et_precio_impresora et_vida_util et_mantenimiento et_watts et_precio_kwh \
          btn_usar_watts_estandar btn_usar_watts_alto btn_usar_region btn_guardar_tarifa btn_usar_tarifa \
          et_boleta_monto; do
  if echo "$ALL_IDS" | grep -qx "$id"; then fail "no debería verse $id al inicio (sin región ni material)"; fi
  echo "OK: no se ve $id al inicio"
done

echo "== Menú de materiales y tabla por región (Metropolitana = \$160/kWh)"
pick sp_material "PLA"
expect_contains "info del material PLA" "$(get tv_material_info)" "95 W"
pick sp_region "Metropolitana de Santiago"
expect_contains "precio de la región usado" "$(get tv_region_ref)" '$160'
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 2
fill et_horas 5
fill et_minutos 0
expect_digits "material" "$(get tv_material)" 2000
expect_digits "electricidad (95 W x 5 h x 160)" "$(get tv_electricidad)" 76
expect_digits "depreciación (450000/5000 x 5 h)" "$(get tv_depreciacion)" 450
expect_digits "mantenimiento (100 x 5 h)" "$(get tv_mantenimiento)" 500
expect_digits "mano de obra sin extras" "$(get tv_mano_obra)" 0
expect_digits "merma sin extras" "$(get tv_merma)" 0
expect_digits "total" "$(get tv_total)" 3026
expect_digits "unitario" "$(get tv_unitario)" 1513
expect_empty "precio de venta sin margen" "$(get tv_precio_venta)"
expect_digits "costo por gramo (3026 / 100 g)" "$(get tv_por_gramo)" 30
expect_digits "costo por hora (3026 / 5 h)" "$(get tv_por_hora)" 605
BARRA=$(get tv_barra_total)
expect_contains "barra fija: total" "$BARRA" '3.026'
expect_contains "barra fija: unitario" "$BARRA" '1.513'
FIJOS=$(get tv_fijos)
expect_contains "resumen de valores fijos (impresora)" "$FIJOS" '$450.000'
expect_contains "resumen de valores fijos (vida útil)" "$FIJOS" '5.000'
expect_contains "resumen de valores fijos (mantenimiento)" "$FIJOS" '$100'
shot pantalla_resultados_base

echo "== Indicadores: hora con minutos, contador y resumen de datos usados"
ST1=$(get tv_status)
expect_contains "estado tras calcular" "$ST1" "actualizado a las"
printf '%s' "$ST1" | grep -qE '[0-9]{2}:[0-9]{2}:[0-9]{2}' || fail "el estado no lleva hora HH:mm:ss: '$ST1'"
echo "OK: el estado lleva hora con minutos y segundos"
N1=$(count_of "$ST1"); [ -n "$N1" ] || fail "no se encontró el número de cálculo en '$ST1'"
CON=$(get tv_calculado_con)
expect_contains "calculado con: material" "$CON" "100 g de PLA"
expect_contains "calculado con: piezas" "$CON" "2 piezas"
expect_contains "calculado con: tiempo" "$CON" "5 h 0 min"
expect_contains "calculado con: watts" "$CON" "95 W"
expect_contains "calculado con: precio y región" "$CON" 'Metropolitana de Santiago'
expect_contains "verificación de suma" "$(get tv_cuadra)" "Verificado"
fill et_gramos 200
expect_digits "material con 200 g" "$(get tv_material)" 4000
ST2=$(get tv_status); N2=$(count_of "$ST2")
[ "$N2" -gt "$N1" ] || fail "el contador de cálculos no avanzó al cambiar un valor ($N1 -> $N2)"
echo "OK: al cambiar un valor se recalculó (cálculo n.º $N1 -> n.º $N2, '$ST2')"
expect_contains "calculado con refleja el cambio" "$(get tv_calculado_con)" "200 g de PLA"
fill et_gramos 100
expect_digits "total al volver a 100 g" "$(get tv_total)" 3026

echo "== Otros costos por pieza (embalaje, envío)"
fill et_otros 500
expect_digits "otros costos (500 x 2 piezas)" "$(get tv_otros)" 1000
expect_digits "total con otros costos" "$(get tv_total)" 4026
expect_digits "unitario con otros costos" "$(get tv_unitario)" 2013
expect_contains "barra fija sigue al total" "$(get tv_barra_total)" '4.026'
expect_contains "calculado con incluye otros costos" "$(get tv_calculado_con)" "otros"
fill et_otros ""
expect_digits "total sin otros costos" "$(get tv_total)" 3026

echo "== Colores de los campos (modo claro): vacío blanco, con valor verde"
read -r R G B <<< "$($H bgrgb et_otros)"; echo "campo vacío: $R $G $B"
[ $((R+G+B)) -gt 700 ] || fail "el campo vacío debía verse claro ($R $G $B)"
read -r R G B <<< "$($H bgrgb et_precio_kg)"; echo "campo con valor: $R $G $B"
[ $((G-R)) -gt 10 ] || fail "el campo con valor debía verse verdoso ($R $G $B)"
echo "OK: campo vacío claro y campo con valor verdoso en modo claro"

echo "== Otros materiales del menú"
pick sp_material "ABS"
expect_digits "electricidad con ABS (200 W)" "$(get tv_electricidad)" 160
expect_digits "total con ABS" "$(get tv_total)" 3110
pick sp_material "PETG"
expect_digits "electricidad con PETG (120 W)" "$(get tv_electricidad)" 96
expect_contains "PETG indicado como estimado" "$(get tv_material_info)" "estimado"
pick sp_material "Otro"
expect_empty "campo watts propio vacío con Otro" "$(get et_watts)"
fill et_watts 150
expect_digits "electricidad con watts propios (150 W)" "$(get tv_electricidad)" 120
expect_contains "calculado con muestra los watts propios" "$(get tv_calculado_con)" "150 W"
shot pantalla_material_otro
pick sp_material "PLA"
expect_digits "total con PLA otra vez" "$(get tv_total)" 3026

echo "== Extras: mano de obra, merma, margen e IVA"
fill et_horas_trabajo 2
fill et_costo_hora_trabajo 3000
fill et_merma 20
fill et_margen 30
expect_digits "mano de obra" "$(get tv_mano_obra)" 6000
expect_digits "merma (20% de 3026)" "$(get tv_merma)" 605
expect_digits "total con extras" "$(get tv_total)" 9631
expect_digits "unitario con extras" "$(get tv_unitario)" 4816
expect_digits "precio de venta" "$(get tv_precio_venta)" 6260
expect_empty "precio con IVA (IVA desactivado)" "$(get tv_precio_iva)"
expect_digits "ganancia del lote" "$(get tv_ganancia)" 2889
tap cb_iva
expect_digits "precio con IVA (IVA activado)" "$(get tv_precio_iva)" 7450
expect_contains "calculado con incluye los extras" "$(get tv_calculado_con)" "merma 20%"
expect_contains "verificación de suma con extras" "$(get tv_cuadra)" "Verificado"
shot pantalla_extras

echo "== Compartir resumen (solo se comprueba que abre el selector y la app sigue viva)"
tap btn_compartir
sleep 3
adb shell input keyevent 4
sleep 2
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró al compartir"
expect_digits "el total sigue visible tras compartir" "$(get tv_total)" 9631

echo "== Validación: piezas = 0 no debe dar resultado ni dividir por cero"
fill et_piezas 0
tap btn_calcular
expect_contains "estado con piezas 0" "$(get tv_status)" "Faltan datos"
expect_empty "total con piezas 0" "$(get tv_total)"
expect_empty "unitario con piezas 0" "$(get tv_unitario)"
fill et_piezas 2
expect_digits "total tras corregir piezas" "$(get tv_total)" 9631

echo "== Modo oscuro: se aplica, conserva los datos y se recuerda al reiniciar"
tap sw_oscuro
sleep 3
expect_dark "tras activar el modo oscuro"
shot pantalla_oscuro
read -r R G B <<< "$($H bgrgb et_otros)"; echo "campo vacío (oscuro): $R $G $B"
[ $((R+G+B)) -lt 150 ] || fail "el campo vacío debía verse oscuro en modo oscuro ($R $G $B)"
read -r R G B <<< "$($H bgrgb et_precio_kg)"; echo "campo con valor (oscuro): $R $G $B"
[ $((R+G+B)) -lt 250 ] || fail "el campo con valor debía seguir oscuro ($R $G $B)"
[ $((G-R)) -gt 20 ] || fail "el campo con valor debía verse verdoso ($R $G $B)"
ML=$($H maxluma et_precio_kg); echo "luminosidad máxima del texto ingresado: $ML"
[ "$ML" -gt 200 ] || fail "el texto ingresado debía verse blanco en modo oscuro (luminosidad $ML)"
echo "OK: en modo oscuro los campos son oscuros, el texto ingresado es blanco y el campo con valor se ve verdoso"
expect_digits "el total sobrevive al cambio de tema" "$(get tv_total)" 9631
expect_contains "el material sobrevive al cambio de tema" "$(get tv_calculado_con)" "PLA"
adb shell am force-stop "$PKG"; sleep 2
start_app
expect_dark "tras reiniciar la app (el modo oscuro se mantiene)"
[ "$(get sw_oscuro checked)" = "true" ] || fail "el interruptor debía quedar activado tras reiniciar"
echo "OK: interruptor activado tras reiniciar"
expect_empty "campo kg vacío al reiniciar" "$(get et_precio_kg)"
expect_contains "la región se recuerda tras reiniciar" "$(get tv_region_ref)" '$160'
tap sw_oscuro
sleep 3
expect_light "tras volver al modo claro"
adb shell am force-stop "$PKG"; sleep 2
start_app
expect_light "tras reiniciar en modo claro"

echo "== Tabla de precios por región"
tap btn_tabla
has_text "Antofagasta"
has_text "Los Ríos"
has_text '$170'
has_text "sin dato: usa tu boleta"
shot pantalla_tabla
$H taptext "Antofagasta" || fail "no se pudo tocar la fila de Antofagasta"
expect_contains "tocar una fila elige la región" "$(get tv_region_ref)" '$170'
tap btn_tabla

echo "== Región sin precio en la tabla: aparece el precio manual y la boleta"
pick sp_region "Los Ríos"
expect_contains "aviso de región sin dato" "$(get tv_region_ref)" "no tiene precio"
expect_empty "precio manual vacío" "$(get et_precio_kwh)"
pick sp_material "PLA"
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 2
fill et_horas 5
fill et_minutos 0
tap btn_calcular
expect_contains "falta el precio del kWh" "$(get tv_status)" "Faltan datos"
fill et_boleta_monto 30000
fill et_boleta_kwh 200
expect_digits "precio efectivo de la boleta" "$(get tv_boleta_resultado)" 150
tap btn_usar_boleta
expect_digits "precio manual desde la boleta" "$(get et_precio_kwh)" 150
expect_digits "electricidad con 150/kWh (95 W x 5 h)" "$(get tv_electricidad)" 71
fill et_boleta_kwh 0
expect_contains "kWh 0 en boleta" "$(get tv_boleta_resultado)" "mayores que 0"
shot pantalla_manual
adb shell am force-stop "$PKG"; sleep 2
start_app
expect_digits "el precio manual se recuerda al reiniciar" "$(get et_precio_kwh)" 150

echo "== LIMPIAR: vacía los datos de la impresión y conserva la región"
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 2
fill et_horas 5
tap btn_calcular
expect_contains "falta elegir material" "$(get tv_material_info)" "Elige un material"
tap btn_limpiar
for id in et_precio_kg et_gramos et_piezas et_horas et_minutos et_horas_trabajo et_otros \
          et_costo_hora_trabajo et_merma et_margen et_boleta_monto et_boleta_kwh; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
for id in tv_material tv_electricidad tv_depreciacion tv_mantenimiento tv_mano_obra tv_merma \
          tv_total tv_unitario tv_precio_venta tv_precio_iva tv_ganancia; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
[ "$(get cb_iva checked)" = "false" ] || fail "el IVA debía quedar desmarcado tras LIMPIAR"
echo "OK: IVA desmarcado"
[ "$(get btn_compartir enabled)" = "false" ] || fail "COMPARTIR debía quedar desactivado sin resultado"
echo "OK: COMPARTIR desactivado sin resultado"
expect_contains "barra fija vacía tras LIMPIAR" "$(get tv_barra_total)" "—"
expect_empty "otros costos tras LIMPIAR" "$(get tv_otros)"
expect_empty "costo por gramo tras LIMPIAR" "$(get tv_por_gramo)"
expect_contains "el material vuelve a vacío" "$(get tv_material_info)" "Elige un material"
expect_contains "la región se conserva tras LIMPIAR" "$(get tv_region_ref)" "no tiene precio"

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
expect_contains "precio de la región detectada" "$(get tv_region_ref)" '$160'
ALL_IDS=$($H ids)
if echo "$ALL_IDS" | grep -qx "et_precio_kwh"; then fail "con precio en la tabla no debe verse la casilla del kWh"; fi
echo "OK: con precio en la tabla la casilla del kWh no aparece"

echo "== Verificando que sigue viva y sin fallos"
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró durante la prueba"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "excepción fatal durante la prueba"; fi

adb logcat -d -t 400 > "$OUT/logcat.txt" || true
echo "PRUEBA DE EMULADOR: OK"
