# Calculadora de costos de impresión 3D

App Android (Kotlin, sin dependencias externas, funciona sin conexión, sin permiso de Internet)
que calcula el costo de fabricación de una impresión 3D con una Bambu Lab A1 Combo.
**Todos los campos parten vacíos** y los valores los ingresa el usuario.

## Datos que se ingresan

Obligatorios: precio del filamento por kg, gramos usados, piezas, tiempo (horas y/o minutos),
**material** (menú, define los watts) y **región** (tabla, define el precio del kWh).
Opcionales: mano de obra, merma, otros costos por pieza, margen e IVA (ver más abajo).

## Valores fijos de la impresora (actualizados en la v1.6)

La depreciación y el mantenimiento se suman **automáticamente** con valores fijos, que no se editan y solo aparecen en el resumen:

| Valor | Cifra | Origen |
|---|---|---|
| Precio de la Bambu Lab A1 Combo | **$489.990** | Precio de mercado en Chile (con IVA) al 15-nov-2026: todotoner.cl, disponible. **pcfactory.cl no se pudo leer** (bloquea la lectura automática); otra tienda (makerschile.cl) lo mostraba en $567.000 sin stock. Si me indicas el precio de PCFactory se actualiza. |
| Vida útil para depreciar | **5.000 h** | **Estimación.** Bambu Lab no publica una vida útil; un usuario de su foro usa 5.000 h como un año de uso muy intenso. |
| Mantenimiento | **$50 por hora** | **Estimación** de reserva para repuestos: hotend ~$12/h + placa PEI ~$17/h + lubricación y piezas menores ~$21/h. Único precio verificado: hotend A1 de Bambu Lab a 10,99 EUR (tienda oficial UE). |

Depreciación por hora = $489.990 / 5.000 h = $97,998; mantenimiento = $50. Las constantes están en `Suggested` (`CostCalculator.kt`).

## Fórmulas

| Componente | Fórmula |
|---|---|
| Material | (precio por kg / 1000) × gramos usados |
| Electricidad | (watts / 1000) × horas × precio kWh |
| Depreciación | ($489.990 / 5.000 h) × horas |
| Mantenimiento | $50 × horas |
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

## Modo oscuro (v1.4, campos corregidos en v1.5)

Interruptor arriba a la derecha. La elección se guarda y se mantiene al reiniciar; los datos escritos no se pierden al cambiar de tema.
La primera vez sigue el modo del teléfono. Desde la v1.5 los campos tienen fondo propio por tema: oscuros con texto blanco en modo oscuro.

## Campos con valor (v1.5)

Un campo vacío tiene fondo neutro; en cuanto tiene un valor, su fondo y borde se vuelven **verdes** (legibles en ambos temas); el campo enfocado lleva borde azul.

## Mejoras de la v1.5

- **Barra fija inferior** con total, unitario, número de cálculo y hora: el total queda a la vista mientras se editan los datos.
- **Otros costos por pieza** (embalaje, envío, etiqueta): se suman al total (por piezas); la merma no se calcula sobre ellos.
- **Costo por gramo** de filamento y **costo por hora** de impresión.
- **Compartir / copiar resumen**: abre el selector de Android con el desglose en texto.
- **Ícono nuevo** basado en el logo ERRETREZ con colores vivos (ícono adaptativo; versión clásica para Android 7).

## Extras opcionales

Vacíos = no aplican; con todos vacíos el resultado es idéntico al base.

| Extra | Cálculo |
|---|---|
| Mano de obra | horas de trabajo × costo por hora (se exigen ambos datos) |
| Merma / fallos (%) | % × (material + electricidad + depreciación + mantenimiento); debe ser menor que 100 |
| Ganancia deseada (%) | ganancia por pieza = costo unitario × %; precio de venta unitario = costo unitario × (1 + %). **No cambia el costo total**: se muestra aparte, en la sección "Ganancia y precio de venta" |
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

## Ganancia, nombre del cálculo y respaldo en imagen (v1.6)

- **Ganancia aparte:** al escribir el % de ganancia sobre el costo, la sección *Ganancia y precio de venta* muestra la ganancia por pieza, la ganancia del lote, el precio de venta unitario (con y sin IVA) y el precio de venta del lote. El costo total no cambia. La barra fija inferior también muestra el precio de venta unitario.
- **Campos con valor:** el fondo verde aparece apenas se escribe un valor, también en el campo que tiene el foco (antes el campo enfocado no se veía verde).
- **Nombre del cálculo:** campo arriba de todo para indicar qué objeto es; encabeza la imagen y es parte del nombre del archivo.
- **Respaldo:** *GUARDAR IMAGEN EN LA GALERÍA* guarda un PNG con el detalle completo en `Pictures/CalculadoraCostos3D` (nombre de archivo = nombre del cálculo + fecha y hora; sin nombre se usa "calculo"). *COMPARTIR IMAGEN Y RESUMEN* guarda y abre el selector de Android; *COPIAR RESUMEN COMO TEXTO* copia el texto. En Android 9 o anterior se pide permiso de almacenamiento; desde Android 10 no.

## Hora, botones rápidos y presentación (v1.7)

- **Hora del móvil:** el estado "Cálculo n.º N actualizado a las …" usa la zona horaria y el formato (24 h o 12 h) del teléfono. La barra inferior muestra además un **reloj en vivo** ("Hora del móvil") que avanza cada segundo y la hora del último cálculo, para comparar de un vistazo.
- **Botones rápidos:** piezas (×1, ×2, ×5, ×10) y minutos (15, 30, 45) con un toque.
- **Presentación:** tarjetas redondeadas, total en grande y de color, botón CALCULAR destacado y barra inferior con sombra.
