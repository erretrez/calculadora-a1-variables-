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
