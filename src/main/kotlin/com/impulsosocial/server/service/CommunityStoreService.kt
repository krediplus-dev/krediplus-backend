package com.impulsosocial.server.service

import com.impulsosocial.server.db.Database
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.sql.Connection
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class CommunityStoreDto(
    val id: Long,
    val code: String,
    val publicCode: String,
    val name: String,
    val municipality: String,
    val parish: String,
    val commune: String,
    val community: String,
    val street: String,
    val ownerName: String,
    val ownerDocument: String,
    val censusStatus: String,
    val financingSource: String?,
    val active: Boolean,
    val associatedBusinessId: Long?,
    val productCount: Int = 0,
    val comboCount: Int = 0
)

data class CommunityStoreImportRow(
    val rowNumber: Int,
    val action: String,
    val name: String,
    val municipality: String,
    val parish: String,
    val commune: String,
    val community: String,
    val street: String,
    val ownerName: String,
    val ownerDocument: String,
    val censusStatus: String,
    val financingSource: String?,
    val errors: List<String> = emptyList()
)

data class CommunityStoreImportResult(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val createRows: Int,
    val updateRows: Int,
    val importedRows: Int,
    val message: String,
    val rows: List<CommunityStoreImportRow>
)

data class StoreProductDto(
    val id: Long,
    val storeId: Long,
    val name: String,
    val category: String,
    val unit: String,
    val priceUsd: Double,
    val stock: Int,
    val active: Boolean,
    val imageUrl: String? = null
)

data class SaveStoreProductRequest(
    val name: String = "",
    val category: String = "Alimentos",
    val unit: String = "unidad",
    val priceUsd: Double = 0.0,
    val stock: Int = 0,
    val active: Boolean = true
)

data class StoreComboItemRequest(val productId: Long = 0, val quantity: Int = 1)
data class SaveStoreComboRequest(
    val name: String = "",
    val description: String = "",
    val priceUsd: Double = 0.0,
    val active: Boolean = true,
    val items: List<StoreComboItemRequest> = emptyList()
)
data class StoreComboItemDto(val productId: Long, val name: String, val quantity: Int)
data class StoreComboDto(
    val id: Long,
    val storeId: Long,
    val name: String,
    val description: String,
    val priceUsd: Double,
    val active: Boolean,
    val items: List<StoreComboItemDto>
)
data class CommunityStoreCatalogDto(
    val store: CommunityStoreDto,
    val products: List<StoreProductDto>,
    val combos: List<StoreComboDto>
)

data class LinkCommunityStoreBusinessRequest(val businessId: Long = 0)
data class CommunityStorePurchaseRequest(val itemType: String = "", val itemId: Long = 0)

class CommunityStoreService(private val database: Database) {
    private val formatter = DataFormatter(Locale("es", "VE"))

    fun ensureSchema() {
        database.dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
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
                    """.trimIndent()
                )
            }
        }
    }

    fun list(activeOnly: Boolean = false): List<CommunityStoreDto> {
        ensureSchema()
        database.dataSource.connection.use { c ->
            val where = if (activeOnly) "WHERE s.active=TRUE" else ""
            val sql = """
                SELECT s.*,
                       (SELECT COUNT(*) FROM productos_bodega p WHERE p.store_id=s.id AND p.active=TRUE) product_count,
                       (SELECT COUNT(*) FROM combos_bodega cb WHERE cb.store_id=s.id AND cb.active=TRUE) combo_count
                FROM bodegas_comunitarias s $where
                ORDER BY s.active DESC, LOWER(s.name) ASC, s.id ASC
            """.trimIndent()
            c.prepareStatement(sql).use { st -> st.executeQuery().use { rs ->
                return buildList { while (rs.next()) add(dto(rs)) }
            }}
        }
    }

    fun importExcel(actorId: Long, bytes: ByteArray, confirm: Boolean): CommunityStoreImportResult {
        ensureSchema()
        if (bytes.isEmpty()) throw AppException("El Excel está vacío.")
        val rows = parseExcel(bytes)
        if (rows.isEmpty()) throw AppException("No se encontraron bodegas en el Excel. Verifica los encabezados.")
        val existing = database.dataSource.connection.use { c ->
            c.prepareStatement("SELECT source_fingerprint FROM bodegas_comunitarias").use { st -> st.executeQuery().use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }}
        }
        val preview = rows.map { row ->
            if (row.errors.isNotEmpty()) row else row.copy(action = if (fingerprint(row) in existing) "UPDATE" else "CREATE")
        }
        val valid = preview.count { it.errors.isEmpty() }
        val invalid = preview.size - valid
        val creates = preview.count { it.action == "CREATE" && it.errors.isEmpty() }
        val updates = preview.count { it.action == "UPDATE" && it.errors.isEmpty() }
        var imported = 0
        if (confirm) {
            database.transaction { c ->
                preview.filter { it.errors.isEmpty() }.forEach { row ->
                    upsert(c, actorId, row)
                    imported++
                }
            }
        }
        return CommunityStoreImportResult(
            totalRows = preview.size,
            validRows = valid,
            invalidRows = invalid,
            createRows = creates,
            updateRows = updates,
            importedRows = imported,
            message = if (confirm) "$imported bodegas importadas/actualizadas y con código QR asignado." else "$creates nuevas, $updates para actualizar y $invalid con errores. Revisa antes de importar.",
            rows = preview.take(300)
        )
    }

    fun catalog(storeId: Long): CommunityStoreCatalogDto {
        ensureSchema()
        val store = database.dataSource.connection.use { c -> findStore(c, storeId) }
        return CommunityStoreCatalogDto(store, products(storeId), combos(storeId))
    }

    fun resolveCode(raw: String): CommunityStoreCatalogDto {
        ensureSchema()
        val code = extractCode(raw)
        database.dataSource.connection.use { c ->
            val id = c.prepareStatement("SELECT id FROM bodegas_comunitarias WHERE active=TRUE AND (UPPER(public_code)=UPPER(?) OR UPPER(code)=UPPER(?))").use { st ->
                st.setString(1, code); st.setString(2, code)
                st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else throw NotFoundException("El QR de la bodega no existe o está inactivo.") }
            }
            return catalog(id)
        }
    }

    fun products(storeId: Long): List<StoreProductDto> {
        ensureSchema()
        database.dataSource.connection.use { c -> return products(c, storeId) }
    }

    fun saveProduct(storeId: Long, productId: Long?, request: SaveStoreProductRequest): StoreProductDto {
        ensureSchema(); if(request.name.isBlank()) throw AppException("Indica el nombre del producto."); if(request.priceUsd < 0 || request.stock < 0) throw AppException("Precio y existencia no pueden ser negativos.")
        return database.transaction { c ->
            findStore(c, storeId)
            val id = if(productId==null) c.prepareStatement("INSERT INTO productos_bodega(store_id,name,category,unit,price_usd,stock,active) VALUES (?,?,?,?,?,?,?) RETURNING id").use { st -> bindProduct(st,storeId,request); st.executeQuery().use { rs->rs.next();rs.getLong(1) } }
            else { c.prepareStatement("UPDATE productos_bodega SET name=?,category=?,unit=?,price_usd=?,stock=?,active=?,updated_at=NOW() WHERE id=? AND store_id=?").use { st -> st.setString(1,request.name.trim());st.setString(2,request.category.trim().ifBlank{"Alimentos"});st.setString(3,request.unit.trim().ifBlank{"unidad"});st.setBigDecimal(4,BigDecimal.valueOf(request.priceUsd));st.setInt(5,request.stock);st.setBoolean(6,request.active);st.setLong(7,productId);st.setLong(8,storeId);if(st.executeUpdate()==0)throw NotFoundException("Producto no encontrado.") }; productId }
            products(c, storeId).first { it.id==id }
        }
    }

    fun combos(storeId: Long): List<StoreComboDto> {
        ensureSchema()
        database.dataSource.connection.use { c -> return combos(c, storeId) }
    }

    fun saveCombo(storeId: Long, comboId: Long?, request: SaveStoreComboRequest): StoreComboDto {
        ensureSchema(); if(request.name.isBlank() || request.priceUsd<=0) throw AppException("Indica nombre y precio válido del combo."); if(request.items.isEmpty()) throw AppException("Agrega al menos un producto al combo.")
        return database.transaction { c ->
            findStore(c,storeId)
            request.items.forEach { item ->
                if(item.productId<=0 || item.quantity<=0) throw AppException("Producto/cantidad inválidos en el combo.")
                val exists=c.prepareStatement("SELECT EXISTS(SELECT 1 FROM productos_bodega WHERE id=? AND store_id=? AND active=TRUE)").use { st->st.setLong(1,item.productId);st.setLong(2,storeId);st.executeQuery().use { rs->rs.next()&&rs.getBoolean(1) } }
                if(!exists) throw AppException("El combo contiene un producto que no pertenece a esta bodega.")
            }
            val id=if(comboId==null)c.prepareStatement("INSERT INTO combos_bodega(store_id,name,description,price_usd,active) VALUES (?,?,?,?,?) RETURNING id").use { st->st.setLong(1,storeId);st.setString(2,request.name.trim());st.setString(3,request.description.trim());st.setBigDecimal(4,BigDecimal.valueOf(request.priceUsd));st.setBoolean(5,request.active);st.executeQuery().use { rs->rs.next();rs.getLong(1) } }
            else { c.prepareStatement("UPDATE combos_bodega SET name=?,description=?,price_usd=?,active=?,updated_at=NOW() WHERE id=? AND store_id=?").use { st->st.setString(1,request.name.trim());st.setString(2,request.description.trim());st.setBigDecimal(3,BigDecimal.valueOf(request.priceUsd));st.setBoolean(4,request.active);st.setLong(5,comboId);st.setLong(6,storeId);if(st.executeUpdate()==0)throw NotFoundException("Combo no encontrado.") };comboId }
            c.prepareStatement("DELETE FROM items_combo_bodega WHERE combo_id=?").use { st->st.setLong(1,id);st.executeUpdate() }
            c.prepareStatement("INSERT INTO items_combo_bodega(combo_id,product_id,quantity) VALUES (?,?,?)").use { st->request.items.forEach { item->st.setLong(1,id);st.setLong(2,item.productId);st.setInt(3,item.quantity);st.addBatch() };st.executeBatch() }
            combos(c, storeId).first { it.id==id }
        }
    }

    fun linkBusiness(storeId: Long, businessId: Long): CommunityStoreDto {
        ensureSchema()
        if (businessId <= 0) throw AppException("Selecciona un negocio asociado válido.")
        return database.transaction { c ->
            findStore(c, storeId)
            val active = c.prepareStatement("SELECT active FROM negocios_asociados WHERE id=?").use { st ->
                st.setLong(1, businessId); st.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else throw NotFoundException("El negocio asociado no existe.") }
            }
            if (!active) throw AppException("El negocio asociado está inactivo.")
            c.prepareStatement("UPDATE bodegas_comunitarias SET associated_business_id=?,updated_at=NOW() WHERE id=?").use { st -> st.setLong(1,businessId);st.setLong(2,storeId);st.executeUpdate() }
            findStore(c, storeId)
        }
    }

    fun ensurePurchaseOffer(storeId: Long, request: CommunityStorePurchaseRequest): String {
        ensureSchema()
        val type = request.itemType.trim().uppercase()
        if (type !in setOf("PRODUCT","COMBO") || request.itemId <= 0) throw AppException("Producto o combo inválido.")
        return database.transaction { c ->
            val store = findStore(c, storeId)
            val businessId = store.associatedBusinessId ?: throw AppException("Esta bodega todavía no tiene configurado su negocio de cobro. El administrador debe vincularlo antes de vender.")
            val businessActive = c.prepareStatement("SELECT active FROM negocios_asociados WHERE id=?").use { st -> st.setLong(1,businessId);st.executeQuery().use { rs->rs.next()&&rs.getBoolean(1) } }
            if (!businessActive) throw AppException("El negocio de cobro de esta bodega está inactivo.")

            val itemName: String
            val description: String
            val price: BigDecimal
            val capacity: Int
            if (type == "PRODUCT") {
                val row = c.prepareStatement("SELECT name,category,price_usd,stock,active FROM productos_bodega WHERE id=? AND store_id=?").use { st ->
                    st.setLong(1,request.itemId);st.setLong(2,storeId);st.executeQuery().use { rs -> if(!rs.next()) throw NotFoundException("Producto no encontrado."); listOf(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getInt(4),rs.getBoolean(5)) }
                }
                if (row[4] != true) throw AppException("El producto no está disponible.")
                itemName=row[0] as String; description=row[1] as String; price=row[2] as BigDecimal; capacity=row[3] as Int
            } else {
                val row = c.prepareStatement("SELECT name,description,price_usd,active FROM combos_bodega WHERE id=? AND store_id=?").use { st ->
                    st.setLong(1,request.itemId);st.setLong(2,storeId);st.executeQuery().use { rs -> if(!rs.next()) throw NotFoundException("Combo no encontrado."); listOf(rs.getString(1),rs.getString(2),rs.getBigDecimal(3),rs.getBoolean(4)) }
                }
                if (row[3] != true) throw AppException("El combo no está disponible.")
                itemName=row[0] as String; description=row[1] as String; price=row[2] as BigDecimal
                capacity = c.prepareStatement("SELECT MIN(FLOOR(p.stock::numeric/i.quantity))::int FROM items_combo_bodega i JOIN productos_bodega p ON p.id=i.product_id WHERE i.combo_id=? AND p.active=TRUE").use { st -> st.setLong(1,request.itemId);st.executeQuery().use { rs -> if(rs.next()) rs.getInt(1).let{if(rs.wasNull())0 else it} else 0 } }
            }
            if (price <= BigDecimal.ZERO) throw AppException("Este artículo no tiene un precio válido.")
            if (capacity <= 0) throw AppException("Este artículo está agotado.")

            val mapped = c.prepareStatement("SELECT offer_id FROM ofertas_bodega_mapeo WHERE store_id=? AND item_type=? AND item_id=?").use { st -> st.setLong(1,storeId);st.setString(2,type);st.setLong(3,request.itemId);st.executeQuery().use { rs->if(rs.next())rs.getLong(1) else null } }
            val sold = mapped?.let { offerId -> c.prepareStatement("SELECT sold_count FROM ofertas_qr_comerciales WHERE id=?").use { st->st.setLong(1,offerId);st.executeQuery().use { rs->if(rs.next())rs.getInt(1) else 0 } } } ?: 0
            val stockLimit = sold + capacity
            val publicCode = if (mapped == null) {
                val code = "KQO-" + UUID.randomUUID().toString().replace("-","").take(18).uppercase()
                val offerId = c.prepareStatement("""INSERT INTO ofertas_qr_comerciales(offer_type,source_id,business_id,name,description,price_usd,status,stock_limit,one_purchase_per_user,public_code)
                    VALUES ('OFFER',NULL,?,?,?,?, 'ACTIVE',?,FALSE,?) RETURNING id""").use { st ->
                    st.setLong(1,businessId);st.setString(2,itemName);st.setString(3,description);st.setBigDecimal(4,price);st.setInt(5,stockLimit);st.setString(6,code);st.executeQuery().use { rs->rs.next();rs.getLong(1) }
                }
                c.prepareStatement("INSERT INTO ofertas_bodega_mapeo(offer_id,store_id,item_type,item_id) VALUES (?,?,?,?)").use { st->st.setLong(1,offerId);st.setLong(2,storeId);st.setString(3,type);st.setLong(4,request.itemId);st.executeUpdate() }
                code
            } else {
                c.prepareStatement("UPDATE ofertas_qr_comerciales SET business_id=?,name=?,description=?,price_usd=?,status='ACTIVE',stock_limit=?,updated_at=NOW() WHERE id=?").use { st->st.setLong(1,businessId);st.setString(2,itemName);st.setString(3,description);st.setBigDecimal(4,price);st.setInt(5,stockLimit);st.setLong(6,mapped);st.executeUpdate() }
                c.prepareStatement("SELECT public_code FROM ofertas_qr_comerciales WHERE id=?").use { st->st.setLong(1,mapped);st.executeQuery().use { rs->if(rs.next())rs.getString(1) else throw NotFoundException("La oferta de esta bodega no existe.") } }
            }
            publicCode
        }
    }

    fun applyCompletedPurchase(purchaseId: Long) {
        ensureSchema()
        if (purchaseId <= 0) return
        database.transaction { c ->
            val status = c.prepareStatement("SELECT status,offer_id FROM compras_qr_comerciales WHERE id=? FOR UPDATE").use { st -> st.setLong(1,purchaseId);st.executeQuery().use { rs->if(rs.next())Pair(rs.getString(1),rs.getLong(2)) else return@transaction } }
            if (status.first != "COMPLETED") return@transaction
            val already = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM compras_bodega_aplicadas WHERE purchase_id=?)").use { st->st.setLong(1,purchaseId);st.executeQuery().use { rs->rs.next()&&rs.getBoolean(1) } }
            if (already) return@transaction
            val mapping = c.prepareStatement("SELECT store_id,item_type,item_id FROM ofertas_bodega_mapeo WHERE offer_id=?").use { st->st.setLong(1,status.second);st.executeQuery().use { rs->if(rs.next())Triple(rs.getLong(1),rs.getString(2),rs.getLong(3)) else null } } ?: return@transaction
            if (mapping.second == "PRODUCT") {
                val changed=c.prepareStatement("UPDATE productos_bodega SET stock=stock-1,updated_at=NOW() WHERE id=? AND store_id=? AND stock>=1").use { st->st.setLong(1,mapping.third);st.setLong(2,mapping.first);st.executeUpdate() }
                if(changed==0) throw AppException("La existencia de la bodega cambió antes de completar la compra.")
            } else {
                val lines=c.prepareStatement("SELECT product_id,quantity FROM items_combo_bodega WHERE combo_id=?").use { st->st.setLong(1,mapping.third);st.executeQuery().use { rs->buildList { while(rs.next())add(rs.getLong(1) to rs.getInt(2)) } } }
                for((productId,qty) in lines){
                    val changed=c.prepareStatement("UPDATE productos_bodega SET stock=stock-?,updated_at=NOW() WHERE id=? AND store_id=? AND stock>=?").use { st->st.setInt(1,qty);st.setLong(2,productId);st.setLong(3,mapping.first);st.setInt(4,qty);st.executeUpdate() }
                    if(changed==0) throw AppException("La existencia de uno de los productos del combo cambió antes de completar la compra.")
                }
            }
            c.prepareStatement("INSERT INTO compras_bodega_aplicadas(purchase_id) VALUES (?)").use { st->st.setLong(1,purchaseId);st.executeUpdate() }
        }
    }

    private fun products(c: Connection, storeId: Long): List<StoreProductDto> =
        c.prepareStatement("SELECT id,store_id,name,category,unit,price_usd,stock,active,image_path FROM productos_bodega WHERE store_id=? ORDER BY active DESC,LOWER(name)").use { st ->
            st.setLong(1, storeId); st.executeQuery().use { rs -> buildList { while(rs.next()) add(StoreProductDto(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getBigDecimal(6).toDouble(),rs.getInt(7),rs.getBoolean(8),rs.getString(9))) } }
        }

    private fun combos(c: Connection, storeId: Long): List<StoreComboDto> {
        val base = mutableListOf<StoreComboDto>()
        c.prepareStatement("SELECT id,store_id,name,description,price_usd,active FROM combos_bodega WHERE store_id=? ORDER BY active DESC,LOWER(name)").use { st -> st.setLong(1,storeId); st.executeQuery().use { rs ->
            while(rs.next()){
                val comboId=rs.getLong(1)
                val items=c.prepareStatement("SELECT p.id,p.name,i.quantity FROM items_combo_bodega i JOIN productos_bodega p ON p.id=i.product_id WHERE i.combo_id=? ORDER BY p.name").use { ist -> ist.setLong(1,comboId);ist.executeQuery().use { irs->buildList { while(irs.next())add(StoreComboItemDto(irs.getLong(1),irs.getString(2),irs.getInt(3))) } } }
                base += StoreComboDto(comboId,rs.getLong(2),rs.getString(3),rs.getString(4),rs.getBigDecimal(5).toDouble(),rs.getBoolean(6),items)
            }
        }}
        return base
    }

    private fun parseExcel(bytes: ByteArray): List<CommunityStoreImportRow> = XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
        val sheet = wb.firstOrNull() ?: return@use emptyList()
        var headerRow = -1
        for (r in 0..minOf(sheet.lastRowNum, 20)) {
            val row=sheet.getRow(r) ?: continue
            val values=(0..9).map { formatter.formatCellValue(row.getCell(it)).trim().lowercase() }
            if(values.any { it=="municipio" } && values.any { it.contains("nombre de la bodega") }) { headerRow=r;break }
        }
        if(headerRow<0) return@use emptyList()
        buildList {
            for(r in headerRow+1..sheet.lastRowNum){
                val row=sheet.getRow(r) ?: continue
                val v=(0..9).map { formatter.formatCellValue(row.getCell(it)).trim() }
                if(v.all { it.isBlank() }) continue
                val errors=mutableListOf<String>()
                if(v[0].isBlank()) errors += "Municipio obligatorio"
                if(v[1].isBlank()) errors += "Parroquia obligatoria"
                if(v[7].isBlank()) errors += "Nombre de bodega obligatorio"
                add(CommunityStoreImportRow(r+1,"CREATE",v[7],v[0],v[1],v[2],v[3],v[4],v[5],v[6],normalizeStatus(v[8]),v[9].ifBlank{null},errors))
            }
        }
    }

    private fun upsert(c: Connection, actorId: Long, row: CommunityStoreImportRow) {
        val fp=fingerprint(row)
        val existing=c.prepareStatement("SELECT id FROM bodegas_comunitarias WHERE source_fingerprint=?").use { st->st.setString(1,fp);st.executeQuery().use { rs->if(rs.next())rs.getLong(1) else null } }
        if(existing==null){
            val public="KPB-"+UUID.randomUUID().toString().replace("-","").take(14).uppercase()
            val id=c.prepareStatement("""INSERT INTO bodegas_comunitarias(public_code,municipality,parish,commune,community,street,owner_name,owner_document,name,census_status,financing_source,active,source_fingerprint,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id""").use { st->
                st.setString(1,public);bindStoreRow(st,2,row);st.setString(13,fp);st.setLong(14,actorId);st.executeQuery().use { rs->rs.next();rs.getLong(1) }
            }
            c.prepareStatement("UPDATE bodegas_comunitarias SET code=? WHERE id=?").use { st->st.setString(1,"KPB-%06d".format(id));st.setLong(2,id);st.executeUpdate() }
        } else {
            c.prepareStatement("""UPDATE bodegas_comunitarias SET municipality=?,parish=?,commune=?,community=?,street=?,owner_name=?,owner_document=?,name=?,census_status=?,financing_source=?,active=?,updated_at=NOW() WHERE id=?""").use { st->bindStoreRow(st,1,row);st.setLong(12,existing);st.executeUpdate() }
        }
    }

    private fun bindStoreRow(st: java.sql.PreparedStatement, start: Int, r: CommunityStoreImportRow){
        var i=start;st.setString(i++,r.municipality.trim());st.setString(i++,r.parish.trim());st.setString(i++,r.commune.trim());st.setString(i++,r.community.trim());st.setString(i++,r.street.trim());st.setString(i++,r.ownerName.trim());st.setString(i++,r.ownerDocument.trim());st.setString(i++,r.name.trim());st.setString(i++,r.censusStatus);st.setString(i++,r.financingSource);st.setBoolean(i,r.censusStatus=="ACTIVE")
    }
    private fun bindProduct(st: java.sql.PreparedStatement, storeId: Long, r: SaveStoreProductRequest){st.setLong(1,storeId);st.setString(2,r.name.trim());st.setString(3,r.category.trim().ifBlank{"Alimentos"});st.setString(4,r.unit.trim().ifBlank{"unidad"});st.setBigDecimal(5,BigDecimal.valueOf(r.priceUsd));st.setInt(6,r.stock);st.setBoolean(7,r.active)}

    private fun dto(rs: java.sql.ResultSet)=CommunityStoreDto(rs.getLong("id"),rs.getString("code")?:"",rs.getString("public_code"),rs.getString("name"),rs.getString("municipality"),rs.getString("parish"),rs.getString("commune"),rs.getString("community"),rs.getString("street"),rs.getString("owner_name"),rs.getString("owner_document"),rs.getString("census_status"),rs.getString("financing_source"),rs.getBoolean("active"),rs.getLong("associated_business_id").let{if(rs.wasNull())null else it},runCatching{rs.getInt("product_count")}.getOrDefault(0),runCatching{rs.getInt("combo_count")}.getOrDefault(0))
    private fun findStore(c:Connection,id:Long):CommunityStoreDto=c.prepareStatement("SELECT s.*,(SELECT COUNT(*) FROM productos_bodega p WHERE p.store_id=s.id AND p.active=TRUE) product_count,(SELECT COUNT(*) FROM combos_bodega cb WHERE cb.store_id=s.id AND cb.active=TRUE) combo_count FROM bodegas_comunitarias s WHERE s.id=?").use { st->st.setLong(1,id);st.executeQuery().use { rs->if(rs.next())dto(rs) else throw NotFoundException("La bodega no existe.") } }

    private fun normalizeStatus(v:String):String {
        val value=v.trim().uppercase()
        return when {
            value.startsWith("INACT") -> "INACTIVE"
            value.startsWith("ACT") -> "ACTIVE"
            else -> "UNKNOWN"
        }
    }
    private fun normalize(v:String):String=Normalizer.normalize(v.trim().lowercase(Locale("es","VE")),Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").replace(Regex("\\s+")," ")
    private fun fingerprint(r:CommunityStoreImportRow)=listOf(r.ownerDocument,r.name,r.municipality,r.parish,r.community).joinToString("|"){normalize(it)}
    private fun extractCode(raw:String):String{
        val trimmed=raw.trim(); val last=runCatching{java.net.URI(trimmed).path.substringAfterLast('/')}.getOrNull()?.takeIf{it.isNotBlank()};return (last?:trimmed).trim()
    }
}
