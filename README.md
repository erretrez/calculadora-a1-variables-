# Calculadora de costos de impresión 3D

App Android (Kotlin, sin dependencias externas, funciona sin conexión, sin permiso de Internet)
que calcula el costo de fabricación de una impresión 3D con una Bambu Lab A1 Combo.
**Todos los campos parten vacíos** y los valores los ingresa el usuario.

## Datos que se ingresan

Obligatorios: precio del filamento por kg, gramos usados, piezas, tiempo (horas y/o minutos),
**material** (menú, define los watts) y **región** (tabla, define el precio del kWh).
Opcionales: mano de obra, merma, margen e IVA (ver más abajo).

## Valores fijos de la impresora (desde la v1.3)

La depreciación y el mantenimiento se suman **automáticamente** con valores fijos, que no se editan
y solo aparecen en el resumen de resultados:

- Precio de la impresora: **$450.000**
- Vida útil estimada: **5.000 horas**
- Mantenimiento: **$100 por hora**

Las constantes están en `Suggested` (`CostCalculator.kt`). El motor de cálculo sigue admitiendo otros
valores y validándolos; solo la pantalla dejó de pedirlos.

## Fórmulas

| Componente | Fórmula |
|---|---|
| Material | (precio por kg / 1000) × gramos usados |
| Electricidad | (watts / 1000) × horas × precio kWh |
| Depreciación | ($450.000 / 5.000 h) × horas |
| Mantenimiento | $100 × horas |
| **Total** | material + electricidad + depreciación + mantenimiento (+ mano de obra y merma si se indican) |
| **Unitario** | total / cantidad de piezas |

`horas` = horas + minutos / 60.

## Validaciones

- Piezas: entero mayor que 0 (evita división por cero). La vida útil fija es mayor que 0.
- Tiempo total mayor que 0; minutos entre 0 y 59 (horas o minutos pueden quedar vacíos si el otro tiene valor).
- Resto de campos obligatorios: no negativos (se admite `0`).
- Se rechazan textos no numéricos y resultados no finitos.
- Punto o coma como decimal; sin separador de miles.

## Consumo de la impresora: menú de materiales (v1.4)

En vez de escribir los watts, se elige el material y la app usa el consumo promedio de la A1 con ese material:

| Material | Watts | Origen |
|---|---|---|
| PLA | 95 W | Wiki de Bambu Lab (promedio, 220 V) |
| ABS | 200 W | Wiki de Bambu Lab (promedio, 220 V) |
| PETG | 120 W | **Estimado** (cama y boquilla más calientes que en PLA) |
| TPU | 95 W | **Estimado** (temperaturas similares al PLA) |
| ASA | 200 W | **Estimado** (similar al ABS) |
| Otro | lo escribes tú | aparece el campo de watts |

Los valores estimados se marcan "(estimado)" en el menú porque no hay cifra publicada ni medida para ellos.
El PC no se incluye (la A1 no tiene cámara cerrada). La hoja técnica solo indica la potencia **máxima**
(1.300 W a 220 V), que dura un rato breve: no es consumo promedio y no se ofrece. Un enchufe medidor da el
dato exacto. Constantes en `Materiales` (`CostCalculator.kt`).

## Precio del kWh: tabla por región (v1.4)

La app **no** se conecta a internet (ni a Google ni a Gemini: no hay fuente oficial por región consultable de forma fiable y
exigiría permiso de Internet). En su lugar hay una **tabla de valores por región** (botón *VER TABLA DE PRECIOS POR REGIÓN*,
y también en el menú de regiones). Al elegir la región, el precio se aplica solo y la casilla del kWh **no aparece**.

- Hay precio para **8 de 16 regiones** (Antofagasta, Atacama, Valparaíso, Metropolitana, Biobío, Araucanía, Los Lagos y Magallanes), de un sitio no oficial (cuantomecuesta.com, abril 2026, anterior al alza de julio de 2026). Para las otras 8 no se encontró una fuente verificable y la tabla dice "sin dato": no se inventa un valor.
- Para esas regiones, o con la opción **Otra tarifa**, aparece la casilla del precio por kWh y la calculadora de **boleta** (monto total ÷ kWh consumidos = precio efectivo, incluye cargos fijos). Ese precio manual se recuerda.
- **DETECTAR MI REGIÓN** pide el permiso de ubicación solo al tocarlo y elige la región cuya capital está más cerca (aproximado, sin conexión; la ubicación no se guarda ni se envía).
- La región elegida se recuerda entre usos.

Constantes en `Regiones` (`CostCalculator.kt`).

## Indicadores de cálculo (v1.4)

- Estado: **"Cálculo n.º N actualizado a las HH:mm:ss"**: el número sube y la hora cambia en cada recálculo.
- Los totales parpadean brevemente al recalcular.
- **Calculado con:** una línea con los datos que entraron (material, gramos, piezas, tiempo, watts, precio y región, extras).
- **Verificado:** control interno de que material + electricidad + depreciación + mantenimiento + mano de obra + merma = total.

## Modo oscuro (v1.4)

Interruptor arriba a la derecha. La elección se guarda y se mantiene al reiniciar; los datos escritos no se pierden al cambiar de tema.
La primera vez sigue el modo del teléfono.

## Extras opcionales

Vacíos = no aplican; con todos vacíos el resultado es idéntico al base.

| Extra | Cálculo |
|---|---|
| Mano de obra | horas de trabajo × costo por hora (se exigen ambos datos) |
| Merma / fallos (%) | % × (material + electricidad + depreciación + mantenimiento); debe ser menor que 100 |
| Margen de ganancia (%) | precio de venta unitario = costo unitario × (1 + margen) |
| IVA 19 % (interruptor) | precio de venta con IVA = precio de venta × 1,19 |

El costo total incluye mano de obra y merma. Se muestran además el precio de venta unitario, el precio con IVA (si está activado) y la ganancia del lote sin IVA.

## Uso

El resultado se actualiza al escribir y también con el botón **CALCULAR** (que además marca los campos inválidos). **LIMPIAR** vacía los datos de la impresión y el material; la región y el precio manual guardados se conservan.
El formato monetario (CLP) está aislado en `CostCalculator.formatClp`; los cálculos no dependen de ninguna moneda.

La app declara los permisos de ubicación aproximada y precisa (solo se usan con el botón DETECTAR); no declara INTERNET.

## Compilación

GitHub Actions (`.github/workflows/build-apk.yml`) en cada push a `main`:

1. Pruebas unitarias (`./gradlew testDebugUnitTest`).
2. `./gradlew assembleDebug`.
3. Verifica en el APK: paquete, `MainActivity` como launcher, `MainActivity` presente en `classes.dex`, ausencia de permiso `INTERNET` y firma.
4. Instala el APK en un emulador Android, abre la app, comprueba que los campos estén vacíos, ingresa datos y valida los resultados, incluidos el menú de materiales, la tabla por región, los indicadores de cálculo, el modo oscuro (comprobado por el color real de la pantalla y tras reiniciar) y la detección de región con ubicación simulada (`scripts/emulator-smoke-test.sh`).
5. Solo si todo lo anterior pasa, publica el APK en **Releases** (tag `apk-latest`) y como artefacto del workflow.

Local: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Compatible con Android 7.0+ (minSdk 24), incluido el Redmi Note 13 5G.
Para instalar el APK hay que permitir "instalar apps de orígenes desconocidos".
