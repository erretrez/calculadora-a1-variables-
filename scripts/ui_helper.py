#!/usr/bin/env python3
"""Ayudante de UI para la prueba de emulador (adb + uiautomator).

Uso:
  ui_helper.py tap  <id>           # desplaza hasta el elemento y lo toca
  ui_helper.py fill <id> <texto>   # toca el campo, borra su contenido y escribe
  ui_helper.py get  <id> [attr]    # imprime un atributo del elemento (por defecto: text)
  ui_helper.py ids                 # recorre toda la pantalla y lista los ids que existen
  ui_helper.py taptext <texto>     # toca el elemento cuyo texto contiene <texto> (p. ej. en un menú)
  ui_helper.py hastext <texto>     # sale con 0 si hay un elemento visible/desplazable con ese texto
  ui_helper.py bgrgb <id>          # color (r g b) del fondo de un campo, leído de una captura
  ui_helper.py maxluma <id>        # luminosidad máxima (0-255) dentro del elemento (p. ej. su texto)
  ui_helper.py visible <id>        # sale con 0 si el elemento está en pantalla ahora (sin desplazar)
  ui_helper.py luma                # luminosidad (0-255) del fondo de la app, leída de una captura

<id> es el id sin prefijo (por ejemplo: btn_limpiar). Sale con código 1 si no se encuentra.
"""
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PKG = "cl.erretrez.calculadora"
STATE = os.path.join(tempfile.gettempdir(), "ui_helper_last")

# Orden de arriba hacia abajo en la pantalla: sirve para decidir hacia dónde desplazarse.
ORDER = (
    ["sw_oscuro", "et_nombre", "et_precio_kg", "et_gramos", "et_horas", "btn_mas_horas", "et_minutos", "btn_mas_minutos",
     "sp_material", "tv_material_info", "et_watts", "sp_perfil"]
    # Piezas adicionales (2.ª a 6.ª): quedan entre la pieza 1 y el botón + AGREGAR PIEZA.
    + [f"p{n}_{x}" for n in range(2, 7)
       for x in ("card", "titulo", "quitar", "precio_kg", "gramos", "horas", "mas_horas", "minutos", "mas_minutos",
                 "material", "info", "watts", "perfil")]
    + ["btn_agregar_pieza", "tv_ayuda_piezas", "sw_tiempo_global", "tv_ayuda_tiempo", "et_horas_global",
       "btn_mas_horas_global", "et_minutos_global", "btn_mas_minutos_global", "btn_perfiles", "et_piezas", "btn_mas_pieza",
       "sp_region", "btn_detectar", "tv_region_info",
       "tv_region_ref", "btn_tabla", "et_precio_kwh", "et_boleta_monto", "et_boleta_kwh",
       "tv_boleta_resultado", "btn_usar_boleta", "et_horas_trabajo", "et_costo_hora_trabajo",
       "et_merma", "et_otros", "et_margen", "cb_iva", "btn_calcular", "btn_limpiar", "tv_status",
       "tv_material", "tv_electricidad", "tv_depreciacion", "tv_mantenimiento", "tv_mano_obra",
       "tv_merma", "tv_otros", "tv_total", "tv_unitario", "tv_por_gramo", "tv_gramos_total", "tv_pct_kilo", "tv_horas_total", "tv_por_hora", "tv_perfiles_estado", "btn_registrar_consumo",
       "tv_calculado_con", "tv_cuadra", "tv_fijos", "tv_venta_ayuda", "tv_ganancia_unit",
       "tv_ganancia", "tv_precio_venta", "tv_precio_iva", "tv_venta_lote", "btn_guardar_imagen",
       "btn_compartir", "btn_copiar"]
)


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def screen_size():
    out = adb("shell", "wm", "size").stdout.strip().splitlines()
    m = re.search(r"(\d+)x(\d+)", out[-1]) if out else None
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2340)


W, H = screen_size()


# Si el emulador deja de responder a un deslizamiento, se prueba con otra columna y otra duración.
SWIPE_VARIANTS = ((0.50, "250"), (0.15, "400"), (0.85, "400"), (0.30, "700"))
_variant = [0]


def swipe_to_see_below():
    """Mueve el contenido hacia arriba (muestra lo que está más abajo)."""
    fx, dur = SWIPE_VARIANTS[_variant[0] % len(SWIPE_VARIANTS)]
    x = str(int(W * fx))
    adb("shell", "input", "swipe", x, str(int(H * 0.75)), x, str(int(H * 0.30)), dur)
    time.sleep(0.8)


def swipe_to_see_above():
    """Mueve el contenido hacia abajo (muestra lo que está más arriba)."""
    fx, dur = SWIPE_VARIANTS[_variant[0] % len(SWIPE_VARIANTS)]
    x = str(int(W * fx))
    adb("shell", "input", "swipe", x, str(int(H * 0.30)), x, str(int(H * 0.75)), dur)
    time.sleep(0.8)


def dump(retries=3):
    for _ in range(retries):
        adb("shell", "uiautomator", "dump", "/sdcard/h.xml")
        with tempfile.NamedTemporaryFile(suffix=".xml") as f:
            adb("pull", "/sdcard/h.xml", f.name)
            try:
                return list(ET.parse(f.name).iter("node"))
            except ET.ParseError:
                time.sleep(1)
    return []


def find_in(nodes, rid):
    full = f"{PKG}:id/{rid}"
    for n in nodes:
        if n.get("resource-id") == full:
            return n
    return None


def center(node):
    a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
    return (a + c) // 2, (b + d) // 2


def read_last():
    try:
        with open(STATE) as f:
            return f.read().strip()
    except OSError:
        return ""


def write_last(rid):
    try:
        with open(STATE, "w") as f:
            f.write(rid)
    except OSError:
        pass


def locate(rid, max_steps=34):
    """Desplaza la pantalla hasta que el elemento sea visible y lo devuelve."""
    if rid.startswith("tv_barra_"):  # la barra inferior es fija: siempre está en pantalla
        for _ in range(3):
            node = find_in(dump(), rid)
            if node is not None:
                return node
        return None
    below = True  # por defecto buscar hacia abajo
    last = read_last()
    if last in ORDER and rid in ORDER:
        below = ORDER.index(rid) >= ORDER.index(last)
    flipped = False
    firma_prev = None
    for step in range(max_steps):
        nodos = dump()
        node = find_in(nodos, rid)
        # Si la pantalla no cambió tras el deslizamiento anterior, se cambia la forma de deslizar.
        firma = tuple(n.get("bounds") for n in nodos if n.get("resource-id", "").startswith(f"{PKG}:id/"))
        if firma_prev is not None and firma == firma_prev:
            _variant[0] += 1
            print(f"AVISO: el deslizamiento no movió la pantalla; se prueba la variante {_variant[0] % len(SWIPE_VARIANTS)}",
                  file=sys.stderr)
        firma_prev = firma
        if node is not None:
            _, y = center(node)
            if H * 0.08 < y < H * 0.92:
                write_last(rid)
                return node
            # Visible solo en parte: acercarlo al centro.
            swipe_to_see_below() if y >= H * 0.92 else swipe_to_see_above()
            continue
        if step == 14 and not flipped:
            below, flipped = not below, True
        swipe_to_see_below() if below else swipe_to_see_above()
    return None


def all_ids():
    """Recorre la pantalla de arriba abajo una vez y devuelve todos los ids de la app que existen."""
    for _ in range(8):
        swipe_to_see_above()
    seen, prev = set(), None
    for _ in range(40):
        ids = {
            n.get("resource-id").split("/")[-1]
            for n in dump()
            if n.get("resource-id", "").startswith(f"{PKG}:id/")
        }
        seen |= ids
        if ids == prev:  # el contenido ya no se desplaza: se llegó al final
            break
        prev = ids
        swipe_to_see_below()
    write_last("btn_copiar")
    return seen


def hide_ime_if_shown():
    out = adb("shell", "dumpsys", "input_method").stdout
    if "mInputShown=true" in out:
        adb("shell", "input", "keyevent", "4")  # BACK cierra el teclado
        time.sleep(0.8)


def find_text(sub, max_steps=12):
    """Busca un elemento por texto, desplazando hacia abajo y luego hacia arriba."""
    for direction in ("down", "up"):
        for _ in range(max_steps):
            for n in dump():
                if sub in n.get("text", ""):
                    return n
            swipe_to_see_below() if direction == "down" else swipe_to_see_above()
    return None


def grab():
    """Captura cruda de pantalla: devuelve (bytes, ancho, alto, tamaño del encabezado)."""
    raw = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True).stdout
    w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
    return raw, w, h, len(raw) - w * h * 4


def px(raw, w, header, x, y):
    off = header + (y * w + x) * 4
    return raw[off], raw[off + 1], raw[off + 2]


def field_pixels(rid):
    """(raw, w, header, caja) de un elemento tras desplazarse hasta él."""
    node = locate(rid)
    if node is None:
        print(f"no se encontró {rid}", file=sys.stderr)
        sys.exit(1)
    raw, w, h, header = grab()
    a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
    return raw, w, header, (max(a, 0), max(b, 0), min(c, w), min(d, h))


def luma():
    """Luminosidad del fondo: un píxel del margen izquierdo, donde solo se ve el fondo de la app."""
    raw = subprocess.run(["adb", "exec-out", "screencap"], capture_output=True).stdout
    header = len(raw) - W * H * 4  # 12 o 16 bytes según la versión de Android
    if header not in (12, 16):
        # La captura puede venir con otra resolución que `wm size`: se toma la del encabezado.
        w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
        header = len(raw) - w * h * 4
        width, height = w, h
    else:
        width, height = W, H
    x, y = 3, height // 2
    off = header + (y * width + x) * 4
    r, g, b = raw[off], raw[off + 1], raw[off + 2]
    return int(0.299 * r + 0.587 * g + 0.114 * b)


def main():
    if len(sys.argv) == 3 and sys.argv[1] == "bgrgb":
        raw, w, header, (a, b, c, d) = field_pixels(sys.argv[2])
        r, g, bl = px(raw, w, header, c - 40, (b + d) // 2)  # lado derecho: nunca tiene texto
        print(r, g, bl)
        return
    if len(sys.argv) == 3 and sys.argv[1] == "maxluma":
        raw, w, header, (a, b, c, d) = field_pixels(sys.argv[2])
        best = 0
        for y in range(b + 4, d - 4, 2):
            for x in range(a + 4, c - 4, 2):
                r, g, bl = px(raw, w, header, x, y)
                best = max(best, int(0.299 * r + 0.587 * g + 0.114 * bl))
        print(best)
        return
    if len(sys.argv) == 2 and sys.argv[1] == "luma":
        print(luma())
        return
    if len(sys.argv) >= 3 and sys.argv[1] in ("taptext", "hastext"):
        node = find_text(sys.argv[2])
        if node is None:
            print(f"no se encontró el texto {sys.argv[2]!r}", file=sys.stderr)
            sys.exit(1)
        if sys.argv[1] == "taptext":
            x, y = center(node)
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1.0)
        return
    if len(sys.argv) == 3 and sys.argv[1] == "visible":
        # Sin desplazar: sale con 0 solo si el elemento ya está en pantalla ahora mismo.
        node = find_in(dump(), sys.argv[2])
        if node is None:
            sys.exit(1)
        _, y = center(node)
        sys.exit(0 if 0 < y < H else 1)
    if len(sys.argv) == 2 and sys.argv[1] == "ids":
        print("\n".join(sorted(all_ids())))
        return
    if len(sys.argv) < 3 or sys.argv[1] not in ("tap", "fill", "get"):
        sys.exit(__doc__)
    cmd, rid = sys.argv[1], sys.argv[2]
    node = locate(rid)
    if node is None:
        print(f"no se encontró {rid}", file=sys.stderr)
        sys.exit(1)

    if cmd == "get":
        attr = sys.argv[3] if len(sys.argv) > 3 else "text"
        print(node.get(attr, ""))
        return

    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.7)

    if cmd == "fill":
        text = sys.argv[3] if len(sys.argv) > 3 else ""
        # MOVE_END y 25 DEL: borra cualquier contenido previo del campo.
        adb("shell", "input", "keyevent", "123", *(["67"] * 25))
        if text:
            adb("shell", "input", "text", text)
        time.sleep(0.5)
        hide_ime_if_shown()


if __name__ == "__main__":
    main()
