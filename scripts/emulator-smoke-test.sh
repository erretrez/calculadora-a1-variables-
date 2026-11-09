#!/usr/bin/env bash
# Prueba en emulador: instala el APK, lo abre y recorre las funciones principales
# (cálculo, extras, validación, LIMPIAR, sugeridos, boleta y detección de región).
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

echo "== Instalando $APK"
adb install -r "$APK" | tee "$OUT/install.txt"
grep -q "Success" "$OUT/install.txt" || fail "la instalación no fue exitosa"

echo "== Abriendo MainActivity"
adb logcat -c
adb shell am start -W -n "$ACT" | tee "$OUT/start.txt"
grep -q "Status: ok" "$OUT/start.txt" || fail "am start no devolvió Status: ok"
sleep 4
adb shell pidof "$PKG" > /dev/null || fail "el proceso de la app no está en ejecución"
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime: .*$PKG" > "$OUT/crash.txt"; then
  cat "$OUT/crash.txt"; fail "la app produjo una excepción fatal al abrir"
fi

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

echo "== Cálculo base con los 10 datos obligatorios"
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 2
fill et_horas 5
fill et_minutos 0
fill et_watts 200
fill et_precio_kwh 150
fill et_precio_impresora 400000
fill et_vida_util 5000
fill et_mantenimiento 100
expect_digits "material" "$(get tv_material)" 2000
expect_digits "electricidad" "$(get tv_electricidad)" 150
expect_digits "depreciación" "$(get tv_depreciacion)" 400
expect_digits "mantenimiento" "$(get tv_mantenimiento)" 500
expect_digits "mano de obra sin extras" "$(get tv_mano_obra)" 0
expect_digits "merma sin extras" "$(get tv_merma)" 0
expect_digits "total" "$(get tv_total)" 3050
expect_digits "unitario" "$(get tv_unitario)" 1525
expect_empty "precio de venta sin margen" "$(get tv_precio_venta)"
expect_empty "ganancia sin margen" "$(get tv_ganancia)"
shot pantalla_resultados_base

echo "== Extras: mano de obra, merma, margen e IVA"
fill et_horas_trabajo 2
fill et_costo_hora_trabajo 3000
fill et_merma 20
fill et_margen 30
expect_digits "mano de obra" "$(get tv_mano_obra)" 6000
expect_digits "merma" "$(get tv_merma)" 610
expect_digits "total con extras" "$(get tv_total)" 9660
expect_digits "unitario con extras" "$(get tv_unitario)" 4830
expect_digits "precio de venta" "$(get tv_precio_venta)" 6279
expect_empty "precio con IVA (IVA desactivado)" "$(get tv_precio_iva)"
expect_digits "ganancia del lote" "$(get tv_ganancia)" 2898
tap cb_iva
expect_digits "precio con IVA (IVA activado)" "$(get tv_precio_iva)" 7472
shot pantalla_extras

echo "== Validación: piezas = 0 no debe dar resultado ni dividir por cero"
fill et_piezas 0
tap btn_calcular
expect_contains "estado con piezas 0" "$(get tv_status)" "Faltan datos"
expect_empty "total con piezas 0" "$(get tv_total)"
expect_empty "unitario con piezas 0" "$(get tv_unitario)"
fill et_piezas 2
expect_digits "total tras corregir piezas" "$(get tv_total)" 9660

echo "== LIMPIAR vacía todo (incluye extras, boleta e IVA)"
tap btn_limpiar
for id in et_precio_kg et_gramos et_piezas et_horas et_minutos et_watts et_precio_kwh \
          et_precio_impresora et_vida_util et_mantenimiento et_horas_trabajo \
          et_costo_hora_trabajo et_merma et_margen et_boleta_monto et_boleta_kwh; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
for id in tv_material tv_electricidad tv_depreciacion tv_mantenimiento tv_mano_obra tv_merma \
          tv_total tv_unitario tv_precio_venta tv_precio_iva tv_ganancia; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
[ "$(get cb_iva checked)" = "false" ] || fail "el IVA debía quedar desmarcado tras LIMPIAR"
echo "OK: IVA desmarcado"

echo "== Valores sugeridos visibles y fijos"
expect_digits "sugerido precio impresora" "$(get tv_sug_precio_impresora)" 450000
expect_digits "sugerido vida útil" "$(get tv_sug_vida_util)" 5000
expect_digits "sugerido mantenimiento" "$(get tv_sug_mantenimiento)" 100

echo "== Botones USAR copian el sugerido al campo editable"
tap btn_usar_precio_impresora
tap btn_usar_vida_util
tap btn_usar_mantenimiento
expect_digits "campo precio impresora" "$(get et_precio_impresora)" 450000
expect_digits "campo vida útil" "$(get et_vida_util)" 5000
expect_digits "campo mantenimiento" "$(get et_mantenimiento)" 100
expect_digits "sugerido sigue visible" "$(get tv_sug_precio_impresora)" 450000
shot pantalla_sugeridos

echo "== Precio del kWh desde la boleta"
fill et_boleta_monto 30000
fill et_boleta_kwh 200
expect_digits "precio efectivo de la boleta" "$(get tv_boleta_resultado)" 150
tap btn_usar_boleta
expect_digits "campo precio kWh desde boleta" "$(get et_precio_kwh)" 150
fill et_boleta_kwh 0
expect_contains "kWh 0 en boleta" "$(get tv_boleta_resultado)" "mayores que 0"
shot pantalla_boleta

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

echo "== Verificando que sigue viva y sin fallos"
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró durante la prueba"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "excepción fatal durante la prueba"; fi

adb logcat -d -t 400 > "$OUT/logcat.txt" || true
echo "PRUEBA DE EMULADOR: OK"
