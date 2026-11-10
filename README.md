# Calculadora de costos de impresión 3D

App Android (Kotlin, sin dependencias externas, funciona sin conexión, sin permiso de Internet)
que calcula el costo de fabricación de una impresión 3D con una Bambu Lab A1 Combo.
**Todos los campos parten vacíos** y los valores los ingresa el usuario.

## Datos que se ingresan

Obligatorios: precio del filamento por kg, gramos usados, piezas, tiempo (horas y/o minutos),
consumo de la impresora (watts) y precio de la electricidad por kWh.
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

## Consumo de la impresora (watts)

Bajo el campo se muestran dos referencias para la Bambu Lab A1 Combo, con botón **USAR** (el valor queda editable):

| Referencia | Valor | Fuente |
|---|---|---|
| Estándar (PLA) | 70–95 W (USAR = 95 W) | Wiki de Bambu Lab: promedio de 95 W en PLA a 220 V; usuarios miden 70–80 W en promedio |
| Alto consumo (ABS, alta temperatura) | ~200 W | Mediciones de usuarios en ABS (foro de Bambu Lab) |

La hoja técnica solo indica la potencia **máxima** (1.300 W a 220 V, 350 W a 110 V), que se alcanza un
rato breve mientras calienta la cama: no es el consumo promedio y por eso no se ofrece como valor.
Son valores referenciales; un enchufe medidor da el dato exacto del equipo.
Las constantes están en `ImpresoraA1` (`CostCalculator.kt`).

## Precio de electricidad por kWh

Un solo campo, con ayudas opcionales debajo (siempre editables):

- **Región:** selector de las 16 regiones y botón **DETECTAR MI REGIÓN**, que pide el permiso de ubicación solo al tocarlo y busca la región cuya capital está más cerca (aproximado, sin conexión: no usa límites regionales; la ubicación no se guarda ni se envía). Muestra un valor **referencial** del kWh. Solo hay dato para 8 regiones (Antofagasta, Atacama, Valparaíso, Metropolitana, Biobío, Araucanía, Los Lagos y Magallanes), tomado de un sitio no oficial (cuantomecuesta.com, abril 2026) y anterior al alza de julio de 2026; en las demás la app indica "usa tu boleta" en vez de inventar un valor. Las constantes están en `Regiones` (`CostCalculator.kt`).
- **Boleta:** monto total de la boleta ÷ kWh consumidos = precio efectivo del kWh (incluye cargos fijos). Es lo más fiable y no se desactualiza.
- **Mi tarifa guardada:** el botón **GUARDAR** conserva el precio del kWh y la región elegida aunque se cierre la app; **USAR** la copia al campo. Los campos siguen partiendo vacíos: lo guardado solo se aplica al tocar USAR.

La app **no** se sincroniza con internet: no existe una fuente oficial por región que una app pueda consultar de forma fiable (ver el historial de la conversación de desarrollo), y hacerlo implicaría pedir permiso de Internet.

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

El resultado se actualiza al escribir y también con el botón **CALCULAR** (que además marca los campos inválidos). **LIMPIAR** vacía todos los campos (la tarifa guardada se conserva).
El formato monetario (CLP) está aislado en `CostCalculator.formatClp`; los cálculos no dependen de ninguna moneda.

La app declara los permisos de ubicación aproximada y precisa (solo se usan con el botón DETECTAR); no declara INTERNET.

## Compilación

GitHub Actions (`.github/workflows/build-apk.yml`) en cada push a `main`:

1. Pruebas unitarias (`./gradlew testDebugUnitTest`).
2. `./gradlew assembleDebug`.
3. Verifica en el APK: paquete, `MainActivity` como launcher, `MainActivity` presente en `classes.dex`, ausencia de permiso `INTERNET` y firma.
4. Instala el APK en un emulador Android, abre la app, comprueba que los campos estén vacíos, ingresa datos y valida los resultados, incluida la detección de región con ubicación simulada y la tarifa guardada tras reiniciar la app (`scripts/emulator-smoke-test.sh`).
5. Solo si todo lo anterior pasa, publica el APK en **Releases** (tag `apk-latest`) y como artefacto del workflow.

Local: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Compatible con Android 7.0+ (minSdk 24), incluido el Redmi Note 13 5G.
Para instalar el APK hay que permitir "instalar apps de orígenes desconocidos".
