#!/usr/bin/env python3
"""Ayudante de UI para la prueba de emulador.

Uso:
  ui_helper.py tap  <id>   # desplaza hasta encontrar el elemento y lo toca
  ui_helper.py text <id>   # desplaza hasta encontrar el elemento e imprime su texto

<id> es el id sin prefijo (por ejemplo: btn_limpiar). Sale con código 1 si no se encuentra.
"""
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

PKG = "cl.erretrez.calculadora"


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def screen_size():
    out = adb("shell", "wm", "size").stdout
    m = re.search(r"(\d+)x(\d+)", out.splitlines()[-1])
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2340)


W, H = screen_size()


def swipe(direction):
    """direction='down' lleva el contenido hacia abajo (muestra lo de arriba)."""
    x = W // 2
    y1, y2 = int(H * 0.30), int(H * 0.75)
    if direction == "down":
        adb("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "250")
    else:
        adb("shell", "input", "swipe", str(x), str(y2), str(x), str(y1), "250")
    time.sleep(0.8)


def dump():
    with tempfile.NamedTemporaryFile(suffix=".xml") as f:
        adb("shell", "uiautomator", "dump", "/sdcard/h.xml")
        adb("pull", "/sdcard/h.xml", f.name)
        return list(ET.parse(f.name).iter("node"))


def find_in(nodes, rid):
    full = f"{PKG}:id/{rid}"
    for n in nodes:
        if n.get("resource-id") == full:
            return n
    return None


def center(node):
    a, b, c, d = map(int, re.findall(r"\d+", node.get("bounds")))
    return (a + c) // 2, (b + d) // 2


def locate(rid):
    node = find_in(dump(), rid)
    if node is not None:
        return node
    for _ in range(6):          # ir al inicio
        swipe("down")
    for _ in range(10):         # bajar de a poco buscando
        node = find_in(dump(), rid)
        if node is not None:
            return node
        swipe("up")
    return None


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("tap", "text"):
        sys.exit(__doc__)
    cmd, rid = sys.argv[1], sys.argv[2]
    node = locate(rid)
    if node is None:
        print(f"no se encontró {rid}", file=sys.stderr)
        sys.exit(1)
    if cmd == "tap":
        x, y = center(node)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.8)
    else:
        print(node.get("text", ""))


if __name__ == "__main__":
    main()
