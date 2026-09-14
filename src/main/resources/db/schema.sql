-- CAMBIOS 8: migración segura de nombres de tablas al español
DO $$
BEGIN
    IF to_regclass('public.schema_versions') IS NOT NULL AND to_regclass('public.versiones_esquema') IS NULL THEN ALTER TABLE schema_versions RENAME TO versiones_esquema; END IF;
    IF to_regclass('public.users') IS NOT NULL AND to_regclass('public.usuarios') IS NULL THEN ALTER TABLE users RENAME TO usuarios; END IF;
    IF to_regclass('public.user_profiles') IS NOT NULL AND to_regclass('public.perfiles_usuario') IS NULL THEN ALTER TABLE user_profiles RENAME TO perfiles_usuario; END IF;
    IF to_regclass('public.document_verifications') IS NOT NULL AND to_regclass('public.verificaciones_documentos') IS NULL THEN ALTER TABLE document_verifications RENAME TO verificaciones_documentos; END IF;
    IF to_regclass('public.auth_challenges') IS NOT NULL AND to_regclass('public.desafios_autenticacion') IS NULL THEN ALTER TABLE auth_challenges RENAME TO desafios_autenticacion; END IF;
    IF to_regclass('public.verification_codes') IS NOT NULL AND to_regclass('public.codigos_verificacion') IS NULL THEN ALTER TABLE verification_codes RENAME TO codigos_verificacion; END IF;
    IF to_regclass('public.user_sessions') IS NOT NULL AND to_regclass('public.sesiones_usuario') IS NULL THEN ALTER TABLE user_sessions RENAME TO sesiones_usuario; END IF;
    IF to_regclass('public.audit_logs') IS NOT NULL AND to_regclass('public.registros_auditoria') IS NULL THEN ALTER TABLE audit_logs RENAME TO registros_auditoria; END IF;
    IF to_regclass('public.user_financial_profiles') IS NOT NULL AND to_regclass('public.perfiles_financieros_usuario') IS NULL THEN ALTER TABLE user_financial_profiles RENAME TO perfiles_financieros_usuario; END IF;
    IF to_regclass('public.products') IS NOT NULL AND to_regclass('public.productos') IS NULL THEN ALTER TABLE products RENAME TO productos; END IF;
    IF to_regclass('public.fairs') IS NOT NULL AND to_regclass('public.jornadas') IS NULL THEN ALTER TABLE fairs RENAME TO jornadas; END IF;
    IF to_regclass('public.fair_payment_details') IS NOT NULL AND to_regclass('public.detalles_pago_jornada') IS NULL THEN ALTER TABLE fair_payment_details RENAME TO detalles_pago_jornada; END IF;
    IF to_regclass('public.fair_products') IS NOT NULL AND to_regclass('public.productos_jornada') IS NULL THEN ALTER TABLE fair_products RENAME TO productos_jornada; END IF;
    IF to_regclass('public.communities') IS NOT NULL AND to_regclass('public.comunidades') IS NULL THEN ALTER TABLE communities RENAME TO comunidades; END IF;
    IF to_regclass('public.combo_products') IS NOT NULL AND to_regclass('public.productos_combo') IS NULL THEN ALTER TABLE combo_products RENAME TO productos_combo; END IF;
    IF to_regclass('public.community_requests') IS NOT NULL AND to_regclass('public.solicitudes_comunidad') IS NULL THEN ALTER TABLE community_requests RENAME TO solicitudes_comunidad; END IF;
    IF to_regclass('public.community_request_items') IS NOT NULL AND to_regclass('public.items_solicitud_comunidad') IS NULL THEN ALTER TABLE community_request_items RENAME TO items_solicitud_comunidad; END IF;
    IF to_regclass('public.orders') IS NOT NULL AND to_regclass('public.pedidos') IS NULL THEN ALTER TABLE orders RENAME TO pedidos; END IF;
    IF to_regclass('public.order_items') IS NOT NULL AND to_regclass('public.items_pedido') IS NULL THEN ALTER TABLE order_items RENAME TO items_pedido; END IF;
    IF to_regclass('public.payments') IS NOT NULL AND to_regclass('public.pagos') IS NULL THEN ALTER TABLE payments RENAME TO pagos; END IF;
    IF to_regclass('public.invoices') IS NOT NULL AND to_regclass('public.facturas') IS NULL THEN ALTER TABLE invoices RENAME TO facturas; END IF;
    IF to_regclass('public.inventory_movements') IS NOT NULL AND to_regclass('public.movimientos_inventario') IS NULL THEN ALTER TABLE inventory_movements RENAME TO movimientos_inventario; END IF;
    IF to_regclass('public.device_tokens') IS NOT NULL AND to_regclass('public.tokens_dispositivo') IS NULL THEN ALTER TABLE device_tokens RENAME TO tokens_dispositivo; END IF;
    IF to_regclass('public.notifications') IS NOT NULL AND to_regclass('public.notificaciones') IS NULL THEN ALTER TABLE notifications RENAME TO notificaciones; END IF;
    IF to_regclass('public.bank_directory') IS NOT NULL AND to_regclass('public.directorio_bancos') IS NULL THEN ALTER TABLE bank_directory RENAME TO directorio_bancos; END IF;
    IF to_regclass('public.payment_verification_requests') IS NOT NULL AND to_regclass('public.solicitudes_verificacion_pago') IS NULL THEN ALTER TABLE payment_verification_requests RENAME TO solicitudes_verificacion_pago; END IF;
    IF to_regclass('public.payment_verification_decisions') IS NOT NULL AND to_regclass('public.decisiones_verificacion_pago') IS NULL THEN ALTER TABLE payment_verification_decisions RENAME TO decisiones_verificacion_pago; END IF;
    IF to_regclass('public.qr_scan_records') IS NOT NULL AND to_regclass('public.registros_escaneo_qr') IS NULL THEN ALTER TABLE qr_scan_records RENAME TO registros_escaneo_qr; END IF;
    IF to_regclass('public.order_combo_items') IS NOT NULL AND to_regclass('public.combos_pedido') IS NULL THEN ALTER TABLE order_combo_items RENAME TO combos_pedido; END IF;
    IF to_regclass('public.credit_accounts') IS NOT NULL AND to_regclass('public.cuentas_credito') IS NULL THEN ALTER TABLE credit_accounts RENAME TO cuentas_credito; END IF;
    IF to_regclass('public.credit_loans') IS NOT NULL AND to_regclass('public.prestamos_credito') IS NULL THEN ALTER TABLE credit_loans RENAME TO prestamos_credito; END IF;
    IF to_regclass('public.credit_installments') IS NOT NULL AND to_regclass('public.cuotas_credito') IS NULL THEN ALTER TABLE credit_installments RENAME TO cuotas_credito; END IF;
    IF to_regclass('public.credimpulso_users') IS NOT NULL AND to_regclass('public.usuarios_credimpulso') IS NULL THEN ALTER TABLE credimpulso_users RENAME TO usuarios_credimpulso; END IF;
    IF to_regclass('public.credimpulso_transactions') IS NOT NULL AND to_regclass('public.transacciones_credimpulso') IS NULL THEN ALTER TABLE credimpulso_transactions RENAME TO transacciones_credimpulso; END IF;
END $$;
BEGIN;

CREATE TABLE IF NOT EXISTS versiones_esquema (
    version INTEGER PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO versiones_esquema(version, description) VALUES (1, 'Esquema inicial Credicash')
ON CONFLICT (version) DO NOTHING;
INSERT INTO versiones_esquema(version, description) VALUES (2, 'Esquema v2: nombres separados y PIN de 6 digitos')
ON CONFLICT (version) DO NOTHING;

INSERT INTO versiones_esquema(version, description) VALUES (3, 'Esquema v3: roles canonicos y autorizacion consistente')
ON CONFLICT (version) DO NOTHING;
INSERT INTO versiones_esquema(version, description) VALUES (4, 'Esquema v4: tipo de empleo y verificacion Cedula RIF selfie')
ON CONFLICT (version) DO NOTHING;
INSERT INTO versiones_esquema(version, description) VALUES (10, 'Esquema v10: sesiones persistentes renovables')
ON CONFLICT (version) DO NOTHING;

CREATE TABLE IF NOT EXISTS usuarios (
    id BIGSERIAL PRIMARY KEY,
    public_id UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    password_hash TEXT NOT NULL,
    pin_hash TEXT NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'BENEFICIARY' CHECK (role IN ('BENEFICIARY','ADMIN','ACCOUNTANT','WAREHOUSE')),
    account_status VARCHAR(40) NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (account_status IN ('PENDING_VERIFICATION','ACTIVE','REJECTED','SUSPENDED','BLOCKED')),
    verification_status VARCHAR(30) NOT NULL DEFAULT 'NOT_SUBMITTED'
        CHECK (verification_status IN ('NOT_SUBMITTED','PENDING','VERIFIED','REJECTED')),
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    phone_verified BOOLEAN NOT NULL DEFAULT FALSE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON usuarios (LOWER(email));

-- Normaliza instalaciones antiguas antes de volver a aplicar la restricción de roles.
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_role_check;
UPDATE usuarios
SET role = CASE
    WHEN UPPER(TRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN') THEN 'ADMIN'
    WHEN UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') THEN 'ACCOUNTANT'
    WHEN UPPER(TRIM(role)) IN ('WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA') THEN 'WAREHOUSE'
    ELSE 'BENEFICIARY'
END
WHERE role IS DISTINCT FROM CASE
    WHEN UPPER(TRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN') THEN 'ADMIN'
    WHEN UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') THEN 'ACCOUNTANT'
    WHEN UPPER(TRIM(role)) IN ('WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA') THEN 'WAREHOUSE'
    ELSE 'BENEFICIARY'
END;
ALTER TABLE usuarios ADD CONSTRAINT users_role_check CHECK (role IN ('BENEFICIARY','ADMIN','ACCOUNTANT','WAREHOUSE'));

CREATE TABLE IF NOT EXISTS perfiles_usuario (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    full_name VARCHAR(220) NOT NULL,
    first_name VARCHAR(100),
    middle_name VARCHAR(100),
    last_name VARCHAR(100),
    second_last_name VARCHAR(100),
    phone VARCHAR(40) NOT NULL,
    birth_date DATE NOT NULL,
    gender VARCHAR(30),
    employment_type VARCHAR(30) CHECK (employment_type IS NULL OR employment_type IN ('PUBLIC_EMPLOYEE','PRIVATE_EMPLOYEE')),
    state VARCHAR(120),
    municipality VARCHAR(120),
    parish VARCHAR(120),
    community VARCHAR(180),
    address TEXT,
    shipping_address TEXT,
    selfie_path TEXT,
    profile_image_path TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE perfiles_usuario ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
ALTER TABLE perfiles_usuario ADD COLUMN IF NOT EXISTS middle_name VARCHAR(100);
ALTER TABLE perfiles_usuario ADD COLUMN IF NOT EXISTS last_name VARCHAR(100);
ALTER TABLE perfiles_usuario ADD COLUMN IF NOT EXISTS second_last_name VARCHAR(100);
ALTER TABLE perfiles_usuario ADD COLUMN IF NOT EXISTS employment_type VARCHAR(30);
ALTER TABLE perfiles_usuario DROP CONSTRAINT IF EXISTS user_profiles_employment_type_check;
ALTER TABLE perfiles_usuario ADD CONSTRAINT user_profiles_employment_type_check CHECK (employment_type IS NULL OR employment_type IN ('PUBLIC_EMPLOYEE','PRIVATE_EMPLOYEE'));
CREATE INDEX IF NOT EXISTS idx_user_profiles_phone ON perfiles_usuario (phone);

CREATE TABLE IF NOT EXISTS verificaciones_documentos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    document_type VARCHAR(40) NOT NULL CHECK (document_type IN ('NATIONAL_ID','PASSPORT','TAX_ID')),
    document_number VARCHAR(120) NOT NULL,
    front_file_path TEXT NOT NULL,
    back_file_path TEXT,
    selfie_file_path TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    rejection_reason TEXT,
    reviewed_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_document_verifications_user ON verificaciones_documentos(user_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_document_verifications_status ON verificaciones_documentos(status, submitted_at);

CREATE TABLE IF NOT EXISTS desafios_autenticacion (
    token UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    purpose VARCHAR(40) NOT NULL CHECK (purpose IN ('LOGIN_PIN','REGISTRATION_DOCUMENT')),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE desafios_autenticacion ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_auth_challenges_user ON desafios_autenticacion(user_id, purpose, expires_at);

CREATE TABLE IF NOT EXISTS codigos_verificacion (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES usuarios(id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('EMAIL','SMS')),
    purpose VARCHAR(40) NOT NULL CHECK (purpose IN ('EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET','PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE')),
    destination VARCHAR(255) NOT NULL,
    code_hash TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_verification_codes_lookup
ON codigos_verificacion(user_id, purpose, expires_at DESC);

CREATE TABLE IF NOT EXISTS sesiones_usuario (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    device_name VARCHAR(255),
    app_version VARCHAR(80),
    ip_address INET,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_active ON sesiones_usuario(user_id, revoked_at, expires_at);

CREATE TABLE IF NOT EXISTS registros_auditoria (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    action VARCHAR(160) NOT NULL,
    entity_type VARCHAR(100),
    entity_id VARCHAR(100),
    description TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_user_created ON registros_auditoria(user_id, created_at DESC);

-- Datos financieros inspirados en el modelo de manejo de usuarios suministrado.
CREATE TABLE IF NOT EXISTS perfiles_financieros_usuario (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    purchase_line NUMERIC(14,2) NOT NULL DEFAULT 0,
    user_level INTEGER NOT NULL DEFAULT 1,
    points INTEGER NOT NULL DEFAULT 0,
    points_expires_at TIMESTAMPTZ,
    risk_status VARCHAR(40) NOT NULL DEFAULT 'NOT_EVALUATED',
    last_evaluated_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS productos (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    category VARCHAR(140) NOT NULL,
    unit VARCHAR(120) NOT NULL,
    technical_details TEXT NOT NULL DEFAULT '',
    base_price NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (base_price >= 0),
    stock INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS jornadas (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(220) NOT NULL,
    place VARCHAR(300) NOT NULL,
    schedule_text VARCHAR(240) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    published BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    payment_mode VARCHAR(40) NOT NULL DEFAULT 'MOBILE_PAYMENT'
        CHECK (payment_mode IN ('MOBILE_PAYMENT','BANK_TRANSFER','BOTH')),
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_fairs_published ON jornadas(published, published_at DESC);

CREATE TABLE IF NOT EXISTS banners_inicio (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(180) NOT NULL,
    image_path TEXT,
    fair_id BIGINT REFERENCES jornadas(id) ON DELETE SET NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at >= starts_at)
);
CREATE INDEX IF NOT EXISTS idx_home_banners_active_order
    ON banners_inicio(active, sort_order, id);
CREATE INDEX IF NOT EXISTS idx_home_banners_fair
    ON banners_inicio(fair_id) WHERE fair_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS calificaciones_banner (
    id BIGSERIAL PRIMARY KEY,
    banner_id BIGINT NOT NULL REFERENCES banners_inicio(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    stars SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(banner_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_banner_ratings_banner ON calificaciones_banner(banner_id);


CREATE TABLE IF NOT EXISTS detalles_pago_jornada (
    fair_id BIGINT PRIMARY KEY REFERENCES jornadas(id) ON DELETE CASCADE,
    mobile_bank VARCHAR(160),
    mobile_phone VARCHAR(40),
    mobile_identity_number VARCHAR(80),
    mobile_holder_name VARCHAR(220),
    bank_name VARCHAR(160),
    bank_account_type VARCHAR(80),
    bank_account_number VARCHAR(120),
    bank_identity_number VARCHAR(80),
    bank_holder_name VARCHAR(220),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS productos_jornada (
    fair_id BIGINT NOT NULL REFERENCES jornadas(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES productos(id) ON DELETE RESTRICT,
    fair_price NUMERIC(14,2) NOT NULL CHECK (fair_price >= 0),
    image_path TEXT,
    PRIMARY KEY (fair_id, product_id)
);

CREATE TABLE IF NOT EXISTS comunidades (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    state VARCHAR(120),
    municipality VARCHAR(120) NOT NULL,
    parish VARCHAR(120) NOT NULL,
    families INTEGER NOT NULL DEFAULT 0 CHECK (families >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS combos (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(220) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    -- El combo tiene un precio comercial propio. No se sobrescribe el precio individual de sus productos.
    combo_price_usd NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (combo_price_usd >= 0),
    combo_price_bs NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (combo_price_bs >= 0),
    bcv_rate NUMERIC(14,4) NOT NULL DEFAULT 0 CHECK (bcv_rate >= 0),
    price_updated_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS productos_combo (
    combo_id BIGINT NOT NULL REFERENCES combos(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES productos(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    extra BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (combo_id, product_id, extra)
);

CREATE TABLE IF NOT EXISTS solicitudes_comunidad (
    id BIGSERIAL PRIMARY KEY,
    community_id BIGINT NOT NULL REFERENCES comunidades(id) ON DELETE RESTRICT,
    status VARCHAR(60) NOT NULL DEFAULT 'Solicitada',
    requested_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS items_solicitud_comunidad (
    request_id BIGINT NOT NULL REFERENCES solicitudes_comunidad(id) ON DELETE CASCADE,
    combo_id BIGINT NOT NULL REFERENCES combos(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (request_id, combo_id)
);

CREATE TABLE IF NOT EXISTS pedidos (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    fair_id BIGINT NOT NULL REFERENCES jornadas(id) ON DELETE RESTRICT,
    status VARCHAR(80) NOT NULL DEFAULT 'Pago reportado',
    item_count INTEGER NOT NULL DEFAULT 0 CHECK (item_count >= 0),
    subtotal NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (subtotal >= 0),
    total NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS calificaciones_compra (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    stars SMALLINT NOT NULL CHECK (stars BETWEEN 1 AND 5),
    tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    comment VARCHAR(600) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(order_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_purchase_ratings_order ON calificaciones_compra(order_id);

CREATE TABLE IF NOT EXISTS items_pedido (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES productos(id) ON DELETE RESTRICT,
    product_name_snapshot VARCHAR(180) NOT NULL,
    unit_snapshot VARCHAR(120) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0)
);

CREATE TABLE IF NOT EXISTS pagos (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    method VARCHAR(40) NOT NULL CHECK (method IN ('MOBILE_PAYMENT','BANK_TRANSFER')),
    origin_bank VARCHAR(160),
    reference_number VARCHAR(180) NOT NULL,
    transaction_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    amount_paid NUMERIC(14,2) NOT NULL CHECK (amount_paid >= 0),
    status VARCHAR(40) NOT NULL DEFAULT 'REPORTED' CHECK (status IN ('REPORTED','VERIFIED','REJECTED')),
    verified_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_payments_reference ON pagos(reference_number);

CREATE TABLE IF NOT EXISTS facturas (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL UNIQUE REFERENCES pedidos(id) ON DELETE CASCADE,
    invoice_number VARCHAR(80) NOT NULL UNIQUE,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS movimientos_inventario (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL REFERENCES productos(id) ON DELETE RESTRICT,
    movement_type VARCHAR(40) NOT NULL CHECK (movement_type IN ('INITIAL','ADJUSTMENT','SALE','COMMUNITY_DISPATCH','RETURN')),
    quantity_delta INTEGER NOT NULL,
    reference_type VARCHAR(80),
    reference_id VARCHAR(100),
    notes TEXT,
    performed_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_product ON movimientos_inventario(product_id, created_at DESC);


-- v4: notificaciones push, tokens de dispositivos y catálogo comunitario.
INSERT INTO versiones_esquema(version, description) VALUES (16, 'Notificaciones push, dispositivos, tasa BCV y solicitudes de registro')
ON CONFLICT (version) DO NOTHING;

ALTER TABLE comunidades ADD COLUMN IF NOT EXISTS state VARCHAR(120);
-- Índice no único: instalaciones existentes pueden contener comunidades repetidas.
-- El servicio consolida por ubicación al guardar, evitando que una migración antigua bloquee el arranque.
DROP INDEX IF EXISTS uq_communities_location_name;
CREATE INDEX IF NOT EXISTS idx_communities_location_name
ON comunidades (LOWER(name), LOWER(COALESCE(state,'')), LOWER(municipality), LOWER(parish));

CREATE TABLE IF NOT EXISTS tokens_dispositivo (
    token TEXT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    platform VARCHAR(30) NOT NULL DEFAULT 'ANDROID',
    device_name VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_device_tokens_user ON tokens_dispositivo(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS notificaciones (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    title VARCHAR(220) NOT NULL,
    body TEXT NOT NULL,
    type VARCHAR(80) NOT NULL DEFAULT 'GENERAL',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_created ON notificaciones(user_id, created_at DESC);


-- v5: Correcciones 3. Pagos trazables, bancos, QR inmutable y verificación con comprobantes.
INSERT INTO versiones_esquema(version, description) VALUES (5, 'Correcciones 3: pagos trazables, QR inmutable, bancos y comprobantes')
ON CONFLICT (version) DO NOTHING;

CREATE TABLE IF NOT EXISTS directorio_bancos (
    code VARCHAR(4) PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO directorio_bancos(code,name) VALUES
('0001','Banco Central de Venezuela'),
('0102','Banco de Venezuela'),
('0104','Venezolano de Crédito'),
('0105','Mercantil Banco'),
('0108','BBVA Provincial'),
('0114','Bancaribe'),
('0115','Banco Exterior'),
('0128','Banco Caroní'),
('0134','Banesco Banco Universal'),
('0137','Banco Sofitasa'),
('0138','Banco Plaza'),
('0146','Bangente'),
('0151','BFC Banco Fondo Común'),
('0156','100% Banco'),
('0157','DelSur Banco Universal'),
('0163','Banco del Tesoro'),
('0166','Banco Agrícola de Venezuela'),
('0168','Bancrecer'),
('0169','R4 Banco Microfinanciero'),
('0171','Banco Activo'),
('0172','Bancamiga'),
('0173','Banco Internacional de Desarrollo'),
('0174','Banplus'),
('0175','Banco Digital de los Trabajadores'),
('0177','BANFANB'),
('0178','N58 Banco Digital'),
('0191','Banco Nacional de Crédito'),
('0601','Instituto Municipal de Crédito Popular')
ON CONFLICT(code) DO UPDATE SET name=EXCLUDED.name, active=TRUE, updated_at=NOW();

ALTER TABLE pagos ADD COLUMN IF NOT EXISTS origin_bank_code VARCHAR(4);
ALTER TABLE pagos ADD COLUMN IF NOT EXISTS origin_phone VARCHAR(40);
ALTER TABLE pagos ADD COLUMN IF NOT EXISTS paid_from_different_phone BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pagos ADD COLUMN IF NOT EXISTS proof_file_path TEXT;
CREATE INDEX IF NOT EXISTS idx_payments_order_created ON pagos(order_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payments_bank_code ON pagos(origin_bank_code);

CREATE TABLE IF NOT EXISTS solicitudes_verificacion_pago (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL UNIQUE REFERENCES pagos(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES pedidos(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    origin_bank_code VARCHAR(4) NOT NULL,
    origin_bank_name_snapshot VARCHAR(180) NOT NULL,
    origin_phone VARCHAR(40) NOT NULL,
    reference_number VARCHAR(180) NOT NULL,
    proof_file_path TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_payment_verification_requests_created ON solicitudes_verificacion_pago(created_at DESC);

CREATE TABLE IF NOT EXISTS decisiones_verificacion_pago (
    id BIGSERIAL PRIMARY KEY,
    request_id BIGINT NOT NULL UNIQUE REFERENCES solicitudes_verificacion_pago(id) ON DELETE RESTRICT,
    approved BOOLEAN NOT NULL,
    notes TEXT,
    reviewed_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS registros_escaneo_qr (
    id BIGSERIAL PRIMARY KEY,
    record_type VARCHAR(80) NOT NULL,
    invoice_number VARCHAR(80) NOT NULL,
    purchase_id BIGINT,
    payload_checksum CHAR(64) NOT NULL UNIQUE,
    raw_payload JSONB NOT NULL,
    scanned_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_qr_scan_records_invoice ON registros_escaneo_qr(invoice_number, created_at DESC);

CREATE OR REPLACE FUNCTION reject_immutable_record_change()
RETURNS trigger
LANGUAGE plpgsql
AS 'BEGIN
    RAISE EXCEPTION ''Este registro es inmutable y no puede modificarse ni eliminarse.'';
END;';

DROP TRIGGER IF EXISTS trg_payment_verification_requests_immutable ON solicitudes_verificacion_pago;
CREATE TRIGGER trg_payment_verification_requests_immutable
BEFORE UPDATE OR DELETE ON solicitudes_verificacion_pago
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();

DROP TRIGGER IF EXISTS trg_payment_verification_decisions_immutable ON decisiones_verificacion_pago;
CREATE TRIGGER trg_payment_verification_decisions_immutable
BEFORE UPDATE OR DELETE ON decisiones_verificacion_pago
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();

DROP TRIGGER IF EXISTS trg_qr_scan_records_immutable ON registros_escaneo_qr;
CREATE TRIGGER trg_qr_scan_records_immutable
BEFORE UPDATE OR DELETE ON registros_escaneo_qr
FOR EACH ROW EXECUTE FUNCTION reject_immutable_record_change();

-- v6: eliminación lógica de jornadas para conservar pedidos y facturas históricas.
INSERT INTO versiones_esquema(version, description) VALUES (6, 'Eliminación lógica segura de jornadas con historial')
ON CONFLICT (version) DO NOTHING;

ALTER TABLE jornadas ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX IF NOT EXISTS idx_fairs_active_published ON jornadas(active, published, published_at DESC);

-- v7: inicialización SQL robusta sin dollar-quoting para compatibilidad de arranque.
INSERT INTO versiones_esquema(version, description) VALUES (7, 'Inicialización SQL robusta y compatible con funciones PL/pgSQL')
ON CONFLICT (version) DO NOTHING;

-- v8: Cambios 5. Las notificaciones reutilizan payload JSONB para conservar detalles
-- estructurados y localizar documentos adjuntos sin alterar registros históricos.
INSERT INTO versiones_esquema(version, description) VALUES (8, 'Cambios 5: notificaciones detalladas con adjuntos y metadatos')
ON CONFLICT (version) DO NOTHING;


-- v9: Credimpulso Nivel 1, carrito mixto de productos y combos, y crédito en 2 cuotas.
INSERT INTO versiones_esquema(version, description) VALUES (9, 'Credimpulso Nivel 1: cupo USD 60, dos cuotas y compras de productos o combos')
ON CONFLICT (version) DO NOTHING;

ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS financing_type VARCHAR(30) NOT NULL DEFAULT 'DIRECT_PAYMENT';
ALTER TABLE pedidos DROP CONSTRAINT IF EXISTS orders_financing_type_check;
ALTER TABLE pedidos ADD CONSTRAINT orders_financing_type_check CHECK (financing_type IN ('DIRECT_PAYMENT','CREDIMPULSO'));

CREATE TABLE IF NOT EXISTS combos_pedido (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES pedidos(id) ON DELETE CASCADE,
    combo_id BIGINT NOT NULL REFERENCES combos(id) ON DELETE RESTRICT,
    combo_name_snapshot VARCHAR(220) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0)
);
CREATE INDEX IF NOT EXISTS idx_order_combo_items_order ON combos_pedido(order_id);

CREATE TABLE IF NOT EXISTS cuentas_credito (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    level INTEGER NOT NULL DEFAULT 1 CHECK (level >= 1),
    credit_limit_usd NUMERIC(12,2) NOT NULL DEFAULT 60.00 CHECK (credit_limit_usd >= 0),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS prestamos_credito (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL UNIQUE REFERENCES pedidos(id) ON DELETE RESTRICT,
    level INTEGER NOT NULL DEFAULT 1,
    principal_usd NUMERIC(12,2) NOT NULL CHECK (principal_usd > 0),
    principal_bs NUMERIC(14,2) NOT NULL CHECK (principal_bs > 0),
    bcv_rate NUMERIC(14,4) NOT NULL CHECK (bcv_rate > 0),
    installment_count INTEGER NOT NULL DEFAULT 2 CHECK (installment_count = 2),
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','PAID','OVERDUE','CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_credit_loans_user_status ON prestamos_credito(user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS cuotas_credito (
    id BIGSERIAL PRIMARY KEY,
    loan_id BIGINT NOT NULL REFERENCES prestamos_credito(id) ON DELETE RESTRICT,
    installment_number INTEGER NOT NULL CHECK (installment_number IN (1,2)),
    amount_usd NUMERIC(12,2) NOT NULL CHECK (amount_usd > 0),
    original_amount_bs NUMERIC(14,2) NOT NULL CHECK (original_amount_bs > 0),
    due_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PAID','OVERDUE')),
    paid_at TIMESTAMPTZ,
    paid_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (loan_id, installment_number)
);
CREATE INDEX IF NOT EXISTS idx_credit_installments_status_due ON cuotas_credito(status, due_date);

-- El cupo es único por cuenta. Reinstalar la app o usar varios teléfonos no vuelve a acreditarlo.
INSERT INTO cuentas_credito(user_id, level, credit_limit_usd)
SELECT id, 1, 60.00
FROM usuarios
WHERE UPPER(role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
  AND verification_status='VERIFIED'
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO versiones_esquema(version, description) VALUES (11, 'Credimpulso transaccional: usuarios activos y libro mayor de movimientos')
ON CONFLICT (version) DO NOTHING;

CREATE TABLE IF NOT EXISTS usuarios_credimpulso (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    total_financed_usd NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total_financed_usd >= 0),
    total_paid_usd NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total_paid_usd >= 0),
    last_transaction_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transacciones_credimpulso (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios_credimpulso(user_id) ON DELETE RESTRICT,
    loan_id BIGINT REFERENCES prestamos_credito(id) ON DELETE RESTRICT,
    installment_id BIGINT REFERENCES cuotas_credito(id) ON DELETE RESTRICT,
    order_id BIGINT REFERENCES pedidos(id) ON DELETE RESTRICT,
    transaction_type VARCHAR(40) NOT NULL CHECK (transaction_type IN ('PURCHASE','INSTALLMENT_PAYMENT','REFUND','ADJUSTMENT')),
    amount_usd NUMERIC(14,2) NOT NULL CHECK (amount_usd >= 0),
    amount_bs NUMERIC(16,2) NOT NULL DEFAULT 0 CHECK (amount_bs >= 0),
    bcv_rate NUMERIC(16,4) NOT NULL DEFAULT 0 CHECK (bcv_rate >= 0),
    balance_before_usd NUMERIC(14,2) NOT NULL CHECK (balance_before_usd >= 0),
    balance_after_usd NUMERIC(14,2) NOT NULL CHECK (balance_after_usd >= 0),
    description TEXT NOT NULL,
    performed_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_credimpulso_transactions_user_date ON transacciones_credimpulso(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credimpulso_transactions_loan ON transacciones_credimpulso(loan_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credimpulso_transactions_created
    ON transacciones_credimpulso(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credimpulso_transactions_type_created
    ON transacciones_credimpulso(transaction_type, created_at DESC);

-- Registra en la tabla dedicada a quienes ya utilizaron Credimpulso antes de esta migración.
INSERT INTO usuarios_credimpulso(user_id,total_financed_usd,total_paid_usd,last_transaction_at)
SELECT cl.user_id,
       COALESCE(SUM(cl.principal_usd),0),
       COALESCE((SELECT SUM(ci.amount_usd) FROM cuotas_credito ci JOIN prestamos_credito cl2 ON cl2.id=ci.loan_id WHERE cl2.user_id=cl.user_id AND ci.status='PAID'),0),
       MAX(cl.updated_at)
FROM prestamos_credito cl
GROUP BY cl.user_id
ON CONFLICT(user_id) DO NOTHING;

COMMIT;


-- Descripciones de tablas para administración en pgAdmin
COMMENT ON TABLE usuarios IS 'Cuentas de acceso, rol, estado y datos principales de autenticación.';
COMMENT ON TABLE perfiles_usuario IS 'Datos personales, contacto y ubicación de cada usuario.';
COMMENT ON TABLE verificaciones_documentos IS 'Documentos de identidad cargados y su proceso de revisión.';
COMMENT ON TABLE sesiones_usuario IS 'Sesiones persistentes y tokens renovables de los dispositivos.';
COMMENT ON TABLE jornadas IS 'Jornadas de venta y distribución creadas por administración.';
COMMENT ON TABLE productos IS 'Catálogo de productos e inventario disponible.';
COMMENT ON TABLE pedidos IS 'Compras realizadas por los usuarios.';
COMMENT ON TABLE pagos IS 'Pagos reportados y datos de referencia bancaria.';
COMMENT ON TABLE facturas IS 'Facturas generadas para cada pedido.';
COMMENT ON TABLE cuentas_credito IS 'Cupo, saldo usado y disponibilidad de Credimpulso.';
COMMENT ON TABLE prestamos_credito IS 'Préstamos Credimpulso generados por compras financiadas.';
COMMENT ON TABLE cuotas_credito IS 'Cuotas programadas y estado de pago de cada préstamo.';
COMMENT ON TABLE usuarios_credimpulso IS 'Personas que han utilizado Credimpulso y sus acumulados.';
COMMENT ON TABLE transacciones_credimpulso IS 'Libro de movimientos de crédito, pagos y saldos Credimpulso.';
COMMENT ON TABLE registros_auditoria IS 'Historial técnico y administrativo de acciones relevantes.';


-- Solicitudes reales de ampliación de cupo Credimpulso.
CREATE TABLE IF NOT EXISTS solicitudes_credito (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    requested_amount_usd NUMERIC(12,2) NOT NULL CHECK (requested_amount_usd > 0),
    requested_installments INTEGER NOT NULL CHECK (requested_installments BETWEEN 2 AND 12),
    purpose VARCHAR(250) NOT NULL DEFAULT 'Compra de alimentos y productos',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    reviewed_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_solicitudes_credito_estado ON solicitudes_credito(status, created_at DESC);
COMMENT ON TABLE solicitudes_credito IS 'Solicitudes de aumento de cupo Credimpulso realizadas por los usuarios y decididas por administradores.';


-- Corrección segura de cuotas flexibles heredadas de versiones anteriores.
-- IMPORTANTE: esta migración no elimina préstamos ni cuotas existentes.
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS credit_loans_installment_count_check;
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_installment_count_check;
ALTER TABLE cuotas_credito DROP CONSTRAINT IF EXISTS credit_installments_installment_number_check;
ALTER TABLE cuotas_credito DROP CONSTRAINT IF EXISTS cuotas_credito_installment_number_check;

-- Quitar temporalmente la unicidad para renumerar sin colisiones.
ALTER TABLE cuotas_credito DROP CONSTRAINT IF EXISTS credit_installments_loan_id_installment_number_key;
ALTER TABLE cuotas_credito DROP CONSTRAINT IF EXISTS cuotas_credito_loan_id_installment_number_key;
DO $impulso_unique_cuotas$
DECLARE
    nombre_restriccion text;
BEGIN
    FOR nombre_restriccion IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.conrelid='cuotas_credito'::regclass
          AND con.contype='u'
          AND pg_get_constraintdef(con.oid)
              ~* 'UNIQUE\\s*\\(\\s*loan_id\\s*,\\s*installment_number\\s*\\)'
    LOOP
        EXECUTE format(
            'ALTER TABLE cuotas_credito DROP CONSTRAINT %I',
            nombre_restriccion
        );
    END LOOP;
END
$impulso_unique_cuotas$;

-- Normaliza la numeración de cuotas por préstamo: 1, 2, 3...
WITH cuotas_ordenadas AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY loan_id
               ORDER BY due_date ASC NULLS LAST, id ASC
           )::integer AS numero_nuevo
    FROM cuotas_credito
)
UPDATE cuotas_credito AS cuota
SET installment_number = orden.numero_nuevo
FROM cuotas_ordenadas AS orden
WHERE cuota.id = orden.id
  AND cuota.installment_number IS DISTINCT FROM orden.numero_nuevo;

-- Sincroniza installment_count solamente cuando el préstamo tiene un total funcional de 2 a 12 cuotas.
WITH conteo AS (
    SELECT loan_id, COUNT(*)::integer AS total
    FROM cuotas_credito
    GROUP BY loan_id
)
UPDATE prestamos_credito AS prestamo
SET installment_count = conteo.total
FROM conteo
WHERE prestamo.id = conteo.loan_id
  AND conteo.total BETWEEN 2 AND 12
  AND prestamo.installment_count IS DISTINCT FROM conteo.total;

ALTER TABLE cuotas_credito
    ADD CONSTRAINT cuotas_credito_loan_id_installment_number_key
    UNIQUE (loan_id, installment_number);

-- Restricciones de compatibilidad para datos heredados.
-- Los créditos nuevos siguen siendo limitados por la lógica de negocio a los valores configurados.
ALTER TABLE prestamos_credito
    ADD CONSTRAINT prestamos_credito_installment_count_check
    CHECK (installment_count >= 1)
    NOT VALID;

ALTER TABLE cuotas_credito
    ADD CONSTRAINT cuotas_credito_installment_number_check
    CHECK (installment_number >= 1)
    NOT VALID;


INSERT INTO versiones_esquema(version, description) VALUES (12, 'Cambios 11: base persistente unica, administradores por consola y contrato API completo')
ON CONFLICT(version) DO NOTHING;

-- v13: seguridad de correo, recuperación de contraseña y reCAPTCHA.
-- Las cuentas que ya estaban activas/verificadas antes de esta versión se consideran correo verificado
-- para no bloquear a usuarios históricos que se registraron antes de habilitar este control.
UPDATE usuarios
SET email_verified=TRUE, updated_at=NOW()
WHERE email_verified=FALSE
  AND (verification_status='VERIFIED' OR account_status='ACTIVE');

ALTER TABLE codigos_verificacion DROP CONSTRAINT IF EXISTS verification_codes_purpose_check;
ALTER TABLE codigos_verificacion DROP CONSTRAINT IF EXISTS codigos_verificacion_purpose_check;

-- Compatibilidad con códigos históricos: las versiones posteriores añadieron
-- ACCOUNT_VERIFICATION. Al reiniciar una base existente, esta migración histórica
-- no debe rechazar filas creadas por versiones más nuevas.
UPDATE codigos_verificacion
SET purpose = CASE
        WHEN UPPER(BTRIM(purpose)) IN (
            'EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET',
            'PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE'
        ) THEN UPPER(BTRIM(purpose))
        WHEN UPPER(BTRIM(purpose)) IN ('VERIFY_EMAIL','EMAIL_VERIFY') THEN 'EMAIL_VERIFICATION'
        WHEN UPPER(BTRIM(purpose)) IN ('VERIFY_ACCOUNT','ACCOUNT_VERIFY','REGISTRATION','REGISTER') THEN 'ACCOUNT_VERIFICATION'
        WHEN UPPER(BTRIM(purpose)) IN ('RESET_PASSWORD','PASSWORD_RECOVERY') THEN 'PASSWORD_RESET'
        WHEN UPPER(BTRIM(purpose)) IN ('VERIFY_PHONE','PHONE_VERIFY') THEN 'PHONE_VERIFICATION'
        WHEN UPPER(BTRIM(purpose)) IN ('LOGIN_PIN','SIGN_IN') THEN 'LOGIN'
        ELSE 'CRITICAL_ACTION'
    END,
    consumed_at = CASE
        WHEN UPPER(BTRIM(purpose)) NOT IN (
            'EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET',
            'PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE',
            'VERIFY_EMAIL','EMAIL_VERIFY','VERIFY_ACCOUNT','ACCOUNT_VERIFY',
            'REGISTRATION','REGISTER','RESET_PASSWORD','PASSWORD_RECOVERY',
            'VERIFY_PHONE','PHONE_VERIFY','LOGIN_PIN','SIGN_IN'
        ) THEN COALESCE(consumed_at,NOW())
        ELSE consumed_at
    END;

ALTER TABLE codigos_verificacion
    ADD CONSTRAINT codigos_verificacion_purpose_check
    CHECK (purpose IN (
        'EMAIL_VERIFICATION',
        'ACCOUNT_VERIFICATION',
        'PASSWORD_RESET',
        'PHONE_VERIFICATION',
        'LOGIN',
        'CRITICAL_ACTION',
        'EMAIL_CHANGE'
    ));
CREATE INDEX IF NOT EXISTS idx_codigos_verificacion_destino_proposito
    ON codigos_verificacion(LOWER(destination), purpose, created_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (13, 'Seguridad v4.1.0: verificacion de correo, recuperacion de contraseña y reCAPTCHA')
ON CONFLICT(version) DO NOTHING;

-- v14: niveles Credimpulso configurables 1 a 6.
CREATE TABLE IF NOT EXISTS nivel_credimpulso_1 (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id=1),
    nombre VARCHAR(80) NOT NULL DEFAULT 'Santa Ana',
    pagos_completados_requeridos INTEGER NOT NULL DEFAULT 0 CHECK (pagos_completados_requeridos >= 0),
    multiplicador_cupo INTEGER NOT NULL DEFAULT 1 CHECK (multiplicador_cupo >= 1),
    porcentaje_inicial NUMERIC(5,2) NOT NULL DEFAULT 20.00 CHECK (porcentaje_inicial BETWEEN 0 AND 100),
    monto_base_usd NUMERIC(12,2) NOT NULL DEFAULT 60.00 CHECK (monto_base_usd > 0),
    max_cuotas INTEGER NOT NULL DEFAULT 2 CHECK (max_cuotas BETWEEN 2 AND 6),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS nivel_credimpulso_2 (LIKE nivel_credimpulso_1 INCLUDING ALL);
CREATE TABLE IF NOT EXISTS nivel_credimpulso_3 (LIKE nivel_credimpulso_1 INCLUDING ALL);
CREATE TABLE IF NOT EXISTS nivel_credimpulso_4 (LIKE nivel_credimpulso_1 INCLUDING ALL);
CREATE TABLE IF NOT EXISTS nivel_credimpulso_5 (LIKE nivel_credimpulso_1 INCLUDING ALL);
CREATE TABLE IF NOT EXISTS nivel_credimpulso_6 (LIKE nivel_credimpulso_1 INCLUDING ALL);

INSERT INTO nivel_credimpulso_1(id,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd)
VALUES (1,'Santa Ana',0,1,20.00,60.00) ON CONFLICT(id) DO NOTHING;
INSERT INTO nivel_credimpulso_2(id,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd)
VALUES (1,'El Ávila',3,2,16.00,60.00) ON CONFLICT(id) DO NOTHING;
INSERT INTO nivel_credimpulso_3(id,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd)
VALUES (1,'Autana',6,3,12.00,60.00) ON CONFLICT(id) DO NOTHING;
INSERT INTO nivel_credimpulso_4(id,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd)
VALUES (1,'Auyantepuy',12,4,8.00,60.00) ON CONFLICT(id) DO NOTHING;
INSERT INTO nivel_credimpulso_5(id,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd)
VALUES (1,'Pico Bolívar',20,5,4.00,60.00) ON CONFLICT(id) DO NOTHING;
INSERT INTO nivel_credimpulso_6(id,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd)
VALUES (1,'Salto Ángel',30,6,0.00,60.00) ON CONFLICT(id) DO NOTHING;

ALTER TABLE nivel_credimpulso_1 ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 2 CHECK (max_cuotas BETWEEN 2 AND 6);
ALTER TABLE nivel_credimpulso_2 ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 2 CHECK (max_cuotas BETWEEN 2 AND 6);
ALTER TABLE nivel_credimpulso_3 ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 3 CHECK (max_cuotas BETWEEN 2 AND 6);
ALTER TABLE nivel_credimpulso_4 ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 4 CHECK (max_cuotas BETWEEN 2 AND 6);
ALTER TABLE nivel_credimpulso_5 ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 5 CHECK (max_cuotas BETWEEN 2 AND 6);
ALTER TABLE nivel_credimpulso_6 ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 6 CHECK (max_cuotas BETWEEN 2 AND 6);

DROP VIEW IF EXISTS reglas_niveles_credimpulso;
CREATE VIEW reglas_niveles_credimpulso AS
SELECT 1 AS level,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_1
UNION ALL
SELECT 2,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_2
UNION ALL
SELECT 3,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_3
UNION ALL
SELECT 4,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_4
UNION ALL
SELECT 5,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_5
UNION ALL
SELECT 6,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_6;

COMMENT ON VIEW reglas_niveles_credimpulso IS 'Vista unificada de las seis reglas configurables de niveles Credimpulso.';

INSERT INTO versiones_esquema(version, description)
VALUES (14, 'Credimpulso v4.1.0: seis niveles configurables y progresion automatica')
ON CONFLICT(version) DO NOTHING;


INSERT INTO versiones_esquema(version, description)
VALUES (15, 'Healthcheck Railway y migraciones heredadas tolerantes a fallos')
ON CONFLICT(version) DO NOTHING;


-- CAMBIOS 18 ---------------------------------------------------------------
-- Jornadas finalizadas sin perder historial.
ALTER TABLE jornadas
    ADD COLUMN IF NOT EXISTS finalized BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_jornadas_finalized
    ON jornadas(finalized, active, published);

-- Cuenta bancaria donde el usuario desea recibir créditos aprobados.
CREATE TABLE IF NOT EXISTS cuentas_desembolso_credito (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    bank_code VARCHAR(20) NOT NULL,
    bank_name VARCHAR(180) NOT NULL,
    account_type VARCHAR(50) NOT NULL,
    account_number VARCHAR(40) NOT NULL,
    holder_name VARCHAR(220) NOT NULL,
    identity_number VARCHAR(40) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO versiones_esquema(version, description)
VALUES (18, 'Cambios 18: jornadas finalizadas, banco de desembolso y reglas de préstamos por nivel')
ON CONFLICT(version) DO NOTHING;


-- CAMBIOS 19 ---------------------------------------------------------------
-- Cartera digital Credimpulso por administrador.
CREATE TABLE IF NOT EXISTS carteras_credimpulso_admin (
    admin_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE RESTRICT,
    saldo_disponible_usd NUMERIC(14,2) NOT NULL DEFAULT 0.00 CHECK (saldo_disponible_usd >= 0),
    total_transferido_usd NUMERIC(14,2) NOT NULL DEFAULT 0.00 CHECK (total_transferido_usd >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS movimientos_cartera_credimpulso (
    id BIGSERIAL PRIMARY KEY,
    admin_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    tipo VARCHAR(30) NOT NULL CHECK (tipo IN ('ABONO','TRANSFERENCIA','AJUSTE')),
    monto_usd NUMERIC(14,2) NOT NULL CHECK (monto_usd > 0),
    saldo_antes_usd NUMERIC(14,2) NOT NULL CHECK (saldo_antes_usd >= 0),
    saldo_despues_usd NUMERIC(14,2) NOT NULL CHECK (saldo_despues_usd >= 0),
    referencia VARCHAR(120),
    descripcion VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_movimientos_cartera_admin_fecha
    ON movimientos_cartera_credimpulso(admin_id, created_at DESC);

INSERT INTO carteras_credimpulso_admin(admin_id)
SELECT id FROM usuarios
WHERE UPPER(role) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
ON CONFLICT(admin_id) DO NOTHING;

INSERT INTO versiones_esquema(version, description)
VALUES (19, 'Cambios 19: cartera digital Credimpulso por administrador, movimientos y transferencias')
ON CONFLICT(version) DO NOTHING;


-- v20: reparación defensiva de Credimpulso para instalaciones heredadas.
-- Garantiza que todas las cuentas naturales activas/verificadas tengan una cuenta de crédito.
INSERT INTO cuentas_credito(user_id,level,credit_limit_usd,status)
SELECT u.id,1,60.00,'ACTIVE'
FROM usuarios u
WHERE UPPER(u.role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
  AND (u.verification_status='VERIFIED' OR u.account_status='ACTIVE')
ON CONFLICT(user_id) DO NOTHING;

-- El nivel debe poder evolucionar del 1 al 6.
ALTER TABLE cuentas_credito DROP CONSTRAINT IF EXISTS cuentas_credito_level_check;
ALTER TABLE cuentas_credito
    ADD CONSTRAINT cuentas_credito_level_check
    CHECK (level BETWEEN 1 AND 6)
    NOT VALID;

INSERT INTO versiones_esquema(version, description)
VALUES (20, 'Correccion robusta de actualizacion de niveles Credimpulso')
ON CONFLICT(version) DO NOTHING;


-- CAMBIOS 20: transferencias internas desde la cartera del administrador.
ALTER TABLE transacciones_credimpulso
    DROP CONSTRAINT IF EXISTS transacciones_credimpulso_transaction_type_check;
ALTER TABLE transacciones_credimpulso
    DROP CONSTRAINT IF EXISTS credimpulso_transactions_transaction_type_check;

ALTER TABLE transacciones_credimpulso
    ADD CONSTRAINT transacciones_credimpulso_transaction_type_check
    CHECK (transaction_type IN (
        'PURCHASE',
        'INSTALLMENT_PAYMENT',
        'REFUND',
        'ADJUSTMENT',
        'CREDIT_TRANSFER'
    ))
    NOT VALID;

INSERT INTO versiones_esquema(version, description)
VALUES (21, 'Cambios 20: transferencia real de saldo entre cartera administrativa y cartera Credimpulso del usuario')
ON CONFLICT(version) DO NOTHING;

-- CAMBIOS 23 ---------------------------------------------------------------
-- Control global del presupuesto de cartera por cumplimiento de cobro.
-- Al evaluar 3 cuotas exigibles de 3 usuarios distintos, deben existir al menos
-- 2 cuotas aprobadas/pagadas; en caso contrario el saldo queda retenido.
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS saldo_bloqueado BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS motivo_bloqueo VARCHAR(500);
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS cuotas_evaluadas INTEGER NOT NULL DEFAULT 0 CHECK (cuotas_evaluadas >= 0);
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS cuotas_aprobadas INTEGER NOT NULL DEFAULT 0 CHECK (cuotas_aprobadas >= 0);
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS usuarios_evaluados INTEGER NOT NULL DEFAULT 0 CHECK (usuarios_evaluados >= 0);
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS bloqueado_desde TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_movimientos_cartera_admin_usuario_tipo
    ON movimientos_cartera_credimpulso(admin_id,user_id,tipo);
CREATE INDEX IF NOT EXISTS idx_cuotas_credito_exigibles
    ON cuotas_credito(due_date,status,loan_id);

INSERT INTO versiones_esquema(version, description)
VALUES (23, 'Cambios 23: retención global de saldo por regla de 2 cuotas aprobadas de 3 usuarios diferentes')
ON CONFLICT(version) DO NOTHING;



-- CAMBIOS 24 ---------------------------------------------------------------
-- Rol Contador y cartera presupuestaria central preparada para futura banca abierta.
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_role_check;
UPDATE usuarios
SET role = CASE
    WHEN UPPER(TRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN') THEN 'ADMIN'
    WHEN UPPER(TRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') THEN 'ACCOUNTANT'
    WHEN UPPER(TRIM(role)) IN ('WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA') THEN 'WAREHOUSE'
    ELSE 'BENEFICIARY'
END;
ALTER TABLE usuarios ADD CONSTRAINT users_role_check
    CHECK (role IN ('BENEFICIARY','ADMIN','ACCOUNTANT','WAREHOUSE'));

CREATE TABLE IF NOT EXISTS contadores (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    codigo_contador VARCHAR(40) NOT NULL UNIQUE DEFAULT ('CONT-' || UPPER(SUBSTRING(gen_random_uuid()::TEXT,1,8))),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    designado_por BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    designado_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS carteras_presupuesto_contador (
    contador_id BIGINT PRIMARY KEY REFERENCES contadores(user_id) ON DELETE CASCADE,
    presupuesto_inicial_usd NUMERIC(18,2) NOT NULL DEFAULT 1000000.00 CHECK (presupuesto_inicial_usd >= 0),
    saldo_disponible_usd NUMERIC(18,2) NOT NULL DEFAULT 1000000.00 CHECK (saldo_disponible_usd >= 0),
    total_asignado_usd NUMERIC(18,2) NOT NULL DEFAULT 0.00 CHECK (total_asignado_usd >= 0),
    fuente_fondos VARCHAR(40) NOT NULL DEFAULT 'INITIAL_OPERATING_BUDGET',
    proveedor_bancario VARCHAR(80),
    cuenta_bancaria_externa_id VARCHAR(180),
    estado_integracion_bancaria VARCHAR(40) NOT NULL DEFAULT 'READY_FOR_BANK_API'
        CHECK (estado_integracion_bancaria IN ('READY_FOR_BANK_API','CONNECTED','SYNCING','ERROR','DISABLED')),
    metadata_integracion JSONB NOT NULL DEFAULT '{}'::JSONB,
    ultima_sincronizacion_bancaria_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS asignaciones_presupuesto_admin (
    id BIGSERIAL PRIMARY KEY,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    admin_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd > 0),
    tasa_bcv NUMERIC(18,6) NOT NULL CHECK (tasa_bcv > 0),
    monto_bs NUMERIC(20,2) NOT NULL CHECK (monto_bs >= 0),
    saldo_contador_antes_usd NUMERIC(18,2) NOT NULL CHECK (saldo_contador_antes_usd >= 0),
    saldo_contador_despues_usd NUMERIC(18,2) NOT NULL CHECK (saldo_contador_despues_usd >= 0),
    referencia VARCHAR(120) NOT NULL UNIQUE,
    descripcion VARCHAR(500),
    fuente_fondos VARCHAR(40) NOT NULL DEFAULT 'ACCOUNTANT_WALLET',
    transaccion_bancaria_externa_id VARCHAR(180),
    estado VARCHAR(30) NOT NULL DEFAULT 'COMPLETED'
        CHECK (estado IN ('PENDING','COMPLETED','REVERSED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_asignaciones_contador_fecha
    ON asignaciones_presupuesto_admin(contador_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_asignaciones_admin_fecha
    ON asignaciones_presupuesto_admin(admin_id, created_at DESC);

CREATE TABLE IF NOT EXISTS movimientos_cartera_contador (
    id BIGSERIAL PRIMARY KEY,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    admin_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    tipo VARCHAR(40) NOT NULL CHECK (tipo IN ('SALDO_INICIAL','ASIGNACION_ADMIN','AJUSTE','ABONO_BANCARIO','REVERSO')),
    monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd >= 0),
    tasa_bcv NUMERIC(18,6),
    monto_bs NUMERIC(20,2),
    saldo_antes_usd NUMERIC(18,2) NOT NULL CHECK (saldo_antes_usd >= 0),
    saldo_despues_usd NUMERIC(18,2) NOT NULL CHECK (saldo_despues_usd >= 0),
    referencia VARCHAR(120),
    descripcion VARCHAR(500),
    proveedor_bancario VARCHAR(80),
    transaccion_bancaria_externa_id VARCHAR(180),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_movimientos_contador_fecha
    ON movimientos_cartera_contador(contador_id, created_at DESC);

INSERT INTO contadores(user_id)
SELECT id FROM usuarios WHERE UPPER(role) IN ('ACCOUNTANT','CONTADOR','CONTADORA')
ON CONFLICT(user_id) DO NOTHING;

INSERT INTO carteras_presupuesto_contador(contador_id)
SELECT user_id FROM contadores
ON CONFLICT(contador_id) DO NOTHING;

INSERT INTO movimientos_cartera_contador(
    contador_id,tipo,monto_usd,saldo_antes_usd,saldo_despues_usd,referencia,descripcion
)
SELECT c.contador_id,'SALDO_INICIAL',c.presupuesto_inicial_usd,0,c.saldo_disponible_usd,
       'INITIAL-' || c.contador_id,
       'Presupuesto inicial de ejemplo de Cambios 24'
FROM carteras_presupuesto_contador c
WHERE NOT EXISTS (
    SELECT 1 FROM movimientos_cartera_contador m
    WHERE m.contador_id=c.contador_id AND m.tipo='SALDO_INICIAL'
);

-- Los fondos de administradores provienen del contador; se amplía el catálogo de movimientos.
-- Se eliminan dinámicamente todos los CHECK históricos relacionados con la columna tipo,
-- porque instalaciones antiguas usaron nombres de restricción diferentes.
DO $wallet_movement_checks$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid=c.conrelid
        WHERE t.relname='movimientos_cartera_credimpulso'
          AND c.contype='c'
          AND pg_get_constraintdef(c.oid) ILIKE '%tipo%'
    LOOP
        EXECUTE format(
            'ALTER TABLE movimientos_cartera_credimpulso DROP CONSTRAINT IF EXISTS %I',
            constraint_name
        );
    END LOOP;
END
$wallet_movement_checks$;

ALTER TABLE movimientos_cartera_credimpulso
    ADD CONSTRAINT movimientos_cartera_credimpulso_tipo_check
    CHECK (tipo IN ('ABONO','TRANSFERENCIA','AJUSTE','ASIGNACION_CONTADOR','REVERSO'));

INSERT INTO versiones_esquema(version, description)
VALUES (24, 'Cambios 24: rol contador, presupuesto central de US$ 1.000.000, BCV y futura integración bancaria')
ON CONFLICT(version) DO NOTHING;

-- CAMBIOS 25 ---------------------------------------------------------------
-- Transferencias internas resueltas por correo y reparación independiente
-- de las credenciales/infraestructura del Contador configurado en Railway.
INSERT INTO versiones_esquema(version, description)
VALUES (25, 'Cambios 25: transferencias por correo, mensaje de registro y acceso robusto del contador')
ON CONFLICT(version) DO NOTHING;

-- CAMBIOS 26 ---------------------------------------------------------------
-- Credimpulso: cuotas por nivel, historial crediticio, suspensión automática
-- y auditoría de desembolsos aprobados con equivalencia BCV.
DO $c26_level_columns$
DECLARE
    tabla TEXT;
BEGIN
    FOREACH tabla IN ARRAY ARRAY[
        'nivel_credimpulso_1','nivel_credimpulso_2','nivel_credimpulso_3',
        'nivel_credimpulso_4','nivel_credimpulso_5','nivel_credimpulso_6'
    ] LOOP
        EXECUTE format(
            'ALTER TABLE %I ADD COLUMN IF NOT EXISTS max_cuotas INTEGER NOT NULL DEFAULT 2 CHECK (max_cuotas BETWEEN 2 AND 6)',
            tabla
        );
    END LOOP;
END
$c26_level_columns$;

UPDATE nivel_credimpulso_1 SET max_cuotas=2 WHERE id=1;
UPDATE nivel_credimpulso_2 SET max_cuotas=2 WHERE id=1;
UPDATE nivel_credimpulso_3 SET max_cuotas=3 WHERE id=1;
UPDATE nivel_credimpulso_4 SET max_cuotas=4 WHERE id=1;
UPDATE nivel_credimpulso_5 SET max_cuotas=5 WHERE id=1;
UPDATE nivel_credimpulso_6 SET max_cuotas=6 WHERE id=1;

DROP VIEW IF EXISTS reglas_niveles_credimpulso;
CREATE VIEW reglas_niveles_credimpulso AS
SELECT 1 AS level,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_1
UNION ALL
SELECT 2,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_2
UNION ALL
SELECT 3,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_3
UNION ALL
SELECT 4,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_4
UNION ALL
SELECT 5,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_5
UNION ALL
SELECT 6,nombre,pagos_completados_requeridos,multiplicador_cupo,porcentaje_inicial,monto_base_usd,max_cuotas FROM nivel_credimpulso_6;

ALTER TABLE cuentas_credito
    ADD COLUMN IF NOT EXISTS preferred_installments INTEGER NOT NULL DEFAULT 2;
ALTER TABLE cuentas_credito DROP CONSTRAINT IF EXISTS cuentas_credito_preferred_installments_check;
ALTER TABLE cuentas_credito ADD CONSTRAINT cuentas_credito_preferred_installments_check
    CHECK (preferred_installments BETWEEN 2 AND 6);

ALTER TABLE solicitudes_credito
    ADD COLUMN IF NOT EXISTS approval_bcv_rate NUMERIC(18,6),
    ADD COLUMN IF NOT EXISTS approved_amount_bs NUMERIC(20,2),
    ADD COLUMN IF NOT EXISTS wallet_reference VARCHAR(120),
    ADD COLUMN IF NOT EXISTS disbursed_at TIMESTAMPTZ;
ALTER TABLE solicitudes_credito DROP CONSTRAINT IF EXISTS solicitudes_credito_requested_installments_check;
ALTER TABLE solicitudes_credito ADD CONSTRAINT solicitudes_credito_requested_installments_check
    CHECK (requested_installments BETWEEN 2 AND 6) NOT VALID;

CREATE TABLE IF NOT EXISTS historial_crediticio_usuarios (
    user_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    porcentaje INTEGER NOT NULL DEFAULT 100 CHECK (porcentaje BETWEEN 0 AND 100),
    pagos_atrasados INTEGER NOT NULL DEFAULT 0 CHECK (pagos_atrasados >= 0),
    pagos_a_tiempo INTEGER NOT NULL DEFAULT 0 CHECK (pagos_a_tiempo >= 0),
    estado VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'
        CHECK (estado IN ('ACTIVE','SUSPENDED','CLOSED')),
    suspendido_at TIMESTAMPTZ,
    motivo_suspension VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS eventos_historial_crediticio (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    loan_id BIGINT REFERENCES prestamos_credito(id) ON DELETE SET NULL,
    installment_id BIGINT REFERENCES cuotas_credito(id) ON DELETE SET NULL,
    order_id BIGINT REFERENCES pedidos(id) ON DELETE SET NULL,
    invoice_number VARCHAR(120) NOT NULL DEFAULT '',
    event_type VARCHAR(40) NOT NULL
        CHECK (event_type IN ('LATE_PAYMENT','ON_TIME_PAYMENT','CREDIT_SUSPENDED','ADMIN_RESTORED','LEVEL_DOWNGRADED')),
    score_before INTEGER NOT NULL CHECK (score_before BETWEEN 0 AND 100),
    score_after INTEGER NOT NULL CHECK (score_after BETWEEN 0 AND 100),
    due_date DATE,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    details VARCHAR(700)
);
CREATE INDEX IF NOT EXISTS idx_historial_crediticio_score
    ON historial_crediticio_usuarios(estado, porcentaje, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_eventos_historial_usuario_fecha
    ON eventos_historial_crediticio(user_id, occurred_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_evento_historial_cuota_tipo
    ON eventos_historial_crediticio(installment_id, event_type)
    WHERE installment_id IS NOT NULL;
ALTER TABLE eventos_historial_crediticio DROP CONSTRAINT IF EXISTS eventos_historial_crediticio_event_type_check;
ALTER TABLE eventos_historial_crediticio ADD CONSTRAINT eventos_historial_crediticio_event_type_check
    CHECK (event_type IN ('LATE_PAYMENT','ON_TIME_PAYMENT','CREDIT_SUSPENDED','ADMIN_RESTORED','LEVEL_DOWNGRADED')) NOT VALID;

INSERT INTO historial_crediticio_usuarios(user_id)
SELECT id FROM usuarios
WHERE UPPER(role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
ON CONFLICT(user_id) DO NOTHING;

ALTER TABLE transacciones_credimpulso DROP CONSTRAINT IF EXISTS transacciones_credimpulso_transaction_type_check;
ALTER TABLE transacciones_credimpulso DROP CONSTRAINT IF EXISTS credit_transactions_transaction_type_check;
ALTER TABLE transacciones_credimpulso DROP CONSTRAINT IF EXISTS credimpulso_transactions_transaction_type_check;
ALTER TABLE transacciones_credimpulso ADD CONSTRAINT transacciones_credimpulso_transaction_type_check
    CHECK (transaction_type IN ('PURCHASE','INSTALLMENT_PAYMENT','REFUND','ADJUSTMENT','CREDIT_TRANSFER','CREDIT_REQUEST_APPROVAL'));

COMMENT ON TABLE historial_crediticio_usuarios IS 'Puntaje y estado crediticio consolidado por usuario. Dos atrasos suspenden Credimpulso.';
COMMENT ON TABLE eventos_historial_crediticio IS 'Auditoría por fecha, cuota y factura de cada variación del historial crediticio.';

INSERT INTO versiones_esquema(version, description)
VALUES (26, 'Cambios 26: solicitud visible, cuotas por nivel, historial crediticio, suspension automatica y exportacion')
ON CONFLICT(version) DO NOTHING;

-- CAMBIOS 27 ---------------------------------------------------------------
-- Identificadores únicos de cartera, reparación de datos heredados, facturas QR
-- e inventario de demanda. Esta migración es idempotente y no elimina datos.
ALTER TABLE cuentas_credito
    ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(80);
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(80);
ALTER TABLE carteras_presupuesto_contador
    ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(80);

UPDATE cuentas_credito
SET wallet_address = 'ISU-' || UPPER(SUBSTRING(MD5('USER:' || user_id::TEXT), 1, 32))
WHERE wallet_address IS NULL OR BTRIM(wallet_address)='';
UPDATE carteras_credimpulso_admin
SET wallet_address = 'ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || admin_id::TEXT), 1, 32))
WHERE wallet_address IS NULL OR BTRIM(wallet_address)='';
UPDATE carteras_presupuesto_contador
SET wallet_address = 'ISC-' || UPPER(SUBSTRING(MD5('ACCOUNTANT:' || contador_id::TEXT), 1, 32))
WHERE wallet_address IS NULL OR BTRIM(wallet_address)='';

-- Crea y repara las carteras de todos los administradores históricos.
INSERT INTO carteras_credimpulso_admin(admin_id,saldo_disponible_usd,total_transferido_usd,wallet_address)
SELECT u.id,0,0,'ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || u.id::TEXT), 1, 32))
FROM usuarios u
WHERE UPPER(TRIM(u.role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
ON CONFLICT(admin_id) DO UPDATE
SET wallet_address=COALESCE(NULLIF(carteras_credimpulso_admin.wallet_address,''),EXCLUDED.wallet_address);

-- La restricción se corrige una sola vez durante el despliegue, no en cada consulta.
DO $c27_wallet_movement_constraint$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid=c.conrelid
        WHERE t.relname='movimientos_cartera_credimpulso'
          AND c.contype='c'
          AND pg_get_constraintdef(c.oid) ILIKE '%tipo%'
    LOOP
        EXECUTE format(
            'ALTER TABLE movimientos_cartera_credimpulso DROP CONSTRAINT IF EXISTS %I',
            constraint_name
        );
    END LOOP;
END
$c27_wallet_movement_constraint$;
ALTER TABLE movimientos_cartera_credimpulso
    ADD CONSTRAINT movimientos_cartera_credimpulso_tipo_check
    CHECK (tipo IN ('ABONO','TRANSFERENCIA','AJUSTE','ASIGNACION_CONTADOR','REVERSO'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_cuentas_credito_wallet_address
    ON cuentas_credito(wallet_address) WHERE wallet_address IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_carteras_admin_wallet_address
    ON carteras_credimpulso_admin(wallet_address) WHERE wallet_address IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_carteras_contador_wallet_address
    ON carteras_presupuesto_contador(wallet_address) WHERE wallet_address IS NOT NULL;

ALTER TABLE solicitudes_credito
    ADD COLUMN IF NOT EXISTS source_wallet_address VARCHAR(80),
    ADD COLUMN IF NOT EXISTS destination_wallet_address VARCHAR(80),
    ADD COLUMN IF NOT EXISTS wallet_transaction_id VARCHAR(120);
CREATE UNIQUE INDEX IF NOT EXISTS uq_solicitudes_credito_wallet_transaction
    ON solicitudes_credito(wallet_transaction_id)
    WHERE wallet_transaction_id IS NOT NULL;

-- Repara facturas faltantes de créditos históricos vinculados a pedidos.
-- Los préstamos creados directamente desde una solicitud pueden no tener order_id;
-- esos préstamos usan prestamos_credito.invoice_number y no deben insertarse en facturas.
INSERT INTO facturas(order_id, invoice_number)
SELECT cl.order_id,
       'IS-' || TO_CHAR(COALESCE(cl.created_at, NOW()), 'YYYYMMDD') || '-' || LPAD(cl.order_id::TEXT, 6, '0')
FROM prestamos_credito cl
LEFT JOIN facturas f ON f.order_id=cl.order_id
WHERE cl.order_id IS NOT NULL
  AND f.id IS NULL
ON CONFLICT DO NOTHING;

-- Asegura que todos los usuarios históricos tengan las filas auxiliares necesarias.
INSERT INTO cuentas_credito(user_id,level,credit_limit_usd,status,wallet_address)
SELECT u.id,1,60.00,'ACTIVE','ISU-' || UPPER(SUBSTRING(MD5('USER:' || u.id::TEXT), 1, 32))
FROM usuarios u
WHERE UPPER(u.role) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN','ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS','WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA')
  AND (u.account_status='ACTIVE' OR u.verification_status='VERIFIED')
ON CONFLICT(user_id) DO UPDATE
SET wallet_address=COALESCE(cuentas_credito.wallet_address, EXCLUDED.wallet_address);

INSERT INTO usuarios_credimpulso(user_id)
SELECT user_id FROM cuentas_credito
ON CONFLICT(user_id) DO NOTHING;

INSERT INTO historial_crediticio_usuarios(user_id)
SELECT user_id FROM cuentas_credito
ON CONFLICT(user_id) DO NOTHING;

-- Índices para cobro, inventario solicitado y consulta de facturas escaneadas.
CREATE INDEX IF NOT EXISTS idx_prestamos_credito_estado_usuario
    ON prestamos_credito(status,user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_pedidos_financing_status
    ON pedidos(financing_type,status,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_items_pedido_producto
    ON items_pedido(product_id,order_id);
CREATE INDEX IF NOT EXISTS idx_combos_pedido_combo
    ON combos_pedido(combo_id,order_id);
CREATE INDEX IF NOT EXISTS idx_qr_records_created
    ON registros_escaneo_qr(created_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (27, 'Cambios 27: carteras identificables, reparacion contable, exportacion, QR, inventario solicitado y creditos historicos')
ON CONFLICT(version) DO NOTHING;


-- CAMBIOS 28 ---------------------------------------------------------------
-- Inicio por nombre de usuario, directorio contable, notificaciones verificables
-- y libro mayor preparado para transacciones continuas e idempotentes.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS username VARCHAR(32);

-- Conserva los nombres válidos y repara cuentas históricas incompletas, inválidas
-- o duplicadas con un identificador determinista y único.
WITH username_rank AS (
    SELECT id,username,
           ROW_NUMBER() OVER (PARTITION BY LOWER(COALESCE(username,'')) ORDER BY id) AS duplicate_position
    FROM usuarios
)
UPDATE usuarios u
SET username = 'USER_' || u.id::TEXT
FROM username_rank r
WHERE u.id=r.id
  AND (
      u.username IS NULL OR BTRIM(u.username)=''
      OR u.username !~ '^[A-Za-z][A-Za-z0-9_.]{3,23}$'
      OR r.duplicate_position > 1
  );
ALTER TABLE usuarios ALTER COLUMN username SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_username_lower ON usuarios(LOWER(username));
CREATE INDEX IF NOT EXISTS idx_usuarios_role_status ON usuarios(role,account_status,id);

CREATE TABLE IF NOT EXISTS directorio_administradores_contador (
    admin_id BIGINT PRIMARY KEY REFERENCES usuarios(id) ON DELETE CASCADE,
    username VARCHAR(32) NOT NULL,
    email VARCHAR(255),
    full_name VARCHAR(220),
    wallet_address VARCHAR(80),
    wallet_balance_usd NUMERIC(18,2) NOT NULL DEFAULT 0,
    account_status VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_directorio_admin_username_lower
    ON directorio_administradores_contador(LOWER(username));
CREATE INDEX IF NOT EXISTS idx_directorio_admin_active
    ON directorio_administradores_contador(active,account_status,admin_id);
CREATE TABLE IF NOT EXISTS conteo_administradores_contador (
    singleton_id SMALLINT PRIMARY KEY CHECK(singleton_id=1),
    total_administradores INTEGER NOT NULL DEFAULT 0 CHECK(total_administradores>=0),
    administradores_activos INTEGER NOT NULL DEFAULT 0 CHECK(administradores_activos>=0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

UPDATE directorio_administradores_contador d
SET active=FALSE,
    account_status=COALESCE(u.account_status,'REMOVED'),
    synced_at=NOW()
FROM usuarios u
WHERE u.id=d.admin_id
  AND (
      UPPER(TRIM(u.role)) NOT IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
      OR u.account_status<>'ACTIVE'
  );
UPDATE directorio_administradores_contador d
SET active=FALSE,account_status='REMOVED',synced_at=NOW()
WHERE NOT EXISTS (SELECT 1 FROM usuarios u WHERE u.id=d.admin_id);

INSERT INTO directorio_administradores_contador(
    admin_id,username,email,full_name,wallet_address,wallet_balance_usd,account_status,active,synced_at
)
SELECT u.id,u.username,u.email,
       COALESCE(NULLIF(TRIM(p.full_name),''),u.username),
       COALESCE(NULLIF(w.wallet_address,''),'ISA-' || UPPER(SUBSTRING(MD5('ADMIN:' || u.id::TEXT),1,32))),
       COALESCE(w.saldo_disponible_usd,0),u.account_status,
       (u.account_status='ACTIVE'),NOW()
FROM usuarios u
LEFT JOIN perfiles_usuario p ON p.user_id=u.id
LEFT JOIN carteras_credimpulso_admin w ON w.admin_id=u.id
WHERE UPPER(TRIM(u.role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN')
ON CONFLICT(admin_id) DO UPDATE SET
    username=EXCLUDED.username,email=EXCLUDED.email,full_name=EXCLUDED.full_name,
    wallet_address=EXCLUDED.wallet_address,wallet_balance_usd=EXCLUDED.wallet_balance_usd,
    account_status=EXCLUDED.account_status,active=EXCLUDED.active,synced_at=NOW();

INSERT INTO conteo_administradores_contador(
    singleton_id,total_administradores,administradores_activos,updated_at
)
SELECT 1,COUNT(*)::INTEGER,
       COUNT(*) FILTER (WHERE active=TRUE AND account_status='ACTIVE')::INTEGER,
       NOW()
FROM directorio_administradores_contador
ON CONFLICT(singleton_id) DO UPDATE SET
    total_administradores=EXCLUDED.total_administradores,
    administradores_activos=EXCLUDED.administradores_activos,
    updated_at=NOW();

ALTER TABLE asignaciones_presupuesto_admin ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(120);
CREATE UNIQUE INDEX IF NOT EXISTS uq_asignacion_presupuesto_idempotency
    ON asignaciones_presupuesto_admin(idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS transacciones_carteras_continuas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_id BIGSERIAL UNIQUE,
    idempotency_key VARCHAR(120) UNIQUE,
    operation_type VARCHAR(60) NOT NULL,
    source_wallet_address VARCHAR(80),
    destination_wallet_address VARCHAR(80),
    amount_usd NUMERIC(18,2) NOT NULL CHECK(amount_usd>0),
    bcv_rate NUMERIC(18,6),
    amount_bs NUMERIC(20,2),
    status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED'
        CHECK(status IN ('PENDING','PROCESSING','COMPLETED','FAILED','REVERSED')),
    reference VARCHAR(120) NOT NULL UNIQUE,
    actor_user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    related_user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_source_created
    ON transacciones_carteras_continuas(source_wallet_address,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_destination_created
    ON transacciones_carteras_continuas(destination_wallet_address,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_status_sequence
    ON transacciones_carteras_continuas(status,sequence_id);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_created
    ON transacciones_carteras_continuas(created_at DESC, sequence_id DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_operation_created
    ON transacciones_carteras_continuas(operation_type, created_at DESC);

ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS token_kind VARCHAR(50) NOT NULL DEFAULT 'FCM_REGISTRATION_TOKEN';
UPDATE tokens_dispositivo SET token_kind='FCM_REGISTRATION_TOKEN' WHERE token_kind IS NULL OR token_kind='FIREBASE_INSTALLATION_ID';
ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS last_success_at TIMESTAMPTZ;
ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS last_error TEXT;
ALTER TABLE tokens_dispositivo ADD COLUMN IF NOT EXISTS failure_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS entregas_notificaciones (
    id BIGSERIAL PRIMARY KEY,
    notification_id BIGINT REFERENCES notificaciones(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    token TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED'
        CHECK(status IN ('QUEUED','SENT','FAILED','INVALID_TOKEN')),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_delivery_user_status
    ON entregas_notificaciones(user_id,status,created_at DESC);

INSERT INTO versiones_esquema(version,description)
VALUES(28,'Cambios 28: usuario único, notificaciones confiables, directorio de administradores y carteras continuas')
ON CONFLICT(version) DO NOTHING;


-- Cambios 29: transferencias administrativas por nombre de usuario y revisión documental visible.
INSERT INTO versiones_esquema(version, description)
VALUES (29, 'Cambios 29: transferencias por username y revisión administrativa de documentos')
ON CONFLICT (version) DO NOTHING;


-- Cambios 31: credenciales criptográficas de dispositivo para desbloqueo biométrico.
-- Nunca se guardan imágenes, plantillas ni hashes de huellas dactilares.
CREATE TABLE IF NOT EXISTS credenciales_biometricas_dispositivo (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    device_id_hash CHAR(64) NOT NULL,
    public_key_hash CHAR(64) NOT NULL,
    public_key_base64 TEXT NOT NULL,
    key_algorithm VARCHAR(20) NOT NULL DEFAULT 'EC' CHECK(key_algorithm IN ('EC','RSA')),
    platform VARCHAR(30) NOT NULL DEFAULT 'ANDROID',
    device_name VARCHAR(255),
    app_version VARCHAR(80),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMPTZ,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, device_id_hash)
);
DROP INDEX IF EXISTS uq_biometric_public_key_hash;
CREATE INDEX IF NOT EXISTS idx_biometric_public_key_hash
    ON credenciales_biometricas_dispositivo(public_key_hash);
CREATE INDEX IF NOT EXISTS idx_biometric_user_enabled
    ON credenciales_biometricas_dispositivo(user_id, enabled, updated_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (31, 'Cambios 31: parche de registro y credenciales biométricas de dispositivo')
ON CONFLICT (version) DO NOTHING;


-- Cambios 32: consentimiento versionado, notificaciones privadas y seguridad administrable.
CREATE TABLE IF NOT EXISTS consentimientos_usuario (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    terms_version VARCHAR(80) NOT NULL,
    privacy_version VARCHAR(80) NOT NULL,
    accepted BOOLEAN NOT NULL DEFAULT TRUE,
    source VARCHAR(30) NOT NULL DEFAULT 'ANDROID',
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    withdrawn_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_consents_user_date
    ON consentimientos_usuario(user_id,accepted_at DESC);

-- Elimina asociaciones antiguas creadas durante el registro antes de que existiera C32.
-- Los dispositivos vuelven a vincularse exclusivamente después de una autenticación válida.
DELETE FROM tokens_dispositivo td
USING usuarios u
WHERE td.user_id=u.id
  AND u.account_status<>'ACTIVE';

INSERT INTO versiones_esquema(version, description)
VALUES (32, 'Cambios 32: consentimiento versionado, notificaciones privadas, verificaciones y carteras')
ON CONFLICT (version) DO NOTHING;

INSERT INTO versiones_esquema(version, description)
VALUES (50, 'Credicash 5.0.0: visor público sanitizado del libro contable privado y consolidación de parches Cambios 32')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();



INSERT INTO versiones_esquema(version, description)
VALUES (51, 'Credicash 5.0.1: migraciones Pre-Deploy y healthcheck rápido para Railway')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

INSERT INTO versiones_esquema(version, description)
VALUES (52, 'Credicash 5.0.2: configuración Railway tipada y Gradle Wrapper protegido en Docker')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


INSERT INTO versiones_esquema(version, description)
VALUES (53, 'Credicash 5.0.3: migración automática en segundo plano y arranque Railway tolerante')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Cambios 33 / Credicash 5.0.8: rebranding y nombres geográficos de niveles.
UPDATE nivel_credimpulso_1 SET nombre='Santa Ana', updated_at=NOW() WHERE id=1;
UPDATE nivel_credimpulso_2 SET nombre='El Ávila', updated_at=NOW() WHERE id=1;
UPDATE nivel_credimpulso_3 SET nombre='Autana', updated_at=NOW() WHERE id=1;
UPDATE nivel_credimpulso_4 SET nombre='Auyantepuy', updated_at=NOW() WHERE id=1;
UPDATE nivel_credimpulso_5 SET nombre='Pico Bolívar', updated_at=NOW() WHERE id=1;
UPDATE nivel_credimpulso_6 SET nombre='Salto Ángel', updated_at=NOW() WHERE id=1;

INSERT INTO versiones_esquema(version, description)
VALUES (54, 'Credicash 5.0.8 - Cambios 33: rebranding, CameraX compilable y niveles venezolanos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


INSERT INTO versiones_esquema(version, description)
VALUES (55, 'Credicash 5.0.10: seguridad de acceso, vistas idempotentes, permisos mínimos y marca técnica consolidada')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Credicash 5.0.18: actualización instantánea del visor mediante PostgreSQL LISTEN/NOTIFY.
CREATE OR REPLACE FUNCTION notify_credicash_ledger_changed()
RETURNS TRIGGER AS $$
DECLARE
    changed_id TEXT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        changed_id := OLD.id::TEXT;
    ELSE
        changed_id := NEW.id::TEXT;
    END IF;

    PERFORM pg_notify(
        'credicash_ledger_changed',
        json_build_object(
            'table', TG_TABLE_NAME,
            'operation', TG_OP,
            'id', changed_id,
            'changedAt', clock_timestamp()
        )::TEXT
    );

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_notify_ledger_wallet_transactions ON transacciones_carteras_continuas;
CREATE TRIGGER trg_notify_ledger_wallet_transactions
AFTER INSERT OR UPDATE OR DELETE ON transacciones_carteras_continuas
FOR EACH ROW EXECUTE FUNCTION notify_credicash_ledger_changed();

DROP TRIGGER IF EXISTS trg_notify_ledger_credit_requests ON solicitudes_credito;
CREATE TRIGGER trg_notify_ledger_credit_requests
AFTER INSERT OR UPDATE OR DELETE ON solicitudes_credito
FOR EACH ROW EXECUTE FUNCTION notify_credicash_ledger_changed();

DROP TRIGGER IF EXISTS trg_notify_ledger_credit_activity ON transacciones_credimpulso;
CREATE TRIGGER trg_notify_ledger_credit_activity
AFTER INSERT OR UPDATE OR DELETE ON transacciones_credimpulso
FOR EACH ROW EXECUTE FUNCTION notify_credicash_ledger_changed();

INSERT INTO versiones_esquema(version, description)
VALUES (56, 'Credicash 5.0.18: sincronización en tiempo real del visor mediante PostgreSQL LISTEN/NOTIFY y SSE')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 59 / Credicash 5.0.33: precios maestros en USD, cálculo BCV exacto y sesión única activa.
ALTER TABLE productos ADD COLUMN IF NOT EXISTS base_price_usd NUMERIC(18,6) NOT NULL DEFAULT 0;
ALTER TABLE productos ADD COLUMN IF NOT EXISTS bcv_rate NUMERIC(18,6) NOT NULL DEFAULT 0;
ALTER TABLE productos ADD COLUMN IF NOT EXISTS pricing_mode VARCHAR(20) NOT NULL DEFAULT 'UNIT';
ALTER TABLE productos ADD COLUMN IF NOT EXISTS price_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
ALTER TABLE productos DROP CONSTRAINT IF EXISTS productos_pricing_mode_check;
ALTER TABLE productos ADD CONSTRAINT productos_pricing_mode_check CHECK (pricing_mode IN ('UNIT','KG'));
CREATE INDEX IF NOT EXISTS idx_products_pricing_mode ON productos(pricing_mode, active, name);

ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS device_id_hash VARCHAR(64);
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ;
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS ended_reason VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_user_sessions_heartbeat
ON sesiones_usuario(user_id, last_heartbeat_at DESC)
WHERE revoked_at IS NULL;

-- Las instalaciones anteriores no conocían el precio USD. El valor se mantiene en Bs y
-- se completa en la siguiente edición del producto usando la tasa BCV vigente.
UPDATE productos
SET price_updated_at = COALESCE(price_updated_at, updated_at, NOW())
WHERE price_updated_at IS NULL;

-- Las sesiones creadas antes de identificar el dispositivo no pueden participar de la
-- política de sesión única. Se cierran una sola vez durante la actualización de seguridad.
UPDATE sesiones_usuario
SET revoked_at = COALESCE(revoked_at, NOW()),
    ended_reason = COALESCE(ended_reason, 'SECURITY_UPGRADE_5_0_33')
WHERE revoked_at IS NULL AND device_id_hash IS NULL;

INSERT INTO versiones_esquema(version, description)
VALUES (59, 'Credicash 5.0.33: precios USD/BCV exactos, productos por kilogramo, centro de pagos y sesión única por usuario')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 60 / Credicash 5.0.34: motor presupuestario consolidado y movimientos exactos.
CREATE TABLE IF NOT EXISTS movimientos_presupuestarios (
    id BIGSERIAL PRIMARY KEY,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    tipo VARCHAR(40) NOT NULL CHECK (tipo IN (
        'BANK_INCOME','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE','RESERVE','RELEASE',
        'ADJUSTMENT_CREDIT','ADJUSTMENT_DEBIT'
    )),
    monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd > 0),
    tasa_bcv NUMERIC(18,6) NOT NULL CHECK (tasa_bcv > 0),
    monto_bs NUMERIC(20,2) NOT NULL CHECK (monto_bs >= 0),
    saldo_antes_usd NUMERIC(18,2) NOT NULL CHECK (saldo_antes_usd >= 0),
    saldo_despues_usd NUMERIC(18,2) NOT NULL CHECK (saldo_despues_usd >= 0),
    referencia VARCHAR(120) NOT NULL UNIQUE,
    descripcion VARCHAR(500),
    categoria_gasto VARCHAR(60),
    idempotency_key VARCHAR(120) UNIQUE,
    estado VARCHAR(30) NOT NULL DEFAULT 'COMPLETED'
        CHECK (estado IN ('PENDING','COMPLETED','REVERSED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reversed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_budget_movements_accountant_date
    ON movimientos_presupuestarios(contador_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_budget_movements_accountant_type
    ON movimientos_presupuestarios(contador_id, tipo, estado);

INSERT INTO versiones_esquema(version, description)
VALUES (60, 'Credicash 5.0.34: sistema presupuestario avanzado, gasto, inversión, préstamos, reservas, proyección y conciliación exacta')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 61 / Credicash 6.0.0: SemVer, predicción explicable y facturación algorítmica.
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS integrity_status VARCHAR(30) NOT NULL DEFAULT 'PENDING';
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS integrity_score INTEGER NOT NULL DEFAULT 0;
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS calculated_total_bs NUMERIC(20,2) NOT NULL DEFAULT 0;
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS integrity_difference_bs NUMERIC(20,2) NOT NULL DEFAULT 0;
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS document_hash VARCHAR(64);
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS algorithm_version VARCHAR(40) NOT NULL DEFAULT 'INVOICE-6.0.0';
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS validation_warnings TEXT NOT NULL DEFAULT '';
ALTER TABLE facturas ADD COLUMN IF NOT EXISTS integrity_verified_at TIMESTAMPTZ;
ALTER TABLE facturas DROP CONSTRAINT IF EXISTS facturas_integrity_status_check;
ALTER TABLE facturas ADD CONSTRAINT facturas_integrity_status_check
    CHECK (integrity_status IN ('PENDING','VERIFIED','REVIEW_REQUIRED','REJECTED'));
ALTER TABLE facturas DROP CONSTRAINT IF EXISTS facturas_integrity_score_check;
ALTER TABLE facturas ADD CONSTRAINT facturas_integrity_score_check CHECK (integrity_score BETWEEN 0 AND 100);
CREATE UNIQUE INDEX IF NOT EXISTS uq_facturas_document_hash
    ON facturas(document_hash) WHERE document_hash IS NOT NULL AND document_hash<>'';
CREATE INDEX IF NOT EXISTS idx_facturas_integrity
    ON facturas(integrity_status, integrity_score, generated_at DESC);

CREATE TABLE IF NOT EXISTS evaluaciones_predictivas (
    id BIGSERIAL PRIMARY KEY,
    subject_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    subject_role VARCHAR(30) NOT NULL,
    payment_success_percent NUMERIC(6,2) NOT NULL CHECK (payment_success_percent BETWEEN 0 AND 100),
    purchase_success_percent NUMERIC(6,2) NOT NULL CHECK (purchase_success_percent BETWEEN 0 AND 100),
    late_probability_percent NUMERIC(6,2) NOT NULL CHECK (late_probability_percent BETWEEN 0 AND 100),
    confidence_percent NUMERIC(6,2) NOT NULL CHECK (confidence_percent BETWEEN 0 AND 100),
    risk_level VARCHAR(20) NOT NULL CHECK (risk_level IN ('VERY_LOW','LOW','MEDIUM','HIGH','CRITICAL')),
    recommended_limit_usd NUMERIC(18,2) NOT NULL DEFAULT 0,
    predicted_next_purchase_usd NUMERIC(18,2) NOT NULL DEFAULT 0,
    sample_size INTEGER NOT NULL DEFAULT 0,
    factors_json TEXT NOT NULL DEFAULT '[]',
    model_version VARCHAR(40) NOT NULL DEFAULT 'PREDICTIVE-6.0.0',
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_predictive_subject_latest
    ON evaluaciones_predictivas(subject_id, generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_predictive_risk
    ON evaluaciones_predictivas(risk_level, payment_success_percent, generated_at DESC);

CREATE TABLE IF NOT EXISTS corridas_predictivas_presupuesto (
    id BIGSERIAL PRIMARY KEY,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    collection_probability_percent NUMERIC(6,2) NOT NULL,
    default_risk_percent NUMERIC(6,2) NOT NULL,
    liquidity_risk_level VARCHAR(20) NOT NULL,
    confidence_percent NUMERIC(6,2) NOT NULL,
    forecasts_json TEXT NOT NULL,
    alerts_json TEXT NOT NULL,
    model_version VARCHAR(40) NOT NULL DEFAULT 'PREDICTIVE-6.0.0',
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_predictive_budget_latest
    ON corridas_predictivas_presupuesto(contador_id, generated_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (61, 'Credicash 6.0.0: nomenclatura SemVer, sistema predictivo presupuestario, probabilidad de pago y facturación algorítmica')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 62 / Credicash 6.0.1: parche de autenticación, servicios y compatibilidad.
-- Esta migración no elimina usuarios ni documentos. Repara el esquema de sesión única
-- antes de que el PIN intente crear una sesión persistente.
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS device_id_hash VARCHAR(64);
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS device_name VARCHAR(255);
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS app_version VARCHAR(80);
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ;
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ;
ALTER TABLE sesiones_usuario ADD COLUMN IF NOT EXISTS ended_reason VARCHAR(80);

UPDATE sesiones_usuario
SET revoked_at=COALESCE(revoked_at,NOW()),
    ended_reason=COALESCE(ended_reason,'LEGACY_SESSION_WITHOUT_DEVICE')
WHERE revoked_at IS NULL AND (device_id_hash IS NULL OR BTRIM(device_id_hash)='');

CREATE INDEX IF NOT EXISTS idx_user_sessions_user_active
    ON sesiones_usuario(user_id, revoked_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_user_sessions_heartbeat
    ON sesiones_usuario(user_id, last_heartbeat_at DESC)
    WHERE revoked_at IS NULL;

INSERT INTO versiones_esquema(version, description)
VALUES (62, 'Credicash 6.0.1: parche de PIN, reparación idempotente de sesiones, diagnóstico de servicios y tasa BCV segura')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 63 / Credicash 6.0.2: compatibilidad de códigos de verificación históricos.
-- Corrige el reinicio de migraciones sobre bases que ya contienen ACCOUNT_VERIFICATION
-- u otros alias antiguos, sin borrar usuarios ni documentos.
ALTER TABLE codigos_verificacion DROP CONSTRAINT IF EXISTS verification_codes_purpose_check;
ALTER TABLE codigos_verificacion DROP CONSTRAINT IF EXISTS codigos_verificacion_purpose_check;

UPDATE codigos_verificacion
SET purpose = CASE
        WHEN UPPER(BTRIM(purpose)) IN (
            'EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET',
            'PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE'
        ) THEN UPPER(BTRIM(purpose))
        ELSE 'CRITICAL_ACTION'
    END,
    consumed_at = CASE
        WHEN UPPER(BTRIM(purpose)) NOT IN (
            'EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET',
            'PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE'
        ) THEN COALESCE(consumed_at,NOW())
        ELSE consumed_at
    END;

ALTER TABLE codigos_verificacion
    ADD CONSTRAINT codigos_verificacion_purpose_check
    CHECK (purpose IN (
        'EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET',
        'PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE'
    ));

INSERT INTO versiones_esquema(version, description)
VALUES (63, 'Credicash 6.0.2: compatibilidad segura de códigos históricos y corrección del bloqueo PostgreSQL en migración 13')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 64 / Credicash 6.2.0: reportes de pago de usuarios y análisis antifraude explicable.
CREATE TABLE IF NOT EXISTS reportes_pago_usuario (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    target_type VARCHAR(30) NOT NULL CHECK (target_type IN ('ORDER','CREDIT_INSTALLMENT')),
    order_id BIGINT REFERENCES pedidos(id) ON DELETE RESTRICT,
    installment_id BIGINT REFERENCES cuotas_credito(id) ON DELETE RESTRICT,
    invoice_number VARCHAR(80) NOT NULL,
    installment_number INTEGER,
    method VARCHAR(40) NOT NULL CHECK (method IN ('MOBILE_PAYMENT','BANK_TRANSFER')),
    origin_bank_code VARCHAR(4) NOT NULL,
    origin_bank_name_snapshot VARCHAR(180) NOT NULL,
    origin_phone VARCHAR(40) NOT NULL,
    reference_number VARCHAR(180) NOT NULL,
    amount_reported_bs NUMERIC(20,2) NOT NULL CHECK (amount_reported_bs > 0),
    expected_amount_bs NUMERIC(20,2) NOT NULL CHECK (expected_amount_bs > 0),
    paid_from_different_phone BOOLEAN NOT NULL DEFAULT FALSE,
    proof_file_path TEXT NOT NULL,
    proof_sha256 CHAR(64) NOT NULL,
    proof_visual_hash CHAR(16),
    user_notes TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'REPORTED'
        CHECK (status IN ('REPORTED','VERIFIED','REJECTED')),
    risk_score INTEGER NOT NULL DEFAULT 0 CHECK (risk_score BETWEEN 0 AND 100),
    risk_level VARCHAR(20) NOT NULL DEFAULT 'VERY_LOW'
        CHECK (risk_level IN ('VERY_LOW','LOW','MEDIUM','HIGH','CRITICAL')),
    confidence_percent INTEGER NOT NULL DEFAULT 45 CHECK (confidence_percent BETWEEN 0 AND 100),
    recommendation TEXT NOT NULL DEFAULT '',
    reasons_json TEXT NOT NULL DEFAULT '[]',
    suggestions_json TEXT NOT NULL DEFAULT '[]',
    algorithm_version VARCHAR(40) NOT NULL DEFAULT 'PAYMENT-RISK-6.2.0',
    bank_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    admin_notes TEXT,
    reviewed_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        (target_type='ORDER' AND order_id IS NOT NULL AND installment_id IS NULL)
        OR
        (target_type='CREDIT_INSTALLMENT' AND installment_id IS NOT NULL)
    )
);

ALTER TABLE reportes_pago_usuario
    ADD COLUMN IF NOT EXISTS proof_visual_hash CHAR(16);

CREATE INDEX IF NOT EXISTS idx_user_payment_reports_user_created
    ON reportes_pago_usuario(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_payment_reports_status_risk
    ON reportes_pago_usuario(status, risk_score DESC, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_payment_reports_reference
    ON reportes_pago_usuario(origin_bank_code, reference_number);
CREATE INDEX IF NOT EXISTS idx_user_payment_reports_proof_hash
    ON reportes_pago_usuario(proof_sha256);
CREATE INDEX IF NOT EXISTS idx_user_payment_reports_visual_hash
    ON reportes_pago_usuario(proof_visual_hash)
    WHERE proof_visual_hash IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_open_payment_report_installment
    ON reportes_pago_usuario(installment_id)
    WHERE installment_id IS NOT NULL AND status='REPORTED';
CREATE UNIQUE INDEX IF NOT EXISTS uq_open_payment_report_order
    ON reportes_pago_usuario(order_id)
    WHERE target_type='ORDER' AND order_id IS NOT NULL AND status='REPORTED';

INSERT INTO versiones_esquema(version, description)
VALUES (64, 'Credicash 6.2.0: reportes de pago de usuarios, comprobantes, verificación administrativa y motor antifraude explicable')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 65 / Credicash 6.4.2: experiencia por roles, conciliación y doble autorización.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS admin_subrole VARCHAR(30);
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_admin_subrole_check;
UPDATE usuarios
SET admin_subrole = CASE
    WHEN role='ADMIN' AND admin_subrole IN ('GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD') THEN admin_subrole
    WHEN role='ADMIN' THEN 'GENERAL'
    WHEN role='ACCOUNTANT' THEN 'ACCOUNTING'
    WHEN role='WAREHOUSE' THEN 'WAREHOUSE'
    ELSE NULL
END;
ALTER TABLE usuarios ADD CONSTRAINT usuarios_admin_subrole_check CHECK (
    admin_subrole IS NULL OR admin_subrole IN (
        'GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD','ACCOUNTING','WAREHOUSE'
    )
);

CREATE TABLE IF NOT EXISTS conciliaciones_pago (
    id BIGSERIAL PRIMARY KEY,
    payment_report_id BIGINT NOT NULL REFERENCES reportes_pago_usuario(id) ON DELETE RESTRICT,
    accountant_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL CHECK (status IN ('MATCHED','PROBABLE','MANUAL_REVIEW','DIFFERENCE','DUPLICATE','UNRELATED')),
    confidence_percent INTEGER NOT NULL DEFAULT 0 CHECK (confidence_percent BETWEEN 0 AND 100),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(payment_report_id)
);
CREATE INDEX IF NOT EXISTS idx_payment_reconciliation_status
    ON conciliaciones_pago(status, updated_at DESC);

CREATE TABLE IF NOT EXISTS solicitudes_doble_aprobacion (
    id BIGSERIAL PRIMARY KEY,
    action_type VARCHAR(60) NOT NULL,
    entity_type VARCHAR(60),
    entity_id BIGINT,
    amount_usd NUMERIC(20,2),
    description TEXT NOT NULL,
    requested_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    approved_by BIGINT REFERENCES usuarios(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','EXECUTED','CANCELLED')),
    decision_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reviewed_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_dual_approvals_status
    ON solicitudes_doble_aprobacion(status, created_at DESC);

CREATE TABLE IF NOT EXISTS cierres_contables (
    id BIGSERIAL PRIMARY KEY,
    period_month VARCHAR(7) NOT NULL UNIQUE,
    accountant_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','READY','CLOSED','REOPENED')),
    pending_differences INTEGER NOT NULL DEFAULT 0,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO versiones_esquema(version, description)
VALUES (65, 'Credicash 6.4.2: experiencia especializada por roles, expedientes, conciliación contable, subroles y doble autorización')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 66 / Credicash 6.4.3: operaciones reales, panel administrativo y español total.
ALTER TABLE carteras_presupuesto_contador
    ALTER COLUMN fuente_fondos SET DEFAULT 'INITIAL_OPERATING_BUDGET';

UPDATE carteras_presupuesto_contador
SET fuente_fondos='INITIAL_OPERATING_BUDGET', updated_at=NOW()
WHERE fuente_fondos IS NULL
   OR TRIM(fuente_fondos)=''
   OR fuente_fondos='SIMULATED_INITIAL_BUDGET';

INSERT INTO versiones_esquema(version, description)
VALUES (66, 'Credicash 6.4.3: eliminación del simulador, operaciones reales, panel administrativo centralizado y español total')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 67 / Credicash 6.4.4: rol Almacenista, precio maestro de inventario y cargas visibles.
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_role_check;
UPDATE usuarios
SET role = CASE
    WHEN UPPER(BTRIM(role)) IN ('ADMIN','ADMINISTRATOR','ADMINISTRADOR','SUPERADMIN','SUPER_ADMIN') THEN 'ADMIN'
    WHEN UPPER(BTRIM(role)) IN ('ACCOUNTANT','CONTADOR','CONTADORA','FINANCE','FINANZAS') THEN 'ACCOUNTANT'
    WHEN UPPER(BTRIM(role)) IN ('WAREHOUSE','ALMACENISTA','STOREKEEPER','ALMACEN','BODEGA') THEN 'WAREHOUSE'
    ELSE 'BENEFICIARY'
END;
ALTER TABLE usuarios
    ADD CONSTRAINT usuarios_role_check CHECK (role IN ('BENEFICIARY','ADMIN','ACCOUNTANT','WAREHOUSE'));

-- La restricción histórica debe retirarse antes de asignar WAREHOUSE.
-- De lo contrario, las bases actualizadas desde 6.4.2 rechazan al Almacenista.
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_admin_subrole_check;
UPDATE usuarios
SET admin_subrole = CASE
    WHEN role='ADMIN' AND admin_subrole IN ('GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD') THEN admin_subrole
    WHEN role='ADMIN' THEN 'GENERAL'
    WHEN role='ACCOUNTANT' THEN 'ACCOUNTING'
    WHEN role='WAREHOUSE' THEN 'WAREHOUSE'
    ELSE NULL
END;
ALTER TABLE usuarios ADD CONSTRAINT usuarios_admin_subrole_check CHECK (
    (role='BENEFICIARY' AND admin_subrole IS NULL)
    OR (role='ADMIN' AND admin_subrole IN ('GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD'))
    OR (role='ACCOUNTANT' AND admin_subrole='ACCOUNTING')
    OR (role='WAREHOUSE' AND admin_subrole='WAREHOUSE')
);

-- El precio de venta se define exclusivamente en Inventario. La jornada conserva
-- una instantánea sincronizada para auditoría, pero no acepta un precio manual.
UPDATE productos_jornada fp
SET fair_price = p.base_price
FROM productos p
WHERE p.id = fp.product_id
  AND fp.fair_price IS DISTINCT FROM p.base_price;

-- Normaliza instalaciones antiguas que todavía mostraban la palabra eliminada.
UPDATE nivel_credimpulso_1 SET nombre=REPLACE(nombre,'Cerro ',''), updated_at=NOW() WHERE nombre ILIKE 'Cerro %';
UPDATE nivel_credimpulso_2 SET nombre=REPLACE(nombre,'Cerro ',''), updated_at=NOW() WHERE nombre ILIKE 'Cerro %';
UPDATE nivel_credimpulso_3 SET nombre=REPLACE(nombre,'Cerro ',''), updated_at=NOW() WHERE nombre ILIKE 'Cerro %';
UPDATE nivel_credimpulso_4 SET nombre=REPLACE(nombre,'Cerro ',''), updated_at=NOW() WHERE nombre ILIKE 'Cerro %';
UPDATE nivel_credimpulso_5 SET nombre=REPLACE(nombre,'Cerro ',''), updated_at=NOW() WHERE nombre ILIKE 'Cerro %';
UPDATE nivel_credimpulso_6 SET nombre=REPLACE(nombre,'Cerro ',''), updated_at=NOW() WHERE nombre ILIKE 'Cerro %';

INSERT INTO versiones_esquema(version, description)
VALUES (67, 'Credicash 6.4.4: Almacenista, precio maestro de inventario, carga visible de imágenes y comprobantes atómicos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 68 / Credicash 6.5.0: consolidación, calidad operativa y controles preventivos.
ALTER TABLE productos ADD COLUMN IF NOT EXISTS minimum_stock INTEGER NOT NULL DEFAULT 5;
ALTER TABLE productos ADD COLUMN IF NOT EXISTS last_counted_at TIMESTAMPTZ;
-- Normaliza datos heredados antes de activar restricciones estrictas.
UPDATE productos
SET minimum_stock=GREATEST(COALESCE(minimum_stock,5),0),
    stock=GREATEST(COALESCE(stock,0),0),
    base_price_usd=GREATEST(COALESCE(base_price_usd,0),0),
    base_price=GREATEST(COALESCE(base_price,0),0)
WHERE minimum_stock IS NULL OR minimum_stock<0
   OR stock IS NULL OR stock<0
   OR base_price_usd IS NULL OR base_price_usd<0
   OR base_price IS NULL OR base_price<0;
ALTER TABLE productos DROP CONSTRAINT IF EXISTS productos_minimum_stock_check;
ALTER TABLE productos ADD CONSTRAINT productos_minimum_stock_check CHECK (minimum_stock >= 0);
ALTER TABLE productos DROP CONSTRAINT IF EXISTS productos_stock_nonnegative_check;
ALTER TABLE productos ADD CONSTRAINT productos_stock_nonnegative_check CHECK (stock >= 0);
ALTER TABLE productos DROP CONSTRAINT IF EXISTS productos_price_usd_positive_check;
ALTER TABLE productos ADD CONSTRAINT productos_price_usd_positive_check CHECK (base_price_usd >= 0);
ALTER TABLE productos DROP CONSTRAINT IF EXISTS productos_price_bs_positive_check;
ALTER TABLE productos ADD CONSTRAINT productos_price_bs_positive_check CHECK (base_price >= 0);

ALTER TABLE movimientos_inventario DROP CONSTRAINT IF EXISTS movimientos_inventario_movement_type_check;
ALTER TABLE movimientos_inventario ADD CONSTRAINT movimientos_inventario_movement_type_check CHECK (
    movement_type IN (
        'INITIAL','ADJUSTMENT','SALE','COMMUNITY_DISPATCH','RETURN',
        'RESERVATION','RELEASE','DAMAGE','COUNT','REVERSAL'
    )
);

ALTER TABLE reportes_pago_usuario
    ADD COLUMN IF NOT EXISTS amount_difference_bs NUMERIC(20,2) NOT NULL DEFAULT 0;
ALTER TABLE reportes_pago_usuario
    ADD COLUMN IF NOT EXISTS amount_difference_percent NUMERIC(10,2) NOT NULL DEFAULT 0;
ALTER TABLE reportes_pago_usuario
    ADD COLUMN IF NOT EXISTS decision_version VARCHAR(40) NOT NULL DEFAULT 'PAYMENT-REVIEW-6.5.0';
ALTER TABLE reportes_pago_usuario DROP CONSTRAINT IF EXISTS reportes_pago_amount_difference_bs_check;
ALTER TABLE reportes_pago_usuario ADD CONSTRAINT reportes_pago_amount_difference_bs_check CHECK (amount_difference_bs >= 0);
ALTER TABLE reportes_pago_usuario DROP CONSTRAINT IF EXISTS reportes_pago_amount_difference_percent_check;
ALTER TABLE reportes_pago_usuario ADD CONSTRAINT reportes_pago_amount_difference_percent_check CHECK (amount_difference_percent >= 0);

UPDATE reportes_pago_usuario
SET amount_difference_bs=ABS(amount_reported_bs-expected_amount_bs),
    amount_difference_percent=CASE
        WHEN expected_amount_bs>0 THEN ROUND(ABS(amount_reported_bs-expected_amount_bs)*100/expected_amount_bs,2)
        ELSE 0
    END,
    decision_version='PAYMENT-REVIEW-6.5.0'
WHERE amount_difference_bs=0 OR decision_version<>'PAYMENT-REVIEW-6.5.0';

CREATE INDEX IF NOT EXISTS idx_payment_reports_review_queue
    ON reportes_pago_usuario(status, risk_score DESC, amount_difference_percent DESC, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_products_low_stock
    ON productos(active, stock, minimum_stock)
    WHERE active=TRUE;

CREATE OR REPLACE VIEW vista_integridad_inventario AS
SELECT
    p.id AS product_id,
    p.name AS product_name,
    p.stock AS recorded_stock,
    COALESCE(SUM(mi.quantity_delta),0)::INTEGER AS movement_stock,
    p.stock-COALESCE(SUM(mi.quantity_delta),0)::INTEGER AS difference,
    p.minimum_stock,
    (p.stock<=p.minimum_stock) AS low_stock,
    (p.stock=COALESCE(SUM(mi.quantity_delta),0)::INTEGER) AS consistent
FROM productos p
LEFT JOIN movimientos_inventario mi ON mi.product_id=p.id
GROUP BY p.id,p.name,p.stock,p.minimum_stock;

INSERT INTO versiones_esquema(version, description)
VALUES (68, 'Credicash 6.5.0: consolidación operativa, cargas seguras, pagos reforzados, inventario verificable y calidad automática')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 69 / Credicash 6.5.4: reparación idempotente del subrol Almacenista.
-- Reafirma el contrato rol/subrol después de actualizar instalaciones existentes.
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_admin_subrole_check;
UPDATE usuarios
SET admin_subrole = CASE
    WHEN role='ADMIN' AND admin_subrole IN ('GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD') THEN admin_subrole
    WHEN role='ADMIN' THEN 'GENERAL'
    WHEN role='ACCOUNTANT' THEN 'ACCOUNTING'
    WHEN role='WAREHOUSE' THEN 'WAREHOUSE'
    ELSE NULL
END;
ALTER TABLE usuarios ADD CONSTRAINT usuarios_admin_subrole_check CHECK (
    (role='BENEFICIARY' AND admin_subrole IS NULL)
    OR (role='ADMIN' AND admin_subrole IN ('GENERAL','SUPERVISOR','ANALYST','SUPPORT','AUDITOR','ANTIFRAUD'))
    OR (role='ACCOUNTANT' AND admin_subrole='ACCOUNTING')
    OR (role='WAREHOUSE' AND admin_subrole='WAREHOUSE')
);

ALTER TABLE reportes_pago_usuario
    ALTER COLUMN decision_version SET DEFAULT 'PAYMENT-REVIEW-6.5.4';
UPDATE reportes_pago_usuario
SET decision_version='PAYMENT-REVIEW-6.5.4'
WHERE decision_version IS NULL
   OR decision_version IN ('PAYMENT-REVIEW-6.5.0','PAYMENT-REVIEW-6.5.1');

INSERT INTO versiones_esquema(version, description)
VALUES (69, 'Credicash 6.5.4: corrección del orden de migración, restricción rol/subrol para Almacenista y política de pagos vigente')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 70 / Credicash 6.6.0: negocios asociados gestionados por Contador.
CREATE TABLE IF NOT EXISTS negocios_asociados (
    id BIGSERIAL PRIMARY KEY,
    commercial_name VARCHAR(180) NOT NULL,
    legal_name VARCHAR(220) NOT NULL,
    rif VARCHAR(20) NOT NULL,
    logo_path TEXT,
    phone VARCHAR(40),
    email VARCHAR(254),
    address TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    payment_mode VARCHAR(40) NOT NULL DEFAULT 'MOBILE_PAYMENT'
        CHECK (payment_mode IN ('MOBILE_PAYMENT','BANK_TRANSFER','BOTH')),
    mobile_bank VARCHAR(160),
    mobile_phone VARCHAR(40),
    mobile_identity_number VARCHAR(80),
    mobile_holder_name VARCHAR(220),
    bank_name VARCHAR(160),
    bank_account_type VARCHAR(80),
    bank_account_number VARCHAR(120),
    bank_identity_number VARCHAR(80),
    bank_holder_name VARCHAR(220),
    created_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_negocios_asociados_rif_activo
    ON negocios_asociados(UPPER(REPLACE(REPLACE(rif,'-',''),' ','')));
CREATE INDEX IF NOT EXISTS idx_negocios_asociados_active_name
    ON negocios_asociados(active, commercial_name);

ALTER TABLE jornadas ADD COLUMN IF NOT EXISTS business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_jornadas_business_id ON jornadas(business_id);

INSERT INTO versiones_esquema(version, description)
VALUES (70, 'Credicash 6.6.0: negocios asociados, cuentas de cobro propias, logos y vinculación con jornadas')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 71 / Credicash 6.6.6: préstamos desembolsables, empresas prestamistas y cuotas visibles en Pagos.
-- Las solicitudes de préstamo aprobadas pasan a crear un préstamo real y su calendario de cuotas.
ALTER TABLE prestamos_credito ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(120);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS credit_request_id BIGINT REFERENCES solicitudes_credito(id) ON DELETE RESTRICT;
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS lender_type VARCHAR(40) NOT NULL DEFAULT 'ADMIN_WALLET';
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS lender_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE RESTRICT;
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE RESTRICT;
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_destination_type VARCHAR(40) NOT NULL DEFAULT 'CREDICASH_WALLET';
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_bank_code VARCHAR(20);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_bank_name VARCHAR(180);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_account_type VARCHAR(50);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_account_number VARCHAR(40);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_holder_name VARCHAR(220);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS disbursement_identity_number VARCHAR(40);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_payment_mode VARCHAR(40);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_business_commercial_name VARCHAR(180);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_business_legal_name VARCHAR(220);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_business_rif VARCHAR(20);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_business_logo_path TEXT;
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_mobile_bank VARCHAR(160);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_mobile_phone VARCHAR(40);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_mobile_identity_number VARCHAR(80);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_mobile_holder_name VARCHAR(220);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_bank_name VARCHAR(160);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_bank_account_type VARCHAR(80);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_bank_account_number VARCHAR(120);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_bank_identity_number VARCHAR(80);
ALTER TABLE prestamos_credito ADD COLUMN IF NOT EXISTS repayment_bank_holder_name VARCHAR(220);

UPDATE prestamos_credito cl
SET invoice_number=COALESCE(NULLIF(cl.invoice_number,''),f.invoice_number,'CRED-' || cl.id::text)
FROM facturas f
WHERE f.order_id=cl.order_id AND (cl.invoice_number IS NULL OR cl.invoice_number='');
UPDATE prestamos_credito SET invoice_number='CRED-' || id::text WHERE invoice_number IS NULL OR invoice_number='';

ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_lender_type_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_lender_type_check
    CHECK (lender_type IN ('ADMIN_WALLET','ASSOCIATED_BUSINESS'));
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_lender_business_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_lender_business_check CHECK (
    (lender_type='ADMIN_WALLET' AND lender_business_id IS NULL)
    OR (lender_type='ASSOCIATED_BUSINESS' AND lender_business_id IS NOT NULL)
) NOT VALID;
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_disbursement_destination_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_disbursement_destination_check
    CHECK (disbursement_destination_type IN ('CREDICASH_WALLET','BANK_ACCOUNT'));
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_repayment_mode_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_repayment_mode_check
    CHECK (repayment_payment_mode IS NULL OR repayment_payment_mode IN ('MOBILE_PAYMENT','BANK_TRANSFER','BOTH'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_prestamos_credito_request
    ON prestamos_credito(credit_request_id) WHERE credit_request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_prestamos_credito_lender
    ON prestamos_credito(lender_type,lender_business_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_prestamos_credito_repayment_business
    ON prestamos_credito(repayment_business_id,status,created_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (71, 'Credicash 6.6.6: préstamos reales desde solicitudes, empresa prestamista, cuenta de desembolso y cuotas visibles en Pagos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 72 / Credicash 6.6.13: compatibilidad de facturas con préstamos sin pedido.
INSERT INTO versiones_esquema(version, description)
VALUES (72, 'Credicash 6.6.13: evita crear facturas de pedido para préstamos directos sin order_id')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();



-- Migración 73: separa la ficha técnica de productos farmacéuticos y tecnológicos.
ALTER TABLE productos ADD COLUMN IF NOT EXISTS technical_details TEXT NOT NULL DEFAULT '';
INSERT INTO versiones_esquema(version, description)
VALUES (73, 'Credicash 6.6.22: ficha técnica separada de categoría para farmacia y otros productos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 74 / Credicash 7.0.0: administración de roles operativos desde las aplicaciones.
-- El Contador es la única cuenta bootstrap. Administradores y Almacenistas se asignan desde Android/Escritorio.
INSERT INTO versiones_esquema(version, description)
VALUES (74, 'Credicash 7.0.0: Contador bootstrap único y asignación de Administradores/Almacenistas desde las aplicaciones')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 75 / Credicash 7.2.2: alta directa de personal por el Contador y doble acceso vinculado.
-- ADMIN y WAREHOUSE pueden disponer de una segunda cuenta BENEFICIARY sin mezclar permisos ni cartera.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS person_group_id UUID;
UPDATE usuarios SET person_group_id=public_id WHERE person_group_id IS NULL;
ALTER TABLE usuarios ALTER COLUMN person_group_id SET DEFAULT gen_random_uuid();
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS account_kind VARCHAR(20);
ALTER TABLE usuarios ALTER COLUMN account_kind SET DEFAULT 'BENEFICIARY';
UPDATE usuarios SET account_kind=CASE
    WHEN role='ACCOUNTANT' THEN 'ACCOUNTANT'
    WHEN role IN ('ADMIN','WAREHOUSE') THEN 'OPERATIONAL'
    ELSE 'BENEFICIARY'
END WHERE account_kind IS NULL OR account_kind='';
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_account_kind_check;
ALTER TABLE usuarios ADD CONSTRAINT usuarios_account_kind_check CHECK (account_kind IN ('BENEFICIARY','OPERATIONAL','ACCOUNTANT'));
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS linked_account_user_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL;

-- Dos cuentas vinculadas de la misma persona comparten teléfono; la unicidad de registro
-- público continúa protegida por la validación transaccional del backend.
DROP INDEX IF EXISTS uq_user_profiles_phone;
CREATE INDEX IF NOT EXISTS idx_user_profiles_phone ON perfiles_usuario(phone);
CREATE INDEX IF NOT EXISTS idx_usuarios_person_group ON usuarios(person_group_id);
CREATE INDEX IF NOT EXISTS idx_usuarios_linked_account ON usuarios(linked_account_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_operational_per_person
    ON usuarios(person_group_id) WHERE account_kind='OPERATIONAL';
CREATE UNIQUE INDEX IF NOT EXISTS uq_usuarios_beneficiary_per_person
    ON usuarios(person_group_id) WHERE account_kind='BENEFICIARY';

INSERT INTO versiones_esquema(version, description)
VALUES (75, 'Credicash 7.2.2: Contador crea Administradores/Almacenistas directamente con documentos y acceso Beneficiario vinculado opcional')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 76 / Hotfix PC 08-08-2026: cambios operativos en tiempo real para clientes autenticados.
-- Se emite un único evento por sentencia, sin datos personales en el payload.
CREATE OR REPLACE FUNCTION notify_credicash_operational_changed()
RETURNS TRIGGER AS $$
BEGIN
    PERFORM pg_notify(
        'credicash_operational_changed',
        json_build_object(
            'table', TG_TABLE_NAME,
            'operation', TG_OP,
            'changedAt', clock_timestamp()
        )::TEXT
    );
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    table_name TEXT;
    trigger_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'usuarios','perfiles_usuario','verificaciones_documentos','perfiles_financieros_usuario',
        'productos','jornadas','detalles_pago_jornada','productos_jornada','comunidades','combos','productos_combo',
        'solicitudes_comunidad','items_solicitud_comunidad','pedidos','items_pedido','pagos','facturas','movimientos_inventario',
        'notificaciones','directorio_bancos','solicitudes_verificacion_pago','decisiones_verificacion_pago','combos_pedido',
        'cuentas_credito','prestamos_credito','cuotas_credito','usuarios_credimpulso','transacciones_credimpulso','solicitudes_credito',
        'cuentas_desembolso_credito','carteras_credimpulso_admin','movimientos_cartera_credimpulso','carteras_presupuesto_contador',
        'asignaciones_presupuesto_admin','movimientos_cartera_contador','historial_crediticio_usuarios','eventos_historial_crediticio',
        'transacciones_carteras_continuas','movimientos_presupuestarios','evaluaciones_predictivas','corridas_predictivas_presupuesto',
        'reportes_pago_usuario','conciliaciones_pago','solicitudes_doble_aprobacion','cierres_contables','negocios_asociados'
    ]
    LOOP
        IF to_regclass('public.' || table_name) IS NOT NULL THEN
            trigger_name := 'trg_realtime_' || table_name;
            EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I', trigger_name, table_name);
            EXECUTE format(
                'CREATE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH STATEMENT EXECUTE FUNCTION notify_credicash_operational_changed()',
                trigger_name,
                table_name
            );
        END IF;
    END LOOP;
END;
$$;

INSERT INTO versiones_esquema(version, description)
VALUES (76, 'Hotfix PC 08-08-2026: sincronización operativa autenticada en tiempo real mediante PostgreSQL LISTEN/NOTIFY y SSE')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 77 / Separación estricta de carteras por identidad vinculada.
-- Una persona puede tener acceso ADMIN y BENEFICIARY, pero cada cuenta conserva
-- dirección, saldo e historial propios. Nunca se consolida por person_group_id.
ALTER TABLE carteras_credimpulso_admin
    ADD COLUMN IF NOT EXISTS wallet_scope VARCHAR(30) NOT NULL DEFAULT 'ADMIN_OPERATIONAL';
ALTER TABLE movimientos_cartera_credimpulso
    ADD COLUMN IF NOT EXISTS wallet_scope VARCHAR(30) NOT NULL DEFAULT 'ADMIN_OPERATIONAL';
ALTER TABLE cuentas_credito
    ADD COLUMN IF NOT EXISTS wallet_scope VARCHAR(30) NOT NULL DEFAULT 'BENEFICIARY';
ALTER TABLE transacciones_credimpulso
    ADD COLUMN IF NOT EXISTS wallet_scope VARCHAR(30) NOT NULL DEFAULT 'BENEFICIARY';

-- Repara cualquier despliegue parcial antes de restablecer las restricciones.
UPDATE carteras_credimpulso_admin SET wallet_scope='ADMIN_OPERATIONAL' WHERE wallet_scope IS DISTINCT FROM 'ADMIN_OPERATIONAL';
UPDATE movimientos_cartera_credimpulso SET wallet_scope='ADMIN_OPERATIONAL' WHERE wallet_scope IS DISTINCT FROM 'ADMIN_OPERATIONAL';
UPDATE cuentas_credito SET wallet_scope='BENEFICIARY' WHERE wallet_scope IS DISTINCT FROM 'BENEFICIARY';
UPDATE transacciones_credimpulso SET wallet_scope='BENEFICIARY' WHERE wallet_scope IS DISTINCT FROM 'BENEFICIARY';

ALTER TABLE carteras_credimpulso_admin DROP CONSTRAINT IF EXISTS carteras_admin_wallet_scope_check;
ALTER TABLE carteras_credimpulso_admin ADD CONSTRAINT carteras_admin_wallet_scope_check
    CHECK (wallet_scope='ADMIN_OPERATIONAL');
ALTER TABLE movimientos_cartera_credimpulso DROP CONSTRAINT IF EXISTS movimientos_admin_wallet_scope_check;
ALTER TABLE movimientos_cartera_credimpulso ADD CONSTRAINT movimientos_admin_wallet_scope_check
    CHECK (wallet_scope='ADMIN_OPERATIONAL');
ALTER TABLE cuentas_credito DROP CONSTRAINT IF EXISTS cuentas_credito_wallet_scope_check;
ALTER TABLE cuentas_credito ADD CONSTRAINT cuentas_credito_wallet_scope_check
    CHECK (wallet_scope='BENEFICIARY');
ALTER TABLE transacciones_credimpulso DROP CONSTRAINT IF EXISTS transacciones_credito_wallet_scope_check;
ALTER TABLE transacciones_credimpulso ADD CONSTRAINT transacciones_credito_wallet_scope_check
    CHECK (wallet_scope='BENEFICIARY');

-- Impide que nuevas carteras de crédito se creen sobre la identidad operativa ADMIN.
CREATE OR REPLACE FUNCTION validate_credicash_wallet_identity_scope()
RETURNS TRIGGER AS $$
DECLARE
    target_role TEXT;
    target_kind TEXT;
BEGIN
    IF TG_TABLE_NAME='carteras_credimpulso_admin' THEN
        SELECT UPPER(COALESCE(role,'')), UPPER(COALESCE(account_kind,''))
          INTO target_role,target_kind FROM usuarios WHERE id=NEW.admin_id;
        IF target_role <> 'ADMIN' OR target_kind <> 'OPERATIONAL' THEN
            RAISE EXCEPTION 'La cartera administrativa solo puede pertenecer al acceso operativo ADMIN.';
        END IF;
    ELSIF TG_TABLE_NAME='cuentas_credito' THEN
        SELECT UPPER(COALESCE(role,'')), UPPER(COALESCE(account_kind,''))
          INTO target_role,target_kind FROM usuarios WHERE id=NEW.user_id;
        IF target_role <> 'BENEFICIARY' OR target_kind <> 'BENEFICIARY' THEN
            RAISE EXCEPTION 'La cartera de crédito solo puede pertenecer al acceso BENEFICIARY.';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_wallet_scope_admin_identity ON carteras_credimpulso_admin;
CREATE TRIGGER trg_wallet_scope_admin_identity
BEFORE INSERT OR UPDATE OF admin_id ON carteras_credimpulso_admin
FOR EACH ROW EXECUTE FUNCTION validate_credicash_wallet_identity_scope();

DROP TRIGGER IF EXISTS trg_wallet_scope_beneficiary_identity ON cuentas_credito;
CREATE TRIGGER trg_wallet_scope_beneficiary_identity
BEFORE INSERT OR UPDATE OF user_id ON cuentas_credito
FOR EACH ROW EXECUTE FUNCTION validate_credicash_wallet_identity_scope();

CREATE INDEX IF NOT EXISTS idx_admin_wallet_scope_history
    ON movimientos_cartera_credimpulso(admin_id,wallet_scope,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_beneficiary_wallet_scope_history
    ON transacciones_credimpulso(user_id,wallet_scope,created_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (77, 'Carteras ADMIN y BENEFICIARY separadas por identidad, dirección e historial independiente')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();



-- Migración 78 / Correcciones 3: exportaciones, filtro predictivo y política de mora.
-- La lógica reduce un nivel por cada 15 días completos de mora activa.
-- Predicción excluye perfiles con menos de 30 días de antigüedad y usuarios Nivel 1.
INSERT INTO versiones_esquema(version, description)
VALUES (78, 'Correcciones 3: exportaciones, predicción desde 30 días/nivel >1 y bajada de nivel cada 15 días de mora')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 79 / Correcciones 4: suspensión manual de cuenta por falta de pago.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMPTZ;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500);
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS suspended_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_usuarios_account_suspension ON usuarios(account_status,suspended_at DESC);
INSERT INTO versiones_esquema(version, description)
VALUES (79, 'Correcciones 4: suspensión/reactivación manual de Beneficiarios por falta de pago y pantalla de cuenta suspendida')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 80 / Correcciones 5.4: eliminación segura de cuentas operativas.
-- Los accesos operativos retirados usan BLOCKED; SUSPENDED queda reservado a Beneficiarios.
UPDATE usuarios
SET account_status='BLOCKED', suspended_at=NULL, suspension_reason=NULL, suspended_by=NULL, updated_at=NOW()
WHERE role IN ('ADMIN','WAREHOUSE','ACCOUNTANT') AND account_status='SUSPENDED';

-- Cartera-resumen del Administrador: desaparece junto con la cuenta.
ALTER TABLE carteras_credimpulso_admin DROP CONSTRAINT IF EXISTS carteras_credimpulso_admin_admin_id_fkey;
ALTER TABLE carteras_credimpulso_admin
    ADD CONSTRAINT carteras_credimpulso_admin_admin_id_fkey
    FOREIGN KEY (admin_id) REFERENCES usuarios(id) ON DELETE CASCADE;

-- Movimientos y asignaciones históricas conservan el registro, pero dejan de exigir una cuenta activa.
ALTER TABLE movimientos_cartera_credimpulso ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE movimientos_cartera_credimpulso DROP CONSTRAINT IF EXISTS movimientos_cartera_credimpulso_admin_id_fkey;
ALTER TABLE movimientos_cartera_credimpulso
    ADD CONSTRAINT movimientos_cartera_credimpulso_admin_id_fkey
    FOREIGN KEY (admin_id) REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE asignaciones_presupuesto_admin ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE asignaciones_presupuesto_admin DROP CONSTRAINT IF EXISTS asignaciones_presupuesto_admin_admin_id_fkey;
ALTER TABLE asignaciones_presupuesto_admin
    ADD CONSTRAINT asignaciones_presupuesto_admin_admin_id_fkey
    FOREIGN KEY (admin_id) REFERENCES usuarios(id) ON DELETE SET NULL;

-- Acciones históricas: el hecho se conserva aunque la cuenta del actor se elimine.
ALTER TABLE decisiones_verificacion_pago ALTER COLUMN reviewed_by DROP NOT NULL;
ALTER TABLE decisiones_verificacion_pago DROP CONSTRAINT IF EXISTS decisiones_verificacion_pago_reviewed_by_fkey;
ALTER TABLE decisiones_verificacion_pago
    ADD CONSTRAINT decisiones_verificacion_pago_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE registros_escaneo_qr ALTER COLUMN scanned_by DROP NOT NULL;
ALTER TABLE registros_escaneo_qr DROP CONSTRAINT IF EXISTS registros_escaneo_qr_scanned_by_fkey;
ALTER TABLE registros_escaneo_qr
    ADD CONSTRAINT registros_escaneo_qr_scanned_by_fkey
    FOREIGN KEY (scanned_by) REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE conciliaciones_pago ALTER COLUMN accountant_id DROP NOT NULL;
ALTER TABLE conciliaciones_pago DROP CONSTRAINT IF EXISTS conciliaciones_pago_accountant_id_fkey;
ALTER TABLE conciliaciones_pago
    ADD CONSTRAINT conciliaciones_pago_accountant_id_fkey
    FOREIGN KEY (accountant_id) REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE solicitudes_doble_aprobacion ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE solicitudes_doble_aprobacion DROP CONSTRAINT IF EXISTS solicitudes_doble_aprobacion_requested_by_fkey;
ALTER TABLE solicitudes_doble_aprobacion
    ADD CONSTRAINT solicitudes_doble_aprobacion_requested_by_fkey
    FOREIGN KEY (requested_by) REFERENCES usuarios(id) ON DELETE SET NULL;
ALTER TABLE solicitudes_doble_aprobacion DROP CONSTRAINT IF EXISTS solicitudes_doble_aprobacion_approved_by_fkey;
ALTER TABLE solicitudes_doble_aprobacion
    ADD CONSTRAINT solicitudes_doble_aprobacion_approved_by_fkey
    FOREIGN KEY (approved_by) REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE cierres_contables ALTER COLUMN accountant_id DROP NOT NULL;
ALTER TABLE cierres_contables DROP CONSTRAINT IF EXISTS cierres_contables_accountant_id_fkey;
ALTER TABLE cierres_contables
    ADD CONSTRAINT cierres_contables_accountant_id_fkey
    FOREIGN KEY (accountant_id) REFERENCES usuarios(id) ON DELETE SET NULL;

ALTER TABLE negocios_asociados ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE negocios_asociados DROP CONSTRAINT IF EXISTS negocios_asociados_created_by_fkey;
ALTER TABLE negocios_asociados
    ADD CONSTRAINT negocios_asociados_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES usuarios(id) ON DELETE SET NULL;

-- Historial propio del Contador: se conserva si se elimina un Contador que no sea el último activo.
ALTER TABLE asignaciones_presupuesto_admin ALTER COLUMN contador_id DROP NOT NULL;
ALTER TABLE asignaciones_presupuesto_admin DROP CONSTRAINT IF EXISTS asignaciones_presupuesto_admin_contador_id_fkey;
ALTER TABLE asignaciones_presupuesto_admin
    ADD CONSTRAINT asignaciones_presupuesto_admin_contador_id_fkey
    FOREIGN KEY (contador_id) REFERENCES contadores(user_id) ON DELETE SET NULL;

ALTER TABLE movimientos_cartera_contador ALTER COLUMN contador_id DROP NOT NULL;
ALTER TABLE movimientos_cartera_contador DROP CONSTRAINT IF EXISTS movimientos_cartera_contador_contador_id_fkey;
ALTER TABLE movimientos_cartera_contador
    ADD CONSTRAINT movimientos_cartera_contador_contador_id_fkey
    FOREIGN KEY (contador_id) REFERENCES contadores(user_id) ON DELETE SET NULL;

ALTER TABLE movimientos_presupuestarios ALTER COLUMN contador_id DROP NOT NULL;
ALTER TABLE movimientos_presupuestarios DROP CONSTRAINT IF EXISTS movimientos_presupuestarios_contador_id_fkey;
ALTER TABLE movimientos_presupuestarios
    ADD CONSTRAINT movimientos_presupuestarios_contador_id_fkey
    FOREIGN KEY (contador_id) REFERENCES contadores(user_id) ON DELETE SET NULL;

ALTER TABLE corridas_predictivas_presupuesto ALTER COLUMN contador_id DROP NOT NULL;
ALTER TABLE corridas_predictivas_presupuesto DROP CONSTRAINT IF EXISTS corridas_predictivas_presupuesto_contador_id_fkey;
ALTER TABLE corridas_predictivas_presupuesto
    ADD CONSTRAINT corridas_predictivas_presupuesto_contador_id_fkey
    FOREIGN KEY (contador_id) REFERENCES contadores(user_id) ON DELETE SET NULL;

INSERT INTO versiones_esquema(version, description)
VALUES (80, 'Correcciones 5.4: accesos operativos retirados separados de suspensión y eliminación segura preservando historial')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 81 / Credicash 7.2.5: carga masiva de Beneficiarios, aprobación exclusiva del Contador
-- y carátulas públicas para Jornadas/Combos.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS registration_source VARCHAR(40) NOT NULL DEFAULT 'SELF_REGISTRATION';
CREATE INDEX IF NOT EXISTS idx_usuarios_created_by ON usuarios(created_by, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_usuarios_registration_source ON usuarios(registration_source, created_at DESC);

ALTER TABLE jornadas ADD COLUMN IF NOT EXISTS cover_path TEXT;
ALTER TABLE combos ADD COLUMN IF NOT EXISTS cover_path TEXT;
-- v91: precio comercial propio de combos, separado del precio individual de productos.
ALTER TABLE combos ADD COLUMN IF NOT EXISTS combo_price_usd NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE combos ADD COLUMN IF NOT EXISTS combo_price_bs NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE combos ADD COLUMN IF NOT EXISTS bcv_rate NUMERIC(14,4) NOT NULL DEFAULT 0;
ALTER TABLE combos ADD COLUMN IF NOT EXISTS price_updated_at TIMESTAMPTZ;
INSERT INTO versiones_esquema(version, description) VALUES (91, 'Precio de combo independiente del precio individual de productos')
ON CONFLICT (version) DO NOTHING;

UPDATE usuarios
SET email_verified=TRUE, updated_at=NOW()
WHERE role='BENEFICIARY' AND email_verified=FALSE;

INSERT INTO versiones_esquema(version, description)
VALUES (81, 'Credicash 7.2.5: Beneficiarios por Excel con trazabilidad, aprobación del Contador y carátulas de Jornadas/Combos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 82 / Credicash 7.2.6: costos claros por grupo y categoría para el Contador.
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS categoria_gasto VARCHAR(60);
ALTER TABLE movimientos_presupuestarios DROP CONSTRAINT IF EXISTS movimientos_presupuestarios_tipo_check;
ALTER TABLE movimientos_presupuestarios
    ADD CONSTRAINT movimientos_presupuestarios_tipo_check CHECK (tipo IN (
        'BANK_INCOME','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE','RESERVE','RELEASE',
        'ADJUSTMENT_CREDIT','ADJUSTMENT_DEBIT'
    ));
CREATE INDEX IF NOT EXISTS idx_budget_movements_expense_category
    ON movimientos_presupuestarios(contador_id,tipo,categoria_gasto,created_at DESC);
INSERT INTO versiones_esquema(version, description)
VALUES (82, 'Credicash 7.2.6: registro directo a documentos, revisión por Administrador/Contador y costos operativos/administrativos categorizados')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 83 / Credicash 1.1.0: planificación y control presupuestario por estructura de costo.
-- El catálogo es global y estable; los centros, periodos y partidas pertenecen a cada Contador.
CREATE TABLE IF NOT EXISTS catalogo_costos (
    id BIGSERIAL PRIMARY KEY,
    codigo VARCHAR(50) NOT NULL UNIQUE,
    parent_codigo VARCHAR(50) REFERENCES catalogo_costos(codigo) ON DELETE RESTRICT,
    nombre VARCHAR(160) NOT NULL,
    grupo VARCHAR(30) NOT NULL CHECK (grupo IN (
        'INVENTORY','OPERATING','ADMINISTRATIVE','COMMERCIAL','FINANCIAL','EXTRAORDINARY'
    )),
    nivel INTEGER NOT NULL CHECK (nivel BETWEEN 1 AND 3),
    permite_movimientos BOOLEAN NOT NULL DEFAULT FALSE,
    requiere_comprobante BOOLEAN NOT NULL DEFAULT TRUE,
    requiere_aprobacion BOOLEAN NOT NULL DEFAULT FALSE,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((nivel=1 AND parent_codigo IS NULL) OR (nivel>1 AND parent_codigo IS NOT NULL))
);

INSERT INTO catalogo_costos(
    codigo,parent_codigo,nombre,grupo,nivel,permite_movimientos,requiere_comprobante,requiere_aprobacion
) VALUES
    ('INVENTORY',NULL,'Costos de mercancía e inventario','INVENTORY',1,FALSE,TRUE,FALSE),
    ('INV-PURCHASES','INVENTORY','Adquisición y manejo de mercancía','INVENTORY',2,FALSE,TRUE,FALSE),
    ('INV-PRODUCTS','INV-PURCHASES','Compra de productos','INVENTORY',3,TRUE,TRUE,FALSE),
    ('INV-FREIGHT','INV-PURCHASES','Flete de adquisición','INVENTORY',3,TRUE,TRUE,FALSE),
    ('INV-INSURANCE','INV-PURCHASES','Seguro de mercancía','INVENTORY',3,TRUE,TRUE,FALSE),
    ('INV-TAXES','INV-PURCHASES','Impuestos de compra','INVENTORY',3,TRUE,TRUE,FALSE),
    ('INV-IMPORT','INV-PURCHASES','Costos de importación','INVENTORY',3,TRUE,TRUE,FALSE),
    ('INV-INITIAL-TRANSFER','INV-PURCHASES','Traslado inicial al almacén','INVENTORY',3,TRUE,TRUE,FALSE),
    ('INV-SHRINKAGE','INV-PURCHASES','Mermas y productos dañados','INVENTORY',3,TRUE,TRUE,TRUE),
    ('INV-ADJUSTMENTS','INV-PURCHASES','Ajustes de inventario','INVENTORY',3,TRUE,TRUE,TRUE),

    ('OPERATING',NULL,'Gastos operativos','OPERATING',1,FALSE,TRUE,FALSE),
    ('OP-TRANSPORT','OPERATING','Transporte y logística','OPERATING',2,FALSE,TRUE,FALSE),
    ('OP-FUEL','OP-TRANSPORT','Combustible','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-VEHICLE-RENT','OP-TRANSPORT','Alquiler de vehículos','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-VEHICLE-MAINT','OP-TRANSPORT','Mantenimiento de vehículos','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-FREIGHT','OP-TRANSPORT','Fletes','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-TOLLS','OP-TRANSPORT','Peajes','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-PARKING','OP-TRANSPORT','Estacionamiento','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-LOAD','OP-TRANSPORT','Carga y descarga','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-INTERNAL-TRANSFER','OP-TRANSPORT','Traslado entre almacenes','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-STAFF-TRANSPORT','OP-TRANSPORT','Transporte de personal','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-DELIVERY','OP-TRANSPORT','Delivery de última milla','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-FIELD','OPERATING','Viáticos y operaciones de campo','OPERATING',2,FALSE,TRUE,FALSE),
    ('OP-MEALS','OP-FIELD','Alimentación','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-LODGING','OP-FIELD','Hospedaje','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-PER-DIEM','OP-FIELD','Viáticos diarios','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-JOURNEY-MATERIAL','OP-FIELD','Materiales de jornada','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-ASSEMBLY','OP-FIELD','Montaje y desmontaje','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-TEMP-STAFF','OP-FIELD','Personal temporal','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-EVENT-SECURITY','OP-FIELD','Seguridad del evento','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-WAREHOUSE','OPERATING','Almacén','OPERATING',2,FALSE,TRUE,FALSE),
    ('OP-WAREHOUSE-RENT','OP-WAREHOUSE','Alquiler del almacén','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-WAREHOUSE-UTILITIES','OP-WAREHOUSE','Servicios del almacén','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-STORAGE-EQUIPMENT','OP-WAREHOUSE','Equipos de almacenamiento','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-WAREHOUSE-CLEANING','OP-WAREHOUSE','Limpieza del almacén','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-PEST-CONTROL','OP-WAREHOUSE','Fumigación','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-WAREHOUSE-MAINT','OP-WAREHOUSE','Mantenimiento del almacén','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-HANDLING-MATERIAL','OP-WAREHOUSE','Material de manipulación','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-DISPATCH','OPERATING','Empaque y despacho','OPERATING',2,FALSE,TRUE,FALSE),
    ('OP-BAGS','OP-DISPATCH','Bolsas','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-BOXES','OP-DISPATCH','Cajas','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-TAPE','OP-DISPATCH','Cintas','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-LABELS','OP-DISPATCH','Etiquetas','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-PROTECTION','OP-DISPATCH','Material protector','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-WAYBILLS','OP-DISPATCH','Impresión de guías','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-SHIPPING','OP-DISPATCH','Envíos nacionales','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-MESSENGER','OP-DISPATCH','Mensajería','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-RETURNS','OP-DISPATCH','Devoluciones','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-REPACK','OP-DISPATCH','Reempaque','OPERATING',3,TRUE,TRUE,FALSE),
    ('OP-OTHER','OPERATING','Otros gastos operativos','OPERATING',3,TRUE,TRUE,TRUE),

    ('ADMINISTRATIVE',NULL,'Gastos administrativos','ADMINISTRATIVE',1,FALSE,TRUE,FALSE),
    ('ADMIN-PERSONNEL','ADMINISTRATIVE','Personal administrativo','ADMINISTRATIVE',2,FALSE,TRUE,FALSE),
    ('ADMIN-SALARIES','ADMIN-PERSONNEL','Sueldos','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-FEES','ADMIN-PERSONNEL','Honorarios profesionales','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-BONUSES','ADMIN-PERSONNEL','Bonificaciones','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-BENEFITS','ADMIN-PERSONNEL','Beneficios laborales','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-PAYROLL-TAXES','ADMIN-PERSONNEL','Aportes laborales','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-VACATIONS','ADMIN-PERSONNEL','Vacaciones','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-TRAINING','ADMIN-PERSONNEL','Capacitación','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-UNIFORMS','ADMIN-PERSONNEL','Uniformes','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-OFFICE','ADMINISTRATIVE','Oficina','ADMINISTRATIVE',2,FALSE,TRUE,FALSE),
    ('ADMIN-OFFICE-RENT','ADMIN-OFFICE','Alquiler de oficina','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-ELECTRICITY','ADMIN-OFFICE','Electricidad','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-WATER','ADMIN-OFFICE','Agua','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-INTERNET','ADMIN-OFFICE','Internet','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-PHONE','ADMIN-OFFICE','Telefonía','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-STATIONERY','ADMIN-OFFICE','Papelería','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-CLEANING','ADMIN-OFFICE','Limpieza','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-SECURITY','ADMIN-OFFICE','Seguridad','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-FURNITURE','ADMIN-OFFICE','Mobiliario','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-OFFICE-MAINT','ADMIN-OFFICE','Mantenimiento de oficina','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-TECH','ADMINISTRATIVE','Tecnología','ADMINISTRATIVE',2,FALSE,TRUE,FALSE),
    ('ADMIN-HOSTING','ADMIN-TECH','Alojamiento y Railway','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-DOMAINS','ADMIN-TECH','Dominios','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-SOFTWARE','ADMIN-TECH','Software y suscripciones','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-IT-EQUIPMENT','ADMIN-TECH','Equipos informáticos','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-IT-MAINT','ADMIN-TECH','Mantenimiento técnico','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-LICENSES','ADMIN-TECH','Licencias','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-BACKUPS','ADMIN-TECH','Copias de seguridad','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-MESSAGING','ADMIN-TECH','Servicios de mensajería o correo','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-PROFESSIONAL','ADMINISTRATIVE','Servicios profesionales','ADMINISTRATIVE',2,FALSE,TRUE,FALSE),
    ('ADMIN-ACCOUNTING','ADMIN-PROFESSIONAL','Contabilidad','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-LEGAL','ADMIN-PROFESSIONAL','Asesoría legal','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-AUDIT','ADMIN-PROFESSIONAL','Auditoría','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-CONSULTING','ADMIN-PROFESSIONAL','Consultoría','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-NOTARY','ADMIN-PROFESSIONAL','Notaría','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-DESIGN','ADMIN-PROFESSIONAL','Diseño profesional','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-TECH-SERVICES','ADMIN-PROFESSIONAL','Servicios técnicos','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-COMPLIANCE','ADMINISTRATIVE','Tributos y cumplimiento','ADMINISTRATIVE',2,FALSE,TRUE,FALSE),
    ('ADMIN-TAXES','ADMIN-COMPLIANCE','Impuestos','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-RATES','ADMIN-COMPLIANCE','Tasas','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-PERMITS','ADMIN-COMPLIANCE','Permisos','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-REGISTRATIONS','ADMIN-COMPLIANCE','Registros','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-FINES','ADMIN-COMPLIANCE','Multas','ADMINISTRATIVE',3,TRUE,TRUE,TRUE),
    ('ADMIN-RENEWALS','ADMIN-COMPLIANCE','Renovaciones','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-INSURANCE','ADMIN-COMPLIANCE','Seguros','ADMINISTRATIVE',3,TRUE,TRUE,FALSE),
    ('ADMIN-OTHER','ADMINISTRATIVE','Otros gastos administrativos','ADMINISTRATIVE',3,TRUE,TRUE,TRUE),

    ('COMMERCIAL',NULL,'Gastos comerciales y marketing','COMMERCIAL',1,FALSE,TRUE,FALSE),
    ('COMM-MARKETING','COMMERCIAL','Marketing y ventas','COMMERCIAL',2,FALSE,TRUE,FALSE),
    ('COMM-ADVERTISING','COMM-MARKETING','Publicidad digital','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-SOCIAL','COMM-MARKETING','Redes sociales','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-DESIGN','COMM-MARKETING','Diseño gráfico','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-PRINT','COMM-MARKETING','Impresión publicitaria','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-PROMOTIONS','COMM-MARKETING','Promociones','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-POP','COMM-MARKETING','Material POP','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-COMMISSIONS','COMM-MARKETING','Comisiones de venta','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-CUSTOMER-SERVICE','COMM-MARKETING','Atención al cliente','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-ACQUISITION','COMM-MARKETING','Captación de beneficiarios','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-EVENTS','COMM-MARKETING','Eventos promocionales','COMMERCIAL',3,TRUE,TRUE,FALSE),
    ('COMM-SPONSORSHIP','COMM-MARKETING','Patrocinios','COMMERCIAL',3,TRUE,TRUE,TRUE),
    ('COMM-OTHER','COMMERCIAL','Otros gastos comerciales','COMMERCIAL',3,TRUE,TRUE,TRUE),

    ('FINANCIAL',NULL,'Gastos financieros','FINANCIAL',1,FALSE,TRUE,FALSE),
    ('FIN-SERVICES','FINANCIAL','Servicios y costos financieros','FINANCIAL',2,FALSE,TRUE,FALSE),
    ('FIN-BANK-FEES','FIN-SERVICES','Comisiones bancarias','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-INTEREST','FIN-SERVICES','Intereses','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-FX-DIFFERENCE','FIN-SERVICES','Diferencias cambiarias','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-TRANSFER','FIN-SERVICES','Costos de transferencias','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-PAYMENT-GATEWAY','FIN-SERVICES','Pasarelas de pago','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-COLLECTION','FIN-SERVICES','Comisiones por cobro','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-ROUNDING','FIN-SERVICES','Pérdidas por redondeo','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-RECONCILIATION','FIN-SERVICES','Gastos de conciliación','FINANCIAL',3,TRUE,TRUE,FALSE),
    ('FIN-OTHER','FINANCIAL','Otros gastos financieros','FINANCIAL',3,TRUE,TRUE,TRUE),

    ('EXTRAORDINARY',NULL,'Gastos extraordinarios','EXTRAORDINARY',1,FALSE,TRUE,TRUE),
    ('EXT-EVENTS','EXTRAORDINARY','Eventos extraordinarios','EXTRAORDINARY',2,FALSE,TRUE,TRUE),
    ('EXT-EMERGENCY','EXT-EVENTS','Emergencias','EXTRAORDINARY',3,TRUE,TRUE,TRUE),
    ('EXT-LOSS','EXT-EVENTS','Daños o pérdidas','EXTRAORDINARY',3,TRUE,TRUE,TRUE),
    ('EXT-LEGAL','EXT-EVENTS','Gastos legales excepcionales','EXTRAORDINARY',3,TRUE,TRUE,TRUE),
    ('EXT-CLAIMS','EXT-EVENTS','Siniestros','EXTRAORDINARY',3,TRUE,TRUE,TRUE),
    ('EXT-AUTHORIZED-ADJUSTMENT','EXT-EVENTS','Ajustes autorizados','EXTRAORDINARY',3,TRUE,TRUE,TRUE)
ON CONFLICT(codigo) DO UPDATE SET
    parent_codigo=EXCLUDED.parent_codigo,
    nombre=EXCLUDED.nombre,
    grupo=EXCLUDED.grupo,
    nivel=EXCLUDED.nivel,
    permite_movimientos=EXCLUDED.permite_movimientos,
    requiere_comprobante=EXCLUDED.requiere_comprobante,
    requiere_aprobacion=EXCLUDED.requiere_aprobacion,
    updated_at=NOW();

CREATE TABLE IF NOT EXISTS centros_costo (
    id BIGSERIAL PRIMARY KEY,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    codigo VARCHAR(40) NOT NULL,
    nombre VARCHAR(160) NOT NULL,
    tipo VARCHAR(30) NOT NULL CHECK (tipo IN (
        'CENTRAL','WAREHOUSE','LOGISTICS','MARKETING','TECHNOLOGY','JOURNEY',
        'BRANCH','ADMINISTRATOR','PROJECT','OTHER'
    )),
    parent_id BIGINT REFERENCES centros_costo(id) ON DELETE RESTRICT,
    responsable_usuario_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(contador_id,codigo)
);

CREATE TABLE IF NOT EXISTS periodos_presupuestarios (
    id BIGSERIAL PRIMARY KEY,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    codigo VARCHAR(30) NOT NULL,
    nombre VARCHAR(160) NOT NULL,
    fecha_inicio DATE NOT NULL,
    fecha_fin DATE NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (estado IN ('DRAFT','SUBMITTED','APPROVED','ACTIVE','CLOSED','CANCELLED')),
    moneda VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (moneda IN ('USD','VES')),
    created_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    approved_by BIGINT REFERENCES usuarios(id) ON DELETE RESTRICT,
    approved_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (fecha_fin >= fecha_inicio),
    UNIQUE(contador_id,codigo)
);

CREATE TABLE IF NOT EXISTS partidas_presupuestarias (
    id BIGSERIAL PRIMARY KEY,
    periodo_id BIGINT NOT NULL REFERENCES periodos_presupuestarios(id) ON DELETE RESTRICT,
    categoria_costo_id BIGINT NOT NULL REFERENCES catalogo_costos(id) ON DELETE RESTRICT,
    centro_costo_id BIGINT NOT NULL REFERENCES centros_costo(id) ON DELETE RESTRICT,
    monto_aprobado_usd NUMERIC(18,2) NOT NULL CHECK (monto_aprobado_usd >= 0),
    monto_modificado_usd NUMERIC(18,2) NOT NULL DEFAULT 0,
    estado VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (estado IN ('ACTIVE','FROZEN','CLOSED','CANCELLED')),
    notas VARCHAR(500),
    created_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(periodo_id,categoria_costo_id,centro_costo_id)
);

CREATE TABLE IF NOT EXISTS compromisos_presupuestarios (
    id BIGSERIAL PRIMARY KEY,
    referencia VARCHAR(120) NOT NULL UNIQUE,
    partida_id BIGINT NOT NULL REFERENCES partidas_presupuestarias(id) ON DELETE RESTRICT,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd > 0),
    descripcion VARCHAR(500) NOT NULL,
    proveedor VARCHAR(220),
    numero_factura VARCHAR(120),
    fecha_pago_esperada DATE,
    estado VARCHAR(20) NOT NULL DEFAULT 'COMMITTED'
        CHECK (estado IN ('PENDING','APPROVED','COMMITTED','PAID','CANCELLED','REVERSED')),
    movimiento_gasto_id BIGINT REFERENCES movimientos_presupuestarios(id) ON DELETE RESTRICT,
    idempotency_key VARCHAR(120) UNIQUE,
    motivo_estado VARCHAR(500),
    created_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ajustes_presupuestarios (
    id BIGSERIAL PRIMARY KEY,
    referencia VARCHAR(120) NOT NULL UNIQUE,
    contador_id BIGINT NOT NULL REFERENCES contadores(user_id) ON DELETE RESTRICT,
    tipo VARCHAR(20) NOT NULL CHECK (tipo IN ('INCREASE','REDUCTION','TRANSFER')),
    partida_origen_id BIGINT REFERENCES partidas_presupuestarias(id) ON DELETE RESTRICT,
    partida_destino_id BIGINT REFERENCES partidas_presupuestarias(id) ON DELETE RESTRICT,
    monto_usd NUMERIC(18,2) NOT NULL CHECK (monto_usd > 0),
    motivo VARCHAR(500) NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'COMPLETED' CHECK (estado IN ('PENDING','COMPLETED','REJECTED','REVERSED')),
    idempotency_key VARCHAR(120) UNIQUE,
    created_by BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        (tipo='INCREASE' AND partida_origen_id IS NULL AND partida_destino_id IS NOT NULL)
        OR (tipo='REDUCTION' AND partida_origen_id IS NOT NULL AND partida_destino_id IS NULL)
        OR (tipo='TRANSFER' AND partida_origen_id IS NOT NULL AND partida_destino_id IS NOT NULL AND partida_origen_id<>partida_destino_id)
    )
);

ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS categoria_costo_id BIGINT REFERENCES catalogo_costos(id) ON DELETE RESTRICT;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS centro_costo_id BIGINT REFERENCES centros_costo(id) ON DELETE RESTRICT;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS periodo_presupuestario_id BIGINT REFERENCES periodos_presupuestarios(id) ON DELETE RESTRICT;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS partida_presupuestaria_id BIGINT REFERENCES partidas_presupuestarias(id) ON DELETE RESTRICT;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS compromiso_id BIGINT REFERENCES compromisos_presupuestarios(id) ON DELETE RESTRICT;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS responsable_usuario_id BIGINT REFERENCES usuarios(id) ON DELETE SET NULL;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS proveedor VARCHAR(220);
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS numero_factura VARCHAR(120);
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS fecha_operacion DATE NOT NULL DEFAULT CURRENT_DATE;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS metodo_pago VARCHAR(40);
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS comportamiento_costo VARCHAR(10) CHECK (comportamiento_costo IS NULL OR comportamiento_costo IN ('FIXED','VARIABLE'));
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS recurrencia VARCHAR(20) CHECK (recurrencia IS NULL OR recurrencia IN ('ONE_TIME','WEEKLY','MONTHLY','QUARTERLY','ANNUAL'));
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS referencia_proyecto VARCHAR(120);
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS comprobante_path TEXT;
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS moneda_original VARCHAR(3) NOT NULL DEFAULT 'USD' CHECK (moneda_original IN ('USD','VES'));
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS monto_original NUMERIC(20,2);
ALTER TABLE movimientos_presupuestarios ADD COLUMN IF NOT EXISTS estado_control_presupuesto VARCHAR(30) NOT NULL DEFAULT 'LEGACY'
    CHECK (estado_control_presupuesto IN ('LEGACY','UNBUDGETED','WITHIN_BUDGET','OVER_BUDGET','COMMITMENT_SETTLED'));

ALTER TABLE movimientos_presupuestarios DROP CONSTRAINT IF EXISTS movimientos_presupuestarios_tipo_check;
ALTER TABLE movimientos_presupuestarios
    ADD CONSTRAINT movimientos_presupuestarios_tipo_check CHECK (tipo IN (
        'BANK_INCOME','INVENTORY_COST','OPERATING_EXPENSE','ADMINISTRATIVE_EXPENSE',
        'COMMERCIAL_EXPENSE','FINANCIAL_EXPENSE','EXTRAORDINARY_EXPENSE',
        'RESERVE','RELEASE','ADJUSTMENT_CREDIT','ADJUSTMENT_DEBIT'
    ));

UPDATE movimientos_presupuestarios m
SET categoria_costo_id=c.id
FROM catalogo_costos c
WHERE m.categoria_costo_id IS NULL
  AND c.codigo=CASE m.categoria_gasto
      WHEN 'TRANSPORTATION' THEN 'OP-STAFF-TRANSPORT'
      WHEN 'LOGISTICS' THEN 'OP-FREIGHT'
      WHEN 'PER_DIEM' THEN 'OP-PER-DIEM'
      WHEN 'DELIVERY' THEN 'OP-DELIVERY'
      WHEN 'FUEL' THEN 'OP-FUEL'
      WHEN 'LOADING_UNLOADING' THEN 'OP-LOAD'
      WHEN 'OPERATIONAL_MAINTENANCE' THEN 'OP-VEHICLE-MAINT'
      WHEN 'OTHER_OPERATING' THEN 'OP-OTHER'
      WHEN 'PERSONNEL' THEN 'ADMIN-SALARIES'
      WHEN 'BAGS' THEN 'OP-BAGS'
      WHEN 'MARKETING' THEN 'COMM-ADVERTISING'
      WHEN 'SHIPPING' THEN 'OP-SHIPPING'
      WHEN 'PACKAGING' THEN 'OP-PROTECTION'
      WHEN 'OFFICE_SUPPLIES' THEN 'ADMIN-STATIONERY'
      WHEN 'ADMIN_SERVICES' THEN 'ADMIN-CONSULTING'
      WHEN 'BANK_FEES' THEN 'FIN-BANK-FEES'
      WHEN 'SOFTWARE_SUBSCRIPTIONS' THEN 'ADMIN-SOFTWARE'
      WHEN 'OTHER_ADMINISTRATIVE' THEN 'ADMIN-OTHER'
      ELSE NULL
  END;

CREATE INDEX IF NOT EXISTS idx_cost_catalog_parent ON catalogo_costos(parent_codigo,nivel,activo);
CREATE INDEX IF NOT EXISTS idx_cost_centers_accountant ON centros_costo(contador_id,activo,tipo);
CREATE INDEX IF NOT EXISTS idx_budget_periods_accountant ON periodos_presupuestarios(contador_id,fecha_inicio DESC);
CREATE INDEX IF NOT EXISTS idx_budget_lines_period ON partidas_presupuestarias(periodo_id,estado);
CREATE INDEX IF NOT EXISTS idx_budget_commitments_line ON compromisos_presupuestarios(partida_id,estado,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_budget_movements_planning ON movimientos_presupuestarios(periodo_presupuestario_id,partida_presupuestaria_id,estado,created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_budget_movement_commitment
    ON movimientos_presupuestarios(compromiso_id) WHERE compromiso_id IS NOT NULL AND estado='COMPLETED';

INSERT INTO versiones_esquema(version, description)
VALUES (83, 'Credicash 1.1.0: catálogo jerárquico de costos, centros de costo, periodos, partidas, compromisos y control presupuestario')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 84 / Robustez de eliminación de cuentas sin historial.
-- Las filas de estado recreable no deben impedir borrar una cuenta vacía. La historia real
-- (pedidos, pagos, créditos, conciliaciones, auditoría, etc.) continúa protegida por RESTRICT.
-- Se eliminan las FK por metadatos en vez de asumir nombres de constraint; esto soporta bases
-- antiguas cuyas tablas fueron renombradas desde los nombres ingleses y conservaron la FK vieja.
DO $$
DECLARE fk_name TEXT;
BEGIN
    FOR fk_name IN
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name=kcu.constraint_name AND tc.constraint_schema=kcu.constraint_schema
        JOIN information_schema.referential_constraints rc
          ON tc.constraint_name=rc.constraint_name AND tc.constraint_schema=rc.constraint_schema
        JOIN information_schema.constraint_column_usage ccu
          ON rc.unique_constraint_name=ccu.constraint_name AND rc.unique_constraint_schema=ccu.constraint_schema
        WHERE tc.constraint_type='FOREIGN KEY' AND tc.table_schema='public'
          AND tc.table_name='cuentas_credito' AND kcu.column_name='user_id'
          AND ccu.table_name='usuarios' AND ccu.column_name='id'
    LOOP
        EXECUTE format('ALTER TABLE cuentas_credito DROP CONSTRAINT %I', fk_name);
    END LOOP;
END $$;
ALTER TABLE cuentas_credito
    ADD CONSTRAINT cuentas_credito_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES usuarios(id) ON DELETE CASCADE;

DO $$
DECLARE fk_name TEXT;
BEGIN
    FOR fk_name IN
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name=kcu.constraint_name AND tc.constraint_schema=kcu.constraint_schema
        JOIN information_schema.referential_constraints rc
          ON tc.constraint_name=rc.constraint_name AND tc.constraint_schema=rc.constraint_schema
        JOIN information_schema.constraint_column_usage ccu
          ON rc.unique_constraint_name=ccu.constraint_name AND rc.unique_constraint_schema=ccu.constraint_schema
        WHERE tc.constraint_type='FOREIGN KEY' AND tc.table_schema='public'
          AND tc.table_name='usuarios_credimpulso' AND kcu.column_name='user_id'
          AND ccu.table_name='usuarios' AND ccu.column_name='id'
    LOOP
        EXECUTE format('ALTER TABLE usuarios_credimpulso DROP CONSTRAINT %I', fk_name);
    END LOOP;
END $$;
ALTER TABLE usuarios_credimpulso
    ADD CONSTRAINT usuarios_credimpulso_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES usuarios(id) ON DELETE CASCADE;

DO $$
DECLARE fk_name TEXT;
BEGIN
    FOR fk_name IN
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name=kcu.constraint_name AND tc.constraint_schema=kcu.constraint_schema
        JOIN information_schema.referential_constraints rc
          ON tc.constraint_name=rc.constraint_name AND tc.constraint_schema=rc.constraint_schema
        JOIN information_schema.constraint_column_usage ccu
          ON rc.unique_constraint_name=ccu.constraint_name AND rc.unique_constraint_schema=ccu.constraint_schema
        WHERE tc.constraint_type='FOREIGN KEY' AND tc.table_schema='public'
          AND tc.table_name='usuarios' AND kcu.column_name='suspended_by'
          AND ccu.table_name='usuarios' AND ccu.column_name='id'
    LOOP
        EXECUTE format('ALTER TABLE usuarios DROP CONSTRAINT %I', fk_name);
    END LOOP;
END $$;
ALTER TABLE usuarios
    ADD CONSTRAINT usuarios_suspended_by_fkey
    FOREIGN KEY (suspended_by) REFERENCES usuarios(id) ON DELETE SET NULL;

INSERT INTO versiones_esquema(version, description)
VALUES (84, 'Robustez de eliminación: estado recreable en cascada, historial financiero protegido y suspensión con SET NULL')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

INSERT INTO versiones_esquema(version, description)
VALUES (85, 'Carrusel de banners del inicio con orden, vigencia y asociación opcional a jornadas')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

INSERT INTO versiones_esquema(version, description)
VALUES (86, 'Encuestas de interés por banners y satisfacción posterior a compras')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();



-- Cambios 88: encuesta rápida de experiencia de la aplicación.
CREATE TABLE IF NOT EXISTS encuestas_experiencia_app (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    registration_stars SMALLINT NOT NULL CHECK (registration_stars BETWEEN 1 AND 5),
    navigation_stars SMALLINT NOT NULL CHECK (navigation_stars BETWEEN 1 AND 5),
    payments_clear BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id)
);
CREATE INDEX IF NOT EXISTS idx_app_experience_survey_created ON encuestas_experiencia_app(created_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (88, 'Encuesta rápida de experiencia de la app: registro, navegación y claridad de pagos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Cambios 89: promociones y descuentos con trazabilidad histórica.
CREATE TABLE IF NOT EXISTS promociones_descuentos (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(180) NOT NULL,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('PRODUCT','COMBO','FAIR')),
    target_id BIGINT NOT NULL,
    discount_type VARCHAR(20) NOT NULL CHECK (discount_type IN ('PERCENT','FIXED_USD')),
    discount_value NUMERIC(14,4) NOT NULL CHECK (discount_value > 0),
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    max_uses INTEGER CHECK (max_uses IS NULL OR max_uses > 0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (discount_type <> 'PERCENT' OR discount_value <= 100),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at >= starts_at)
);
CREATE INDEX IF NOT EXISTS idx_promotions_active_dates ON promociones_descuentos(active, starts_at, ends_at);
CREATE INDEX IF NOT EXISTS idx_promotions_target ON promociones_descuentos(target_type, target_id);

ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS promotion_id BIGINT REFERENCES promociones_descuentos(id) ON DELETE SET NULL;
ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS promotion_name_snapshot VARCHAR(180);
ALTER TABLE pedidos ADD COLUMN IF NOT EXISTS original_subtotal NUMERIC(14,2);
UPDATE pedidos SET original_subtotal = COALESCE(original_subtotal, subtotal) WHERE original_subtotal IS NULL;
CREATE INDEX IF NOT EXISTS idx_orders_promotion ON pedidos(promotion_id) WHERE promotion_id IS NOT NULL;

INSERT INTO versiones_esquema(version, description)
VALUES (89, 'Promociones y descuentos con cálculo seguro en backend y trazabilidad en pedidos')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();




-- Migración 92 / Kredi+ 7.2.39: destinos de pago independientes para productos y combos.
CREATE TABLE IF NOT EXISTS configuracion_ventas_catalogo (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    product_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE SET NULL,
    combo_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO configuracion_ventas_catalogo(id) VALUES (1) ON CONFLICT(id) DO NOTHING;
INSERT INTO versiones_esquema(version, description)
VALUES (92, 'Kredi+ 7.2.39: asociación explícita de negocio para ventas individuales y combos; pago deshabilitado sin destino')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 93 / Kredi+ 1.2.0: compras presenciales por QR, sin billetera ni pasarela.
CREATE TABLE IF NOT EXISTS terminales_qr_negocio (
    id BIGSERIAL PRIMARY KEY,
    business_id BIGINT NOT NULL REFERENCES negocios_asociados(id) ON DELETE RESTRICT,
    branch_name VARCHAR(180) NOT NULL,
    terminal_name VARCHAR(120) NOT NULL,
    public_code VARCHAR(96) NOT NULL UNIQUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_terminales_qr_negocio_business ON terminales_qr_negocio(business_id,active);
CREATE UNIQUE INDEX IF NOT EXISTS uq_terminales_qr_negocio_nombre ON terminales_qr_negocio(business_id,LOWER(branch_name),LOWER(terminal_name));

CREATE TABLE IF NOT EXISTS sesiones_compra_qr (
    id BIGSERIAL PRIMARY KEY,
    terminal_id BIGINT NOT NULL REFERENCES terminales_qr_negocio(id) ON DELETE RESTRICT,
    business_id BIGINT NOT NULL REFERENCES negocios_asociados(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    status VARCHAR(40) NOT NULL DEFAULT 'AWAITING_MERCHANT'
        CHECK (status IN ('AWAITING_MERCHANT','AWAITING_CUSTOMER','AWAITING_INITIAL','COMPLETED','CANCELLED','EXPIRED')),
    total_usd NUMERIC(12,2),
    invoice_reference VARCHAR(160),
    level_snapshot INTEGER,
    level_name_snapshot VARCHAR(120),
    available_line_snapshot NUMERIC(12,2),
    initial_percent NUMERIC(7,2),
    initial_usd NUMERIC(12,2),
    financed_usd NUMERIC(12,2),
    installment_count INTEGER,
    installment_usd NUMERIC(12,2),
    loan_id BIGINT REFERENCES prestamos_credito(id) ON DELETE SET NULL,
    customer_confirmed_at TIMESTAMPTZ,
    initial_received_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sesiones_compra_qr_terminal_status ON sesiones_compra_qr(terminal_id,status,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sesiones_compra_qr_user_status ON sesiones_compra_qr(user_id,status,created_at DESC);

ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_lender_type_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_lender_type_check
    CHECK (lender_type IN ('ADMIN_WALLET','ASSOCIATED_BUSINESS','MERCHANT_PURCHASE'));
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_lender_business_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_lender_business_check CHECK (
    (lender_type='ADMIN_WALLET' AND lender_business_id IS NULL)
    OR (lender_type IN ('ASSOCIATED_BUSINESS','MERCHANT_PURCHASE') AND lender_business_id IS NOT NULL)
) NOT VALID;
ALTER TABLE prestamos_credito DROP CONSTRAINT IF EXISTS prestamos_credito_disbursement_destination_check;
ALTER TABLE prestamos_credito ADD CONSTRAINT prestamos_credito_disbursement_destination_check
    CHECK (disbursement_destination_type IN ('CREDICASH_WALLET','BANK_ACCOUNT','IN_STORE_PURCHASE'));

INSERT INTO versiones_esquema(version, description)
VALUES (93, 'Kredi+ 1.2.0: terminales QR por negocio y compra presencial con inicial directa al comercio')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


-- Migración 94 / Kredi+ 1.2.2: edición de perfil y cambio de correo autenticado.
ALTER TABLE codigos_verificacion DROP CONSTRAINT IF EXISTS verification_codes_purpose_check;
ALTER TABLE codigos_verificacion DROP CONSTRAINT IF EXISTS codigos_verificacion_purpose_check;
ALTER TABLE codigos_verificacion
    ADD CONSTRAINT codigos_verificacion_purpose_check
    CHECK (purpose IN (
        'EMAIL_VERIFICATION','ACCOUNT_VERIFICATION','PASSWORD_RESET',
        'PHONE_VERIFICATION','LOGIN','CRITICAL_ACTION','EMAIL_CHANGE'
    ));

INSERT INTO versiones_esquema(version, description)
VALUES (94, 'Kredi+ 1.2.2: perfil editable por pestañas, ubicación independiente y cambio de correo verificado')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();


ALTER TABLE productos_jornada ADD COLUMN IF NOT EXISTS quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0);

-- Migración 96 / Kredi+ 1.3.0: QR de Jornada/Combo/Oferta y eliminación lógica de cuentas.
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS deleted_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL;
ALTER TABLE usuarios DROP CONSTRAINT IF EXISTS usuarios_account_status_check;
ALTER TABLE usuarios ADD CONSTRAINT usuarios_account_status_check
    CHECK (account_status IN ('PENDING_VERIFICATION','ACTIVE','REJECTED','SUSPENDED','BLOCKED','DELETED'));

CREATE TABLE IF NOT EXISTS ofertas_qr_comerciales (
    id BIGSERIAL PRIMARY KEY,
    offer_type VARCHAR(24) NOT NULL CHECK (offer_type IN ('JOURNEY','COMBO','OFFER')),
    source_id BIGINT,
    business_id BIGINT NOT NULL REFERENCES negocios_asociados(id) ON DELETE RESTRICT,
    name VARCHAR(220) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    price_usd NUMERIC(12,2) NOT NULL CHECK (price_usd > 0),
    cover_path TEXT,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','SCHEDULED','ACTIVE','SOLD_OUT','FINISHED','DISABLED')),
    stock_limit INTEGER CHECK (stock_limit IS NULL OR stock_limit > 0),
    sold_count INTEGER NOT NULL DEFAULT 0 CHECK (sold_count >= 0),
    one_purchase_per_user BOOLEAN NOT NULL DEFAULT TRUE,
    starts_at TIMESTAMPTZ,
    ends_at TIMESTAMPTZ,
    public_code VARCHAR(96) NOT NULL UNIQUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (ends_at IS NULL OR starts_at IS NULL OR ends_at >= starts_at)
);
CREATE INDEX IF NOT EXISTS idx_ofertas_qr_status ON ofertas_qr_comerciales(status,starts_at,ends_at);
CREATE INDEX IF NOT EXISTS idx_ofertas_qr_business ON ofertas_qr_comerciales(business_id,status);

CREATE TABLE IF NOT EXISTS items_oferta_qr (
    offer_id BIGINT NOT NULL REFERENCES ofertas_qr_comerciales(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES productos(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price_usd NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (unit_price_usd >= 0),
    PRIMARY KEY(offer_id,product_id)
);

CREATE TABLE IF NOT EXISTS compras_qr_comerciales (
    id BIGSERIAL PRIMARY KEY,
    offer_id BIGINT NOT NULL REFERENCES ofertas_qr_comerciales(id) ON DELETE RESTRICT,
    business_id BIGINT NOT NULL REFERENCES negocios_asociados(id) ON DELETE RESTRICT,
    user_id BIGINT NOT NULL REFERENCES usuarios(id) ON DELETE RESTRICT,
    status VARCHAR(32) NOT NULL DEFAULT 'AWAITING_CONFIRMATION'
        CHECK (status IN ('AWAITING_CONFIRMATION','AWAITING_INITIAL','COMPLETED','CANCELLED','EXPIRED')),
    total_usd NUMERIC(12,2) NOT NULL,
    level_snapshot INTEGER NOT NULL,
    level_name_snapshot VARCHAR(120) NOT NULL,
    available_line_snapshot NUMERIC(12,2) NOT NULL,
    initial_percent NUMERIC(7,2) NOT NULL,
    initial_usd NUMERIC(12,2) NOT NULL,
    financed_usd NUMERIC(12,2) NOT NULL,
    installment_count INTEGER NOT NULL,
    installment_usd NUMERIC(12,2) NOT NULL,
    offer_name_snapshot VARCHAR(220) NOT NULL,
    business_name_snapshot VARCHAR(220) NOT NULL,
    user_name_snapshot VARCHAR(220) NOT NULL DEFAULT 'Usuario Kredi+',
    user_document_snapshot VARCHAR(80),
    items_snapshot TEXT NOT NULL DEFAULT '',
    loan_id BIGINT REFERENCES prestamos_credito(id) ON DELETE SET NULL,
    confirmed_at TIMESTAMPTZ,
    initial_received_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '15 minutes'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS user_name_snapshot VARCHAR(220) NOT NULL DEFAULT 'Usuario Kredi+';
ALTER TABLE compras_qr_comerciales ADD COLUMN IF NOT EXISTS user_document_snapshot VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_compras_qr_comerciales_user ON compras_qr_comerciales(user_id,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_compras_qr_comerciales_offer ON compras_qr_comerciales(offer_id,status,created_at DESC);
-- La compra única depende de la configuración de cada oferta; no se fuerza con un índice
-- global porque una oferta puede permitir varias compras del mismo usuario.
DROP INDEX IF EXISTS uq_compras_qr_unica_activa;
CREATE INDEX IF NOT EXISTS idx_compras_qr_offer_user
    ON compras_qr_comerciales(offer_id,user_id,created_at DESC);

INSERT INTO versiones_esquema(version, description)
VALUES (96, 'Kredi+ 1.3.0: QR comercial por Jornada/Combo/Oferta, compra por nivel y eliminación lógica de cuentas')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();

-- Migración 97 / Kredi+ 1.3.9: submódulo de bodegas comunitarias con QR y catálogo propio.
CREATE TABLE IF NOT EXISTS bodegas_comunitarias (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(24) UNIQUE,
    public_code VARCHAR(96) NOT NULL UNIQUE,
    municipality VARCHAR(160) NOT NULL,
    parish VARCHAR(160) NOT NULL,
    commune VARCHAR(220) NOT NULL DEFAULT '',
    community VARCHAR(220) NOT NULL DEFAULT '',
    street TEXT NOT NULL DEFAULT '',
    owner_name VARCHAR(220) NOT NULL DEFAULT '',
    owner_document VARCHAR(80) NOT NULL DEFAULT '',
    name VARCHAR(220) NOT NULL,
    census_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    financing_source VARCHAR(220),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    associated_business_id BIGINT REFERENCES negocios_asociados(id) ON DELETE SET NULL,
    source_fingerprint VARCHAR(128) NOT NULL UNIQUE,
    created_by BIGINT REFERENCES usuarios(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_bodegas_comunitarias_az ON bodegas_comunitarias(active,name);
CREATE INDEX IF NOT EXISTS idx_bodegas_comunitarias_territory ON bodegas_comunitarias(municipality,parish,commune,community);
CREATE TABLE IF NOT EXISTS productos_bodega (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES bodegas_comunitarias(id) ON DELETE CASCADE,
    name VARCHAR(220) NOT NULL,
    category VARCHAR(160) NOT NULL DEFAULT 'Alimentos',
    unit VARCHAR(80) NOT NULL DEFAULT 'unidad',
    price_usd NUMERIC(12,2) NOT NULL CHECK(price_usd>=0),
    stock INTEGER NOT NULL DEFAULT 0 CHECK(stock>=0),
    image_path TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_productos_bodega_name ON productos_bodega(store_id,LOWER(BTRIM(name)));
CREATE TABLE IF NOT EXISTS combos_bodega (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES bodegas_comunitarias(id) ON DELETE CASCADE,
    name VARCHAR(220) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    price_usd NUMERIC(12,2) NOT NULL CHECK(price_usd>0),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_combos_bodega_name ON combos_bodega(store_id,LOWER(BTRIM(name)));
CREATE TABLE IF NOT EXISTS items_combo_bodega (
    combo_id BIGINT NOT NULL REFERENCES combos_bodega(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES productos_bodega(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL CHECK(quantity>0),
    PRIMARY KEY(combo_id,product_id)
);
CREATE TABLE IF NOT EXISTS ofertas_bodega_mapeo (
    offer_id BIGINT PRIMARY KEY REFERENCES ofertas_qr_comerciales(id) ON DELETE CASCADE,
    store_id BIGINT NOT NULL REFERENCES bodegas_comunitarias(id) ON DELETE CASCADE,
    item_type VARCHAR(16) NOT NULL CHECK(item_type IN ('PRODUCT','COMBO')),
    item_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(store_id,item_type,item_id)
);
CREATE TABLE IF NOT EXISTS compras_bodega_aplicadas (
    purchase_id BIGINT PRIMARY KEY REFERENCES compras_qr_comerciales(id) ON DELETE CASCADE,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO versiones_esquema(version, description)
VALUES (97, 'Kredi+ 1.3.9: bodegas comunitarias, importación Excel, QR permanente y catálogo propio por comercio')
ON CONFLICT(version) DO UPDATE SET description=EXCLUDED.description, applied_at=NOW();
