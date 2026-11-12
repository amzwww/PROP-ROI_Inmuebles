# ROI Inmuebles (Android)

App interna para valorar una compra para alquilar:

1. Localiza el inmueble en Catastro por su dirección y guarda la referencia catastral.
2. Lee los alquileres anunciados en Habitaclia para el municipio o el barrio. Si es una vivienda y no hay anuncios, usa el precio medio del municipio que publica Fotocasa.
3. Calcula rentabilidad bruta y neta, flujo de caja, escenarios y el precio máximo para una rentabilidad objetivo.

Los análisis se guardan solo en el móvil.

## Cómo está hecha

- `app/src/main/assets/index.html`: toda la interfaz y los cálculos.
- `app/src/main/java/.../MainActivity.java`: muestra esa página y le da acceso a Catastro y a un navegador oculto para leer los portales.
- `.github/workflows/build.yml`: cada cambio en `main` compila el APK y lo publica en *Releases*.

## Instalar

Descarga `ROI-Inmuebles.apk` de la última versión en *Releases* desde el móvil y ábrelo. Android pedirá permiso para instalar apps de esa fuente la primera vez.

La firma (`keystore/roi.jks`) es fija, así que cada versión nueva se instala encima de la anterior sin perder los análisis. Mantén este repositorio privado.
