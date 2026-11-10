#!/usr/bin/env python3
"""Ayudante de UI para la prueba de emulador (adb + uiautomator).

Uso:
  ui_helper.py tap  <id>           # desplaza hasta el elemento y lo toca
  ui_helper.py fill <id> <texto>   # toca el campo, borra su contenido y escribe
  ui_helper.py get  <id> [attr]    # imprime un atributo del elemento (por defecto: text)
  ui_helper.py ids                 # recorre toda la pantalla y lista los ids que existen

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
ORDER = [
    "et_precio_kg", "et_gramos", "et_piezas", "et_horas", "et_minutos", "et_watts",
    "tv_watts_estandar", "btn_usar_watts_estandar", "tv_watts_alto", "btn_usar_watts_alto",
    "et_precio_kwh", "sp_region", "btn_detectar", "tv_region_info", "tv_region_ref",
    "btn_usar_region", "et_boleta_monto", "et_boleta_kwh", "tv_boleta_resultado",
    "btn_usar_boleta", "tv_tarifa_guardada", "btn_guardar_tarifa", "btn_usar_tarifa",
    "et_horas_trabajo", "et_costo_hora_trabajo", "et_merma", "et_margen", "cb_iva",
    "btn_calcular", "btn_limpiar", "tv_status", "tv_material", "tv_electricidad",
    "tv_depreciacion", "tv_mantenimiento", "tv_mano_obra", "tv_merma", "tv_total",
    "tv_unitario", "tv_fijos", "tv_precio_venta", "tv_precio_iva", "tv_ganancia",
]


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def screen_size():
    out = adb("shell", "wm", "size").stdout.strip().splitlines()
    m = re.search(r"(\d+)x(\d+)", out[-1]) if out else None
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2340)


W, H = screen_size()


def swipe_to_see_below():
    """Mueve el contenido hacia arriba (muestra lo que está más abajo)."""
    adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.75)), str(W // 2), str(int(H * 0.30)), "250")
    time.sleep(0.8)


def swipe_to_see_above():
    """Mueve el contenido hacia abajo (muestra lo que está más arriba)."""
    adb("shell", "input", "swipe", str(W // 2), str(int(H * 0.30)), str(W // 2), str(int(H * 0.75)), "250")
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
    below = True  # por defecto buscar hacia abajo
    last = read_last()
    if last in ORDER and rid in ORDER:
        below = ORDER.index(rid) >= ORDER.index(last)
    flipped = False
    for step in range(max_steps):
        node = find_in(dump(), rid)
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
    write_last("tv_ganancia")
    return seen


def hide_ime_if_shown():
    out = adb("shell", "dumpsys", "input_method").stdout
    if "mInputShown=true" in out:
        adb("shell", "input", "keyevent", "4")  # BACK cierra el teclado
        time.sleep(0.8)


def main():
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
