# Kredi+ Backend 1.3.9

Backend oficial de Kredi+ para Railway.

## Archivos persistentes

En producción, los comprobantes, documentos, logotipos y demás archivos cargados deben guardarse fuera del filesystem efímero del contenedor. El runtime usa:

- `UPLOAD_DIR=/data/uploads`
- volumen persistente de Railway montado en `/data`

No uses `/app`, `/workspace` ni una carpeta temporal para archivos de usuarios. El directorio `/data/uploads` se crea en la imagen, pero la persistencia real depende de que el servicio tenga un volumen Railway montado en `/data`.

## URL pública

`PUBLIC_BASE_URL` debe apuntar al origen público, por ejemplo `https://api.krediplus.org`, sin `/api/v1`. Las rutas firmadas de archivos se publican bajo `/uploads/...`.

## Modelo del Contador

El Contador se usa para conciliación, revisión de comprobantes, cuotas, incidencias, auditoría y revisión documental autorizada. Las funciones de cartera monetaria, asignación de fondos, transferencias y presupuesto monetario están retiradas de la API activa. Las rutas históricas correspondientes responden `410 Gone` para impedir que clientes antiguos ejecuten operaciones monetarias.

## Reparación automática del QR comercial (1.3.7)

Si PostgreSQL proviene de un dump anterior y todavía no tiene completa la migración 96, las rutas de ofertas y compras QR ejecutan una reparación idempotente antes de consultar datos. Se verifican `ofertas_qr_comerciales`, `items_oferta_qr`, `compras_qr_comerciales`, `productos_jornada.quantity` y la configuración de destino de ventas. Esta reparación no elimina datos existentes.

## Hotfix R15: imagen de ítems QR

Railway registró `SQLSTATE 42703` en `GET /api/v1/admin/commerce-offers`: la consulta de `commercialQrOfferDto` intentaba leer `p.image_path` desde `productos`. Esa columna no forma parte del modelo; la imagen se resuelve desde `productos_jornada.image_path`. Backend 1.3.7 corrige la consulta sin modificar los datos del catálogo.
