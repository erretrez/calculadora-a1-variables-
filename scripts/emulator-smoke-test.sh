#!/usr/bin/env bash
# Prueba en emulador: instala el APK, abre la app, verifica que no falle,
# que los 10 campos estén vacíos y que un cálculo real dé el resultado esperado.
set -euo pipefail

APK="${1:?uso: emulator-smoke-test.sh <ruta-al-apk>}"
PKG="cl.erretrez.calculadora"
ACT="$PKG/.MainActivity"
OUT="evidence"
mkdir -p "$OUT"

fail() { echo "FALLO: $*"; adb logcat -d -t 300 > "$OUT/logcat.txt" || true; exit 1; }

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
  cat "$OUT/crash.txt"; fail "la app produjo una excepción fatal"
fi

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$OUT/$1" > /dev/null
}

echo "== Estado inicial de la pantalla"
dump_ui ui_inicial.xml
adb exec-out screencap -p > "$OUT/pantalla_inicial.png" || true

python3 - "$OUT/ui_inicial.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
nodes = list(ET.parse(sys.argv[1]).iter("node"))
edits = [n for n in nodes if n.get("class") == "android.widget.EditText"]
ids = [n.get("resource-id", "") for n in nodes]
print("EditText visibles:", len(edits))
assert len(edits) >= 5, "se esperaban al menos 5 campos visibles en la primera pantalla"
for n in edits:
    txt = n.get("text", "")
    assert not re.search(r"\d", txt), f"campo con valor precargado: {txt!r}"
print("OK: campos vacíos al iniciar")
PY

echo "== Ingresando datos reales mediante teclado"
# Posición del primer campo desde el dump
read -r X Y < <(python3 - "$OUT/ui_inicial.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse(sys.argv[1]).iter("node"):
    if n.get("resource-id", "").endswith(":id/et_precio_kg"):
        a, b, c, d = map(int, re.findall(r"\d+", n.get("bounds")))
        print((a + c) // 2, (b + d) // 2); break
else:
    sys.exit("no se encontró et_precio_kg")
PY
)
adb shell input tap "$X" "$Y"
sleep 1
VALUES=(20000 100 2 5 0 200 150 400000 5000 100)
for i in "${!VALUES[@]}"; do
  adb shell input text "${VALUES[$i]}"
  sleep 0.5
  if [ "$i" -lt $((${#VALUES[@]} - 1)) ]; then
    adb shell input keyevent KEYCODE_TAB
    sleep 0.5
  fi
done
adb shell input keyevent KEYCODE_BACK   # cierra el teclado
sleep 1

echo "== Desplazando hasta los resultados"
adb shell input swipe 540 1700 540 400 300
adb shell input swipe 540 1700 540 400 300
sleep 1
dump_ui ui_resultados.xml
adb exec-out screencap -p > "$OUT/pantalla_resultados.png" || true

python3 - "$OUT/ui_resultados.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
texts = {}
for n in ET.parse(sys.argv[1]).iter("node"):
    rid = n.get("resource-id", "")
    if ":id/tv_" in rid:
        texts[rid.split("/")[-1]] = n.get("text", "")
print(texts)
digits = lambda s: re.sub(r"\D", "", s)
expected = {
    "tv_material": "2000",
    "tv_electricidad": "150",
    "tv_depreciacion": "400",
    "tv_mantenimiento": "500",
    "tv_total": "3050",
    "tv_unitario": "1525",
}
for k, v in expected.items():
    assert k in texts, f"no se encontró {k} en pantalla"
    assert digits(texts[k]) == v, f"{k}: esperado {v}, obtenido {texts[k]!r}"
print("OK: los 6 resultados coinciden con el cálculo esperado")
PY

H="python3 scripts/ui_helper.py"
digits() { tr -cd '0-9'; }
expect_digits() {  # expect_digits <descripción> <obtenido> <esperado>
  local got
  got=$(printf '%s' "$2" | digits)
  [ "$got" = "$3" ] || fail "$1: esperado '$3', obtenido '$2'"
  echo "OK: $1 -> '$2'"
}
expect_empty() {  # expect_empty <descripción> <obtenido>
  [ -z "$(printf '%s' "$2" | digits)" ] || fail "$1: debía estar vacío y fue '$2'"
  echo "OK: $1 vacío"
}

echo "== LIMPIAR vacía campos y resultados"
$H tap btn_limpiar || fail "no se pudo tocar LIMPIAR"
expect_empty "tv_total tras LIMPIAR" "$($H text tv_total)"
expect_empty "tv_unitario tras LIMPIAR" "$($H text tv_unitario)"
for id in et_precio_kg et_gramos et_piezas et_horas et_minutos et_watts et_precio_kwh et_precio_impresora et_vida_util et_mantenimiento; do
  expect_empty "$id tras LIMPIAR" "$($H text $id)"
done

echo "== Valores sugeridos visibles y fijos"
expect_digits "sugerido precio impresora" "$($H text tv_sug_precio_impresora)" "450000"
expect_digits "sugerido vida útil" "$($H text tv_sug_vida_util)" "5000"
expect_digits "sugerido mantenimiento" "$($H text tv_sug_mantenimiento)" "100"

echo "== Botones USAR copian el sugerido al campo editable"
$H tap btn_usar_precio_impresora || fail "no se pudo tocar USAR (impresora)"
$H tap btn_usar_vida_util || fail "no se pudo tocar USAR (vida útil)"
$H tap btn_usar_mantenimiento || fail "no se pudo tocar USAR (mantenimiento)"
expect_digits "campo precio impresora" "$($H text et_precio_impresora)" "450000"
expect_digits "campo vida útil" "$($H text et_vida_util)" "5000"
expect_digits "campo mantenimiento" "$($H text et_mantenimiento)" "100"
# El texto sugerido sigue ahí (es fijo, aparte del campo editable)
expect_digits "sugerido sigue visible" "$($H text tv_sug_precio_impresora)" "450000"
adb exec-out screencap -p > "$OUT/pantalla_sugeridos.png" || true

echo "== Verificando que sigue viva y sin fallos"
adb shell pidof "$PKG" > /dev/null || fail "la app se cerró durante la prueba"
if adb logcat -d | grep -q "FATAL EXCEPTION"; then fail "excepción fatal durante la prueba"; fi

adb logcat -d -t 300 > "$OUT/logcat.txt" || true
echo "PRUEBA DE EMULADOR: OK"
