# Calculadora de costos de impresión 3D

App Android (Kotlin, sin dependencias externas, 100 % offline, sin permiso de Internet)
que calcula el costo de fabricación de una impresión 3D. **Todos los campos parten vacíos**
y los valores los ingresa el usuario.

## Fórmulas

| Componente | Fórmula |
|---|---|
| Material | (precio por kg / 1000) × gramos usados |
| Electricidad | (watts / 1000) × horas × precio kWh |
| Depreciación | (precio impresora / vida útil en horas) × horas |
| Mantenimiento | costo por hora × horas |
| **Total** | material + electricidad + depreciación + mantenimiento |
| **Unitario** | total / cantidad de piezas |

`horas` = horas + minutos / 60.

## Validaciones

- Piezas: entero mayor que 0. Vida útil: mayor que 0 (evita divisiones por cero).
- Tiempo total mayor que 0; minutos entre 0 y 59 (horas o minutos pueden quedar vacíos si el otro tiene valor).
- Resto de campos: obligatorios y no negativos (se admite `0`).
- Se rechazan textos no numéricos y resultados no finitos.
- Punto o coma como decimal; sin separador de miles.

## Valores sugeridos

Bajo tres campos se muestra un valor sugerido fijo, como referencia: precio de la impresora $450.000, vida útil 5.000 horas y mantenimiento $100 por hora. Los campos siguen partiendo vacíos; el botón **USAR** copia el sugerido al campo, donde se puede modificar. Las constantes están en `Suggested` (`CostCalculator.kt`).

## Versión 1.2

**Precio del kWh según la región.** Bajo el campo del precio del kWh hay dos ayudas, ambas opcionales y siempre editables:

- **Región:** selector de las 16 regiones y botón **DETECTAR MI REGIÓN**, que pide el permiso de ubicación solo al tocarlo y busca la región cuya capital está más cerca (aproximado, sin conexión: no usa límites regionales; la ubicación no se guarda ni se envía). Muestra un valor **referencial** del kWh. Solo hay dato para 8 regiones (Antofagasta, Atacama, Valparaíso, Metropolitana, Biobío, Araucanía, Los Lagos y Magallanes), tomado de un sitio no oficial (cuantomecuesta.com, abril 2026) y anterior al alza de julio de 2026; en las demás la app indica "usa tu boleta" en vez de inventar un valor. Las constantes están en `Regiones` (`CostCalculator.kt`).
- **Boleta:** monto total de la boleta ÷ kWh consumidos = precio efectivo del kWh (incluye cargos fijos). Es lo más fiable y no se desactualiza.

**Extras opcionales** (vacíos = no aplican; con todos vacíos el resultado es idéntico al de la v1.1):

| Extra | Cálculo |
|---|---|
| Mano de obra | horas de trabajo × costo por hora (se exigen ambos datos) |
| Merma / fallos (%) | % × (material + electricidad + depreciación + mantenimiento); debe ser menor que 100 |
| Margen de ganancia (%) | precio de venta unitario = costo unitario × (1 + margen) |
| IVA 19 % (interruptor) | precio de venta con IVA = precio de venta × 1,19 |

El costo total incluye mano de obra y merma. Se muestran además el precio de venta unitario, el precio con IVA (si está activado) y la ganancia del lote sin IVA.

La app declara los permisos de ubicación aproximada y precisa (solo se usan con el botón DETECTAR); no declara INTERNET.

El resultado se actualiza al escribir y también con el botón **CALCULAR** (que además marca los campos inválidos). **LIMPIAR** vacía todo.
El formato monetario (CLP) está aislado en `CostCalculator.formatClp`; los cálculos no dependen de ninguna moneda.

## Compilación

GitHub Actions (`.github/workflows/build-apk.yml`) en cada push a `main`:

1. Pruebas unitarias (`./gradlew testDebugUnitTest`).
2. `./gradlew assembleDebug`.
3. Verifica en el APK: paquete, `MainActivity` como launcher, `MainActivity` presente en `classes.dex`, ausencia de permiso `INTERNET` y firma.
4. Instala el APK en un emulador Android, abre la app, comprueba que los campos estén vacíos, ingresa datos y valida los resultados (`scripts/emulator-smoke-test.sh`).
5. Publica el APK en **Releases** (tag `apk-latest`) y como artefacto del workflow.

Local: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
Compatible con Android 7.0+ (minSdk 24), incluido el Redmi Note 13 5G.
Para instalar el APK hay que permitir "instalar apps de orígenes desconocidos".
