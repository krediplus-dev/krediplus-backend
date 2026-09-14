# Backend Kredi+ 1.3.9 — revisión R16

## Correcciones

- Los cuerpos JSON incompletos, formatos ilegibles y argumentos inválidos devuelven una respuesta HTTP 400 en español. Las excepciones de bibliotecas no exponen detalles internos.
- La consola de alta de administradores genera el nombre de usuario requerido por el esquema actual y asigna el subrol `GENERAL` y el tipo de cuenta `OPERATIONAL`. La actualización conserva la identidad existente y rechaza convertir mediante esta consola una cuenta que pertenezca a otro rol.
- El alta por consola aplica la misma política de PIN de seis dígitos que el resto del backend, incluyendo rechazo de secuencias y PIN comunes.
- `SERVER_HOST` permite ejecutar el servidor solamente en `127.0.0.1` para instalación local. El valor predeterminado `0.0.0.0` conserva la compatibilidad con Railway.
- Las pruebas que requieren PostgreSQL ahora se marcan como omitidas si falta `TEST_DATABASE_URL`, y activar ese entorno invalida la caché de pruebas.
- La versión 1.3.9 es coherente entre Gradle, la respuesta de versión, Docker y OpenAPI.

## Validación realizada el 14 de septiembre de 2026

| Comprobación | Resultado |
| --- | --- |
| `test buildFatJar` | Compilación correcta |
| Pruebas automatizadas | 105 pruebas, 39 suites; 0 fallos, 0 errores, 0 omitidas |
| PostgreSQL 18 local, instalación vacía | Esquema aplicado correctamente hasta la migración 96 |
| Aplicación repetida del esquema | Correcta, sin errores |
| Prueba real de presupuesto | Creación de período, partida y compromiso; saldos comprobados |
| Prueba real de consola | Administrador creado y actualizado con identidad, rol y contraseña válidos |
| Inicio completo del JAR | `/health/ready` 200; versión 1.3.9, base conectada, esquema listo |
| Acceso de escritorio | Usuario y contraseña + PIN verificados para administrador y contador ficticios |
| Consultas HTTP autenticadas | Perfil, sesiones, experiencia del rol, bancos, comunidades y notificaciones: 200 |
| Solicitud JSON incompleta | 400 con mensaje controlado en español |
| Consulta de perfil sin sesión | 401 |

Las once comprobaciones HTTP del recorrido local finalizaron correctamente. Los datos, credenciales y sesiones usados fueron ficticios y se mantuvieron en un entorno PostgreSQL separado. No se utilizaron credenciales ni servicios de producción.

El JAR se compiló con Gradle 9.4.1, Kotlin 2.4.10 y destino JVM 21. También se inició y verificó con el Java 26 instalado en el equipo. En ese Java se utilizó un directorio corto para sockets con `-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp`.

Artefacto: `build/libs/krediplus-server-all.jar`.

SHA-256: `CA43C08ED0750A6C906FB18F14B3BC8967174265E324702913D0116A7A0D5B97`.

## Configuración local

Se requiere Java 21 o superior y PostgreSQL. El servidor aplica el esquema al iniciar. Variables básicas:

```text
DATABASE_URL=postgresql://USUARIO:CONTRASENA_CODIFICADA@127.0.0.1:PUERTO/BASE
PORT=18080
SERVER_HOST=127.0.0.1
PUBLIC_BASE_URL=http://localhost:18080
JWT_SECRET_FILE=RUTA_PERSISTENTE_FUERA_DE_UPLOADS
UPLOAD_DIR=RUTA_PERSISTENTE_DE_ARCHIVOS
APP_ENV=development
```

Para crear el primer contador se necesitan `BOOTSTRAP_ACCOUNTANT_EMAIL`, `BOOTSTRAP_ACCOUNTANT_PASSWORD`, `BOOTSTRAP_ACCOUNTANT_PIN` y `BOOTSTRAP_ACCOUNTANT_PHONE`. El nombre visible se puede configurar con `BOOTSTRAP_ACCOUNTANT_NAME`; el usuario de acceso con `BOOTSTRAP_ACCOUNTANT_USERNAME`. Si no se define un usuario válido, se deriva del correo. La contraseña y el PIN deben cumplir las políticas del backend antes de guardar la configuración.

La consola administrativa se ejecuta con:

```text
java -cp build/libs/krediplus-server-all.jar com.impulsosocial.server.AdminConsoleMainKt ensure
```

## Alcance y servicios externos

Las operaciones monetarias que la versión recibida ya había retirado conservan su respuesta HTTP 410: no se reactivaron carteras, transferencias ni desembolsos. El presupuesto conserva pruebas internas del servicio histórico, pero sus rutas retiradas continúan inactivas.

El adaptador bancario incluido no tiene un proveedor contratado; informa que no existe conexión y no acredita fondos ficticios. Correo de recuperación, Firebase, reCAPTCHA y tasa BCV requieren sus servicios y configuración reales. Esta validación no prueba envíos de correo, notificaciones a dispositivos, conciliación bancaria externa ni despliegue en Railway.

Los archivos de `.qa-runtime`, bases de prueba, sesiones ficticias, cachés de Gradle y credenciales no forman parte de la entrega.
