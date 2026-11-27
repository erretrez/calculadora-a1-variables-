#!/usr/bin/env python3
"""Genera el logo ERRETREZ (engranaje + ouroboros + nombre) y todos sus tamaños.

Uso: python3 scripts/generar_logo.py [carpeta_res]
Necesita rsvg-convert. Escribe los íconos del lanzador (adaptativo, clásico y redondo) y las tres capas de la
animación de inicio (engranaje, serpiente y nombre), todas con el mismo lienzo para que se superpongan exactas.
"""
import math
import os
import subprocess
import sys

RES = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res"
FONDO = "#0B1220"


def pts(radio, ang_ini, ang_fin, paso=3.0):
    n = max(2, int(abs(ang_fin - ang_ini) / paso) + 1)
    return [(radio * math.cos(math.radians(ang_ini + (ang_fin - ang_ini) * k / (n - 1))),
             radio * math.sin(math.radians(ang_ini + (ang_fin - ang_ini) * k / (n - 1)))) for k in range(n)]


def engranaje(dientes=12, r_ext=0.985, r_raiz=0.86, r_hueco=0.69):
    periodo = 360.0 / dientes
    p = []
    for k in range(dientes):
        c = k * periodo - 90
        p += pts(r_raiz, c - periodo / 2 + 1, c - 8.5)
        p += [(r_ext * math.cos(math.radians(c - 5.2)), r_ext * math.sin(math.radians(c - 5.2))),
              (r_ext * math.cos(math.radians(c + 5.2)), r_ext * math.sin(math.radians(c + 5.2)))]
        p += pts(r_raiz, c + 8.5, c + periodo / 2 - 1)
    d = "M " + " L ".join(f"{x:.4f},{y:.4f}" for x, y in p) + " Z "
    d += f"M {r_hueco},0 A {r_hueco},{r_hueco} 0 1 0 {-r_hueco},0 A {r_hueco},{r_hueco} 0 1 0 {r_hueco},0 Z"
    return d


def mezcla(c1, c2, t):
    a = [int(c1[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(c2[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(a[i] + (b[i] - a[i]) * t):02x}" for i in range(3))


PALETA = ["#2ee6c8", "#38bdf8", "#6366f1", "#a855f7", "#ec4899"]


def color_en(t):
    t = min(max(t, 0.0), 1.0) * (len(PALETA) - 1)
    i = min(int(t), len(PALETA) - 2)
    return mezcla(PALETA[i], PALETA[i + 1], t - i)


def serpiente(rs=0.47, cabeza=-75.0, cola=-50.0):
    """Ouroboros: el cuerpo da casi toda la vuelta en sentido horario y la cabeza muerde la punta de la cola."""
    inicio, fin = cola, cabeza + 360.0
    paso = 1.5
    n = int((fin - inicio) / paso)
    out = []
    for k in range(n):
        a0 = inicio + k * paso
        a1 = a0 + paso + 0.4
        t = k / (n - 1)
        ancho = 0.022 + (0.135 - 0.022) * min(1.0, (a0 - inicio) / 80.0) ** 0.8
        if t > 0.93:
            ancho += 0.012 * (t - 0.93) / 0.07
        x0, y0 = rs * math.cos(math.radians(a0)), rs * math.sin(math.radians(a0))
        x1, y1 = rs * math.cos(math.radians(a1)), rs * math.sin(math.radians(a1))
        out.append(f'<path d="M {x0:.4f},{y0:.4f} A {rs},{rs} 0 0 1 {x1:.4f},{y1:.4f}" fill="none" '
                   f'stroke="{color_en(t)}" stroke-width="{ancho:.4f}" stroke-linecap="round"/>')
    # brillo para dar volumen
    brillo = []
    for k in range(0, n, 2):
        a0 = inicio + k * paso
        t = k / (n - 1)
        if t < 0.12:
            continue
        r2 = rs - 0.03
        x0, y0 = r2 * math.cos(math.radians(a0)), r2 * math.sin(math.radians(a0))
        x1, y1 = r2 * math.cos(math.radians(a0 + 4)), r2 * math.sin(math.radians(a0 + 4))
        brillo.append(f'<path d="M {x0:.4f},{y0:.4f} A {r2},{r2} 0 0 1 {x1:.4f},{y1:.4f}" fill="none" '
                      f'stroke="#ffffff" stroke-opacity="0.28" stroke-width="0.022" stroke-linecap="round"/>')
    # escamas: marcas cortas cruzadas al cuerpo
    escamas = []
    for k in range(0, n, 5):
        a0 = inicio + k * paso
        t = k / (n - 1)
        if t < 0.15 or t > 0.94:
            continue
        w = 0.022 + (0.135 - 0.022) * min(1.0, (a0 - inicio) / 80.0) ** 0.8
        xa, ya = (rs - w * 0.42) * math.cos(math.radians(a0)), (rs - w * 0.42) * math.sin(math.radians(a0))
        xb, yb = (rs + w * 0.42) * math.cos(math.radians(a0 + 2.5)), (rs + w * 0.42) * math.sin(math.radians(a0 + 2.5))
        escamas.append(f'<path d="M {xa:.4f},{ya:.4f} L {xb:.4f},{yb:.4f}" stroke="#0b1220" stroke-opacity="0.35" '
                       f'stroke-width="0.012" stroke-linecap="round"/>')
    h = math.radians(cabeza)
    px, py = rs * math.cos(h), rs * math.sin(h)
    ang_t = math.degrees(math.atan2(math.cos(h), -math.sin(h)))
    cabeza_svg = f'''<g transform="translate({px:.4f},{py:.4f}) rotate({ang_t:.2f})">
      <path d="M -0.07,-0.092 C 0.04,-0.135 0.17,-0.085 0.235,0.005 C 0.17,0.075 0.05,0.115 -0.07,0.092 Z"
            fill="{color_en(1.0)}"/>
      <path d="M -0.07,-0.092 C 0.04,-0.135 0.17,-0.085 0.235,0.005 C 0.17,-0.04 0.04,-0.055 -0.07,-0.04 Z"
            fill="#ffffff" fill-opacity="0.22"/>
      <path d="M 0.235,0.005 C 0.20,0.035 0.12,0.045 0.04,0.04" fill="none" stroke="#0b1220" stroke-width="0.012"
            stroke-linecap="round"/>
      <circle cx="0.06" cy="-0.04" r="0.026" fill="#fff7c2"/>
      <ellipse cx="0.066" cy="-0.04" rx="0.008" ry="0.022" fill="#0b1220"/>
      <circle cx="0.205" cy="-0.022" r="0.008" fill="#0b1220"/>
      <path d="M 0.235,0.005 L 0.285,-0.012 M 0.235,0.005 L 0.285,0.028" stroke="#ff4d8d" stroke-width="0.012"
            stroke-linecap="round"/>
    </g>'''
    return "\n".join(out + escamas + brillo) + "\n" + cabeza_svg


DEFS = f'''<defs>
  <linearGradient id="gGear" x1="0" y1="-1" x2="0.6" y2="1" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#22c1ff"/><stop offset="0.5" stop-color="#7c5cf6"/><stop offset="1" stop-color="#e044c8"/>
  </linearGradient>
  <radialGradient id="gDisco" cx="0" cy="-0.15" r="1.05" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#1c2b66"/><stop offset="0.75" stop-color="#0d1530"/><stop offset="1" stop-color="#0d1530"/>
  </radialGradient>
  <linearGradient id="gTexto" x1="-0.8" y1="0" x2="0.8" y2="0" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#5fd4ff"/><stop offset="0.5" stop-color="#b79cff"/><stop offset="1" stop-color="#ff7ac8"/>
  </linearGradient>
  <linearGradient id="gBorde" x1="-1" y1="0" x2="1" y2="0" gradientUnits="userSpaceOnUse">
    <stop offset="0" stop-color="#22c1ff"/><stop offset="0.55" stop-color="#8b5cf6"/><stop offset="1" stop-color="#e044c8"/>
  </linearGradient>
  <filter id="sombra" x="-20%" y="-20%" width="140%" height="140%">
    <feDropShadow dx="0" dy="0.012" stdDeviation="0.02" flood-color="#000" flood-opacity="0.55"/>
  </filter>
</defs>'''

CAPA_DISCO = f'''<circle r="0.80" fill="url(#gDisco)"/>
<path d="{engranaje()}" fill="url(#gGear)" fill-rule="evenodd"/>
<circle r="0.69" fill="none" stroke="#ffffff" stroke-opacity="0.18" stroke-width="0.012"/>'''

CAPA_SERPIENTE = f'<g filter="url(#sombra)">{serpiente()}</g>'

CAPA_NOMBRE = '''<g filter="url(#sombra)">
<rect x="-0.80" y="-0.15" width="1.60" height="0.30" rx="0.06" fill="#0b1220" fill-opacity="0.96"
      stroke="url(#gBorde)" stroke-width="0.024"/>
<text x="0" y="0.068" text-anchor="middle" font-family="DejaVu Sans" font-weight="bold" font-size="0.19"
      letter-spacing="0.012" fill="url(#gTexto)">ERRETREZ</text>
</g>'''


def svg(contenido, vista=1.0, fondo_disco=True):
    """Lienzo cuadrado centrado en 0; vista = radio visible (1.0 = el logo llena el lienzo)."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{-vista} {-vista} {2 * vista} {2 * vista}">'
            f'{DEFS}{contenido}</svg>')


def render(svg_texto, tam, destino):
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    tmp = destino + ".svg"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(svg_texto)
    subprocess.run(["rsvg-convert", "-w", str(tam), "-h", str(tam), "-o", destino, tmp], check=True)
    os.remove(tmp)


COMPLETO = CAPA_DISCO + CAPA_SERPIENTE + CAPA_NOMBRE


def main():
    # Ícono adaptativo (primer plano): el disco ocupa 68/108 del lienzo, dentro del círculo visible de 72 dp.
    vista_adapt = 1.0 / (68.0 / 108.0)
    for carpeta, tam in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        render(svg(COMPLETO, vista_adapt), tam, f"{RES}/mipmap-{carpeta}/ic_launcher_foreground.png")
    # Ícono clásico y redondo (Android 7 y lanzadores que piden ícono redondo): disco completo, esquinas transparentes.
    for carpeta, tam in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for nombre in ("ic_launcher", "ic_launcher_round"):
            render(svg(COMPLETO, 1.0), tam, f"{RES}/mipmap-{carpeta}/{nombre}.png")
    # Capas de la animación de inicio (mismo lienzo, 768 px).
    render(svg(CAPA_DISCO, 1.0), 768, f"{RES}/drawable-nodpi/splash_engranaje.png")
    render(svg(CAPA_SERPIENTE, 1.0), 768, f"{RES}/drawable-nodpi/splash_serpiente.png")
    render(svg(CAPA_NOMBRE, 1.0), 768, f"{RES}/drawable-nodpi/splash_nombre.png")


if __name__ == "__main__":
    main()
