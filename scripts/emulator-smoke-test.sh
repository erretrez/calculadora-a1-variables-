#!/usr/bin/env bash
# Prueba en emulador: instala el APK, lo abre y recorre las funciones principales
# (menú de materiales, tabla de precios por región, cálculo con valores fijos, extras,
# varias piezas (+ AGREGAR PIEZA), validación, indicadores de cálculo, modo oscuro persistente, LIMPIAR, boleta y
# detección de región con ubicación simulada).
set -euo pipefail

APK="${1:?uso: emulator-smoke-test.sh <ruta-al-apk>}"
PKG="cl.erretrez.calculadora"
ACT="$PKG/.MainActivity"
OUT="evidence"
mkdir -p "$OUT"
H="python3 scripts/ui_helper.py"

# Color de fondo de un campo, repitiendo la lectura hasta que dos capturas seguidas coincidan
# (así no se mide mientras la pantalla aún se desplaza o cambia de tema).
rgb_estable() {
  local id=$1 prev="" cur t
  for t in 1 2 3 4 5; do
    sleep 1.5
    cur=$($H bgrgb "$id")
    echo "  lectura $t de $id: $cur" >&2
    if [ "$cur" = "$prev" ]; then break; fi
    prev=$cur
  done
  echo "$cur"
}

fail() {
  echo "FALLO: $*"
  # Diagnóstico: qué hay en pantalla en este momento.
  adb shell uiautomator dump /sdcard/ui_fallo.xml > /dev/null 2>&1 || true
  adb pull /sdcard/ui_fallo.xml "$OUT/ui_fallo.xml" > /dev/null 2>&1 || true
  echo "ids en pantalla: $(grep -oE 'resource-id="[^"]+"' "$OUT/ui_fallo.xml" 2>/dev/null | grep -oE 'id/[a-z0-9_]+' | tr '\n' ' ')"
  echo "ventana: $(adb shell dumpsys window | grep mCurrentFocus | head -1)"
  adb logcat -d -t 400 > "$OUT/logcat.txt" || true
  exit 1
}
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

echo "== Menú de materiales y tabla por región (Metropolitana = \$247/kWh)"
pick sp_material "PLA"
expect_contains "info del material PLA" "$(get tv_material_info)" "95 W"
pick sp_region "Metropolitana de Santiago"
expect_contains "precio de la región usado" "$(get tv_region_ref)" '$247'
fill et_nombre Llavero_Erretrez
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 2
fill et_horas 5
fill et_minutos 0
expect_digits "material (2 unidades x 100 g)" "$(get tv_material)" 4000
expect_digits "electricidad (95 W x 5 h x 247 x 2 unidades)" "$(get tv_electricidad)" 235
expect_digits "depreciación (489990/5000 x 10 h)" "$(get tv_depreciacion)" 980
expect_digits "mantenimiento (50 x 10 h)" "$(get tv_mantenimiento)" 500
expect_digits "mano de obra sin extras" "$(get tv_mano_obra)" 0
expect_digits "merma sin extras" "$(get tv_merma)" 0
expect_digits "costo del lote" "$(get tv_total)" 5715
expect_digits "costo unitario (lote / 2 unidades)" "$(get tv_unitario)" 2857
expect_empty "precio de venta sin margen" "$(get tv_precio_venta)"
expect_digits "costo por gramo (5715 / 200 g)" "$(get tv_por_gramo)" 29
expect_contains "filamento total del lote" "$(get tv_gramos_total)" "200 g"
expect_contains "tiempo total del lote" "$(get tv_horas_total)" "10 h"
expect_digits "costo por hora (5715 / 10 h)" "$(get tv_por_hora)" 571
BARRA=$(get tv_barra_total)
expect_contains "barra fija: total" "$BARRA" '5.715'
expect_contains "barra fija: unitario" "$BARRA" '2.857'
FIJOS=$(get tv_fijos)
expect_contains "resumen de valores fijos (impresora)" "$FIJOS" '$489.990'
expect_contains "resumen de valores fijos (vida útil)" "$FIJOS" '5.000'
expect_contains "resumen de valores fijos (mantenimiento)" "$FIJOS" '$50'
shot pantalla_resultados_base

echo "== Indicadores: hora con minutos, contador y resumen de datos usados"
ST1=$(get tv_status)
expect_contains "estado tras calcular" "$ST1" "actualizado a las"
printf '%s' "$ST1" | grep -qE '[0-9]{2}:[0-9]{2}:[0-9]{2}' || fail "el estado no lleva hora HH:mm:ss: '$ST1'"
echo "OK: el estado lleva hora con minutos y segundos"
N1=$(count_of "$ST1"); [ -n "$N1" ] || fail "no se encontró el número de cálculo en '$ST1'"
CON=$(get tv_calculado_con)
expect_contains "calculado con: material" "$CON" "100 g de PLA"
expect_contains "calculado con: unidades" "$CON" "2 unidades"
expect_contains "calculado con: tiempo" "$CON" "5 h 0 min"
expect_contains "calculado con: watts" "$CON" "95 W"
expect_contains "calculado con: precio y región" "$CON" 'Metropolitana de Santiago'
expect_contains "verificación de suma" "$(get tv_cuadra)" "Verificado"
fill et_gramos 200
expect_digits "material con 200 g por unidad" "$(get tv_material)" 8000
ST2=$(get tv_status); N2=$(count_of "$ST2")
[ "$N2" -gt "$N1" ] || fail "el contador de cálculos no avanzó al cambiar un valor ($N1 -> $N2)"
echo "OK: al cambiar un valor se recalculó (cálculo n.º $N1 -> n.º $N2, '$ST2')"
expect_contains "calculado con refleja el cambio" "$(get tv_calculado_con)" "200 g de PLA"
fill et_gramos 100
expect_digits "total al volver a 100 g" "$(get tv_total)" 5715

echo "== CALCULAR: queda marcado y la pantalla baja a los totales"
[ "$(get btn_calcular)" = "CALCULAR" ] || fail "el botón debía decir CALCULAR antes de pulsarlo"
tap btn_calcular
sleep 2
$H visible tv_total || fail "tras CALCULAR la pantalla no bajó hasta los totales"
echo "OK: tras CALCULAR el costo del lote queda a la vista"
expect_contains "el botón queda marcado" "$(get btn_calcular)" "CALCULADO"
fill et_gramos 101
[ "$(get btn_calcular)" = "CALCULAR" ] || fail "al cambiar un dato el botón debía volver a CALCULAR"
echo "OK: al cambiar un dato el botón vuelve a CALCULAR"
fill et_gramos 100

echo "== Botones de suma rápida (un solo botón bajo cada casilla)"
read -r R G B <<< "$($H bgrgb btn_mas_pieza)"; echo "botón + 1 unidad (claro): $R $G $B"
[ $((B-R)) -gt 20 ] || fail "el botón rápido debía verse azulado y distinto del fondo ($R $G $B)"
echo "OK: el botón rápido se distingue del fondo en modo claro"
tap btn_mas_pieza
expect_digits "unidades con + 1 unidad (2 -> 3)" "$(get et_piezas)" 3
expect_digits "las unidades multiplican: costo del lote con 3 unidades" "$(get tv_total)" 8572
expect_digits "el costo unitario no cambia con las unidades" "$(get tv_unitario)" 2857
fill et_piezas 2
tap btn_mas_horas
expect_digits "horas con + 1 hora (5 -> 6)" "$(get et_horas)" 6
fill et_horas 5
tap btn_mas_minutos
tap btn_mas_minutos
tap btn_mas_minutos
expect_digits "minutos con + 10 min tres veces" "$(get et_minutos)" 30
expect_digits "electricidad con 5,5 h por unidad (95 W x 5,5 h x 247 x 2)" "$(get tv_electricidad)" 258
fill et_minutos 55
tap btn_mas_minutos
expect_digits "minutos tras pasar de 60 (55 + 10 = 5)" "$(get et_minutos)" 5
expect_digits "la hora completa pasa a la casilla de horas (5 -> 6)" "$(get et_horas)" 6
fill et_horas 5
fill et_minutos 0
expect_digits "total tras volver a 5 h" "$(get tv_total)" 5715

echo "== Reloj: la hora mostrada es la del móvil y avanza en vivo"
clock_min() { printf '%s' "$1" | grep -oE '[0-9]{2}:[0-9]{2}:[0-9]{2}' | head -1; }
T1=$(clock_min "$(get tv_barra_hora)"); [ -n "$T1" ] || fail "la barra no muestra el reloj en vivo"
DEV=$(adb shell date +%H:%M:%S | tr -d '\r')
echo "reloj de la app: $T1 | reloj del móvil (adb): $DEV"
python3 - "$T1" "$DEV" <<'PY'
import sys
def secs(t):
    h, m, s = map(int, t.split(":")); return (h % 12) * 3600 + m * 60 + s   # compara en 12 h por si el móvil usa 12 h
a, b = secs(sys.argv[1]), secs(sys.argv[2])
d = min((a - b) % 43200, (b - a) % 43200)
print("diferencia:", d, "s")
assert d <= 150, "la hora de la app no coincide con la del móvil"
PY
echo "OK: la hora de la app coincide con la del móvil"
sleep 3
T2=$(clock_min "$(get tv_barra_hora)")
[ "$T1" != "$T2" ] || fail "el reloj en vivo no avanzó ($T1)"
echo "OK: el reloj avanza en vivo ($T1 -> $T2)"

echo "== Otros costos por pieza (embalaje, envío)"
fill et_otros 500
expect_digits "otros costos (500 x 2 unidades)" "$(get tv_otros)" 1000
expect_digits "total con otros costos" "$(get tv_total)" 6715
expect_digits "unitario con otros costos" "$(get tv_unitario)" 3357
expect_contains "barra fija sigue al total" "$(get tv_barra_total)" '6.715'
expect_contains "calculado con incluye otros costos" "$(get tv_calculado_con)" "otros"
fill et_otros ""
expect_digits "total sin otros costos" "$(get tv_total)" 5715

echo "== Colores de los campos (modo claro): vacío blanco, con valor verde"
read -r R G B <<< "$($H bgrgb et_otros)"; echo "campo vacío: $R $G $B"
[ $((R+G+B)) -gt 700 ] || fail "el campo vacío debía verse claro ($R $G $B)"
read -r R G B <<< "$($H bgrgb et_precio_kg)"; echo "campo con valor: $R $G $B"
[ $((G-R)) -gt 10 ] || fail "el campo con valor debía verse verdoso ($R $G $B)"
echo "OK: campo vacío claro y campo con valor verdoso en modo claro"

echo "== Otros materiales del menú"
pick sp_material "ABS"
expect_digits "electricidad con ABS (200 W)" "$(get tv_electricidad)" 494
expect_digits "total con ABS" "$(get tv_total)" 5974
pick sp_material "PETG"
expect_digits "electricidad con PETG (120 W)" "$(get tv_electricidad)" 296
expect_contains "PETG indicado como estimado" "$(get tv_material_info)" "estimado"
pick sp_material "Otro"
expect_empty "campo watts propio vacío con Otro" "$(get et_watts)"
fill et_watts 160
expect_digits "electricidad con watts propios (160 W)" "$(get tv_electricidad)" 395
expect_contains "calculado con muestra los watts propios" "$(get tv_calculado_con)" "160 W"
shot pantalla_material_otro
pick sp_material "PLA"
expect_digits "total con PLA otra vez" "$(get tv_total)" 5715

echo "== Extras: mano de obra, merma, margen e IVA"
fill et_horas_trabajo 2
fill et_costo_hora_trabajo 3000
fill et_merma 1
fill et_margen 30
read -r R G B <<< "$($H bgrgb et_margen)"; echo "campo de ganancia % con foco y valor: $R $G $B"
[ $((G-R)) -gt 10 ] || fail "el campo del porcentaje de ganancia debía verse verde aunque tenga el foco ($R $G $B)"
echo "OK: el último campo (ganancia %) se ve verde apenas se llena, aun con el foco"
expect_digits "mano de obra" "$(get tv_mano_obra)" 6000
expect_digits "unidad fallida (1 x (5714,63 + 6000) / 2)" "$(get tv_merma)" 5857
expect_digits "total con extras" "$(get tv_total)" 17572
expect_digits "unitario con extras" "$(get tv_unitario)" 8786
expect_digits "precio de venta" "$(get tv_precio_venta)" 11422
expect_empty "precio con IVA (IVA desactivado)" "$(get tv_precio_iva)"
expect_digits "ganancia del lote" "$(get tv_ganancia)" 5272
expect_digits "ganancia por unidad (30% del costo unitario)" "$(get tv_ganancia_unit)" 2636
expect_digits "precio de venta del lote" "$(get tv_venta_lote)" 22844
expect_contains "la ganancia se indica aparte con su porcentaje" "$(get tv_venta_ayuda)" "30%"
expect_contains "barra fija muestra la venta" "$(get tv_barra_detalle)" '11.422'
expect_digits "el costo total no cambia por la ganancia" "$(get tv_total)" 17572
tap cb_iva
expect_digits "precio con IVA (IVA activado)" "$(get tv_precio_iva)" 13592
expect_contains "calculado con incluye los extras" "$(get tv_calculado_con)" "1 unidad fallida"
expect_contains "verificación de suma con extras" "$(get tv_cuadra)" "Verificado"
shot pantalla_extras

echo "== Guardar imagen del detalle en la galería, con el nombre del cálculo"
tap btn_guardar_imagen
sleep 4
MEDIA=$(adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path)
echo "$MEDIA" | grep -E "Llavero_Erretrez_[0-9]{8}_[0-9]{6}\.png" | head -3
IMG=$(echo "$MEDIA" | grep -oE "Llavero_Erretrez_[0-9]{8}_[0-9]{6}\.png" | head -1)
[ -n "$IMG" ] || fail "la imagen con el nombre del cálculo no apareció en la galería"
echo "$MEDIA" | grep "$IMG" | grep -q "CalculadoraCostos3D" || fail "la imagen no quedó en Pictures/CalculadoraCostos3D"
adb pull "/sdcard/Pictures/CalculadoraCostos3D/$IMG" "$OUT/imagen_guardada.png" > /dev/null || fail "no se pudo leer la imagen guardada"
python3 - "$OUT/imagen_guardada.png" <<'PY'
import struct, sys
d = open(sys.argv[1], "rb").read()
assert d[:8] == b"\x89PNG\r\n\x1a\n", "no es un PNG"
w, h = struct.unpack(">II", d[16:24])
print("imagen:", w, "x", h, "px,", len(d), "bytes")
assert w == 1080 and h > 800, "tamaño inesperado"
assert len(d) > 30000, "la imagen parece vacía"
PY
echo "OK: imagen guardada en la galería con el nombre del cálculo ($IMG)"

echo "== Compartir resumen (solo se comprueba que abre el selector y la app sigue viva)"
tap btn_compartir
# Se espera a que el selector de Android esté al frente; solo entonces se cierra con "atrás"
# (si se pulsara antes, "atrás" cerraría la propia app).
for i in $(seq 1 15); do
  FOCUS=$(adb shell dumpsys window | grep -E "mCurrentFocus" | head -1)
  case "$FOCUS" in *"$PKG"*) sleep 1;; *) echo "selector al frente: $FOCUS"; break;; esac
done
case "$FOCUS" in *"$PKG"*) echo "AVISO: el selector no apareció (sin apps para compartir en el emulador)";; *) adb shell input keyevent 4;; esac
sleep 2
echo "foco tras cerrar el selector: $(adb shell dumpsys window | grep mCurrentFocus | head -1)"
adb shell uiautomator dump /sdcard/ui_dbg.xml > /dev/null; adb pull /sdcard/ui_dbg.xml "$OUT/ui_tras_compartir.xml" > /dev/null
grep -oE 'resource-id="[^"]*"' "$OUT/ui_tras_compartir.xml" | head -12
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró al compartir"
adb shell input keyevent 3   # HOME: suelta cualquier ventana residual del selector de compartir
sleep 1
adb shell am start -n "$ACT" > /dev/null   # trae la app al frente (no la reinicia)
sleep 2
# Diagnóstico (no falla): ¿responde el desplazamiento tras compartir?
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1); SW=${W%x*}; SH=${W#*x}
for n in 1 2 3; do
  adb shell input swipe $((SW/2)) $((SH*70/100)) $((SW/2)) $((SH*30/100)) 400
  sleep 1
  adb shell uiautomator dump /sdcard/ui_diag.xml > /dev/null 2>&1 || true
  adb pull /sdcard/ui_diag.xml "$OUT/ui_diag.xml" > /dev/null 2>&1 || true
  echo "diagnóstico tras compartir, deslizamiento $n: $(grep -oE 'id/(tv|et|btn|sp|cb|sw)_[a-z_0-9]+' "$OUT/ui_diag.xml" | head -8 | tr '\n' ' ')"
done
expect_contains "los resultados siguen en pantalla tras compartir (barra fija)" "$(get tv_barra_total)" '17.572'
expect_digits "los resultados siguen en pantalla tras compartir (costo por hora)" "$(get tv_por_hora)" 1757

echo "== Validación: unidades = 0 no debe dar resultado ni dividir por cero"
fill et_piezas 0
tap btn_calcular
expect_contains "estado con piezas 0" "$(get tv_status)" "Faltan datos"
expect_empty "total con piezas 0" "$(get tv_total)"
expect_empty "unitario con piezas 0" "$(get tv_unitario)"
fill et_piezas 2
expect_digits "total tras corregir unidades" "$(get tv_total)" 17572

echo "== Productos de varias piezas: + AGREGAR PIEZA (cada pieza suma su filamento, tiempo y consumo)"
tap btn_agregar_pieza
expect_contains "título de la pieza 2" "$(get p2_titulo)" "Pieza 2"
expect_digits "el precio del filamento se copia de la pieza anterior" "$(get p2_precio_kg)" 20000
expect_contains "el material se copia de la pieza anterior" "$(get p2_info)" "95 W"
tap btn_calcular
expect_contains "con la pieza 2 vacía faltan datos" "$(get tv_status)" "Faltan datos"
expect_empty "sin total mientras la pieza 2 está incompleta" "$(get tv_total)"
fill p2_gramos 50
fill p2_horas 1
tap p2_mas_horas
expect_digits "horas de la pieza 2 con + 1 hora (1 -> 2)" "$(get p2_horas)" 2
fill p2_minutos 20
tap p2_mas_minutos
expect_digits "minutos de la pieza 2 con + 10 min (20 -> 30)" "$(get p2_minutos)" 30
pick p2_material "PETG"
expect_contains "info del material de la pieza 2" "$(get p2_info)" "120 W"
# Por unidad (2 unidades en el lote). Pieza 1: material 2000, electricidad 117,325, depreciación 489,99,
# mantenimiento 250 = 2857,315. Pieza 2: material 1000, electricidad 0,12 kW x 2,5 h x 247 = 74,1,
# depreciación 244,995, mantenimiento 125 = 1444,095. Base del lote 8602,82 + mano de obra 6000 (total del trabajo);
# una unidad fallida = (8602,82 + 6000) / 2 = 7301,41.
expect_digits "material de las dos piezas (2 unidades)" "$(get tv_material)" 6000
expect_digits "electricidad de las dos piezas (2 x (95 W x 5 h + 120 W x 2,5 h) x 247)" "$(get tv_electricidad)" 383
expect_digits "depreciación de las dos piezas (15 h)" "$(get tv_depreciacion)" 1470
expect_digits "mantenimiento de las dos piezas (15 h)" "$(get tv_mantenimiento)" 750
expect_digits "unidad fallida con las dos piezas y la mano de obra" "$(get tv_merma)" 7301
expect_digits "costo del lote con dos piezas" "$(get tv_total)" 21904
expect_digits "costo unitario con dos piezas" "$(get tv_unitario)" 10952
expect_digits "precio de venta con dos piezas" "$(get tv_precio_venta)" 14238
expect_digits "costo por gramo (300 g en el lote)" "$(get tv_por_gramo)" 73
expect_digits "costo por hora (15 h en el lote)" "$(get tv_por_hora)" 1460
expect_contains "filamento total del lote" "$(get tv_gramos_total)" "300 g"
expect_contains "tiempo total del lote" "$(get tv_horas_total)" "15 h"
has_text "Pieza 1 · 100 g · 5 h"
has_text "Pieza 2 · 50 g · 2 h 30 min"
CON2=$(get tv_calculado_con)
expect_contains "calculado con: cantidad de piezas" "$CON2" "2 piezas por unidad"
expect_contains "calculado con: gramos por unidad" "$CON2" "150 g por unidad"
expect_contains "calculado con: tiempo por unidad" "$CON2" "7 h 30 min por unidad"
expect_contains "verificación de suma con dos piezas" "$(get tv_cuadra)" "Verificado"
shot pantalla_dos_piezas
echo "== La pieza agregada sobrevive al cambio de tema"
tap sw_oscuro
sleep 3
expect_dark "con la pieza 2 en modo oscuro"
expect_digits "gramos de la pieza 2 tras cambiar de tema" "$(get p2_gramos)" 50
expect_contains "material de la pieza 2 tras cambiar de tema" "$(get p2_info)" "120 W"
expect_digits "el total con dos piezas sobrevive al cambio de tema" "$(get tv_total)" 21904
shot pantalla_dos_piezas_oscuro
tap sw_oscuro
sleep 3
expect_light "de vuelta en modo claro con la pieza 2"
echo "== QUITAR la pieza: vuelve el cálculo de una sola pieza"
tap p2_quitar
expect_digits "total tras quitar la pieza 2" "$(get tv_total)" 17572
ALL_IDS=$($H ids)
if echo "$ALL_IDS" | grep -qx "p2_gramos"; then fail "la pieza 2 debía desaparecer tras QUITAR"; fi
echo "OK: la pieza 2 desapareció"

echo "== Modo oscuro: se aplica, conserva los datos y se recuerda al reiniciar"
tap sw_oscuro
sleep 3
expect_dark "tras activar el modo oscuro"
shot pantalla_oscuro
read -r R G B <<< "$(rgb_estable et_otros)"; echo "campo vacío (oscuro): $R $G $B"
[ $((R+G+B)) -lt 150 ] || fail "el campo vacío debía verse oscuro en modo oscuro ($R $G $B)"
read -r R G B <<< "$(rgb_estable et_precio_kg)"; echo "campo con valor (oscuro): $R $G $B"
[ $((R+G+B)) -lt 250 ] || fail "el campo con valor debía seguir oscuro ($R $G $B)"
[ $((G-R)) -gt 20 ] || fail "el campo con valor debía verse verdoso ($R $G $B)"
read -r R G B <<< "$(rgb_estable btn_mas_pieza)"; echo "botón + 1 unidad (oscuro): $R $G $B"
[ $((B-R)) -gt 30 ] || fail "el botón rápido debía verse azulado en modo oscuro ($R $G $B)"
echo "OK: el botón rápido se distingue del fondo en modo oscuro"
ML=$($H maxluma et_precio_kg); echo "luminosidad máxima del texto ingresado: $ML"
[ "$ML" -gt 200 ] || fail "el texto ingresado debía verse blanco en modo oscuro (luminosidad $ML)"
echo "OK: en modo oscuro los campos son oscuros, el texto ingresado es blanco y el campo con valor se ve verdoso"
expect_digits "el total sobrevive al cambio de tema" "$(get tv_total)" 17572
expect_contains "el material sobrevive al cambio de tema" "$(get tv_calculado_con)" "PLA"
adb shell am force-stop "$PKG"; sleep 2
start_app
expect_dark "tras reiniciar la app (el modo oscuro se mantiene)"
[ "$(get sw_oscuro checked)" = "true" ] || fail "el interruptor debía quedar activado tras reiniciar"
echo "OK: interruptor activado tras reiniciar"
expect_empty "campo kg vacío al reiniciar" "$(get et_precio_kg)"
expect_contains "la región se recuerda tras reiniciar" "$(get tv_region_ref)" '$247'
expect_contains "el material se recuerda tras reiniciar" "$(get tv_material_info)" "95 W"
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
has_text '$298'
has_text "est."
shot pantalla_tabla
$H taptext "Antofagasta" || fail "no se pudo tocar la fila de Antofagasta"
expect_contains "tocar una fila elige la región" "$(get tv_region_ref)" '$248'
expect_contains "la región sin dato propio se indica como estimada" "$(get tv_region_ref)" "ESTIMADO"
tap btn_tabla

echo "== Región sin precio en la tabla: aparece el precio manual y la boleta"
pick sp_region "Otra tarifa"
expect_contains "aviso de tarifa manual" "$(get tv_region_ref)" "Tarifa manual"
expect_empty "precio manual vacío" "$(get et_precio_kwh)"
pick sp_material "PLA"
fill et_precio_kg 20000
fill et_gramos 100
fill et_piezas 1
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
pick sp_material "Elige el material"
tap btn_calcular
expect_contains "falta elegir material" "$(get tv_material_info)" "Elige un material"
pick sp_material "PLA"
tap btn_limpiar_arriba
for id in et_nombre et_precio_kg et_gramos et_piezas et_horas et_minutos et_horas_trabajo et_otros \
          et_costo_hora_trabajo et_merma et_margen et_boleta_monto et_boleta_kwh; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
for id in tv_material tv_electricidad tv_depreciacion tv_mantenimiento tv_mano_obra tv_merma \
          tv_total tv_unitario tv_precio_venta tv_precio_iva tv_ganancia tv_ganancia_unit tv_venta_lote; do
  expect_empty "$id tras LIMPIAR" "$(get $id)"
done
[ "$(get cb_iva checked)" = "false" ] || fail "el IVA debía quedar desmarcado tras LIMPIAR"
echo "OK: IVA desmarcado"
[ "$(get btn_guardar_imagen enabled)" = "false" ] || fail "GUARDAR IMAGEN debía quedar desactivado sin resultado"
[ "$(get btn_compartir enabled)" = "false" ] || fail "COMPARTIR debía quedar desactivado sin resultado"
echo "OK: COMPARTIR desactivado sin resultado"
expect_contains "barra fija vacía tras LIMPIAR" "$(get tv_barra_total)" "—"
expect_empty "otros costos tras LIMPIAR" "$(get tv_otros)"
expect_empty "costo por gramo tras LIMPIAR" "$(get tv_por_gramo)"
expect_contains "el material se conserva tras LIMPIAR" "$(get tv_material_info)" "95 W"
expect_contains "la región se conserva tras LIMPIAR" "$(get tv_region_ref)" "Tarifa manual"

echo "== LIMPIAR superior y LIMPIAR inferior hacen lo mismo"
fill et_gramos 5
tap btn_limpiar
expect_empty "et_gramos tras el LIMPIAR inferior" "$(get et_gramos)"
fill et_gramos 5
tap btn_limpiar_arriba
expect_empty "et_gramos tras el LIMPIAR superior" "$(get et_gramos)"

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
expect_contains "precio de la región detectada" "$(get tv_region_ref)" '$247'
ALL_IDS=$($H ids)
if echo "$ALL_IDS" | grep -qx "et_precio_kwh"; then fail "con precio en la tabla no debe verse la casilla del kWh"; fi
echo "OK: con precio en la tabla la casilla del kWh no aparece"

echo "== Verificando que sigue viva y sin fallos"
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró durante la prueba"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "excepción fatal durante la prueba"; fi

adb logcat -d -t 400 > "$OUT/logcat.txt" || true
echo "PRUEBA DE EMULADOR: OK"
