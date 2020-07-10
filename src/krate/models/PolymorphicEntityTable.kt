package krate.models

import reflectr.entity.Entity

import krate.handling.query
import krate.binding.SqlBinding
import krate.binding.table
import krate.util.Sr

import org.jetbrains.exposed.sql.*

import java.util.*

import kotlin.reflect.KClass

import com.github.kittinunf.result.coroutines.map

/**
 * Version of [EntityTable] that can operate generically on sealed types.
 *
 * This table class works by creating one base table that stores the common properties of the base [TEntity] type,
 * as well as accessing "variant tables" (defined in the `@SqlTable` annotation present on variant classes) to store
 * variant-specific data. Both base and variant table need to be manually created, the base table inheriting from this very
 * class.
 *
 * This is achieved by storing the [UUID] of an entity in both the base table and a variant table.
 *
 * Read operations imply a `select` on every variant table until the one containing the UUID is found.
 *
 * The base table must include [bindings][SqlBinding] to common properties of the base sealed type. Variant tables,
 * on the other hand, must only include bindings to properties of the sealed variant they are associated with. During
 * operations, bindings from both are combined by implementation logic in overrides of [obtain], [obtainAll], [obtainListing],
 * [insert], [update] and [delete]. Passage of properties to [reflectr.entity.instantiation.construct] is also handled
 * automatically.
 *
 * The operations described above are also available on variant tables themselves, using the same combining mechanisms,
 * with the advantage of providing safe return types for each variant. Logically, operations on the variant tables do not
 * return or deal with entities of the same parent type but of a different variant type. Operations on the base table
 * however, return or deal with entities of all variant types.
 *
 * **Note** : It is important to consider that usage of this class implies a significantly higher quantity of SQL `select` operations,
 * as entity constructions requires both the base and variant data. Performance degradation is to be expected,
 * and query optimization should be of a higher concern.
 *
 * @param klass the sealed parent class, required for initialization of variant tables
 *
 * @author Benjozork
 */
abstract class PolymorphicEntityTable<TEntity : Entity>(klass: KClass<out TEntity>, name: String = "") : EntityTable<TEntity>(klass, name) {

    val variantTables get() = this.klass.sealedSubclasses.associateWith { variant -> variant.table }

    init {
        require(this.klass.isSealed) { "polymorphic entity tables can only be used on sealed classes" }
    }

    override val primaryKey: PrimaryKey // Hack to avoid initializing VT instances at the same time as the base table,
                                          // preventing downstream bindings from referring to this while it is uninitialized
        get() {
            variantTables.forEach { (_, table) -> // Create parent key column on each variant table's uuid column
                table.uuid.foreignKey = ForeignKeyConstraint(this@PolymorphicEntityTable.uuid, table.uuid, ReferenceOption.RESTRICT, ReferenceOption.CASCADE, null)
            }

            return super.primaryKey
        }

    override suspend fun obtainListing ( // TODO this is very unoptimized; fix later
        queryContext: QueryContext,
        selectCondition: SqlExpressionBuilder.() -> Op<Boolean>,
        quantity: Int,
        page: Int,
        orderBy: Column<*>,
        sortOrder: SortOrder
    ): Sr<Pair<List<TEntity>, Boolean>> = query {
        val baseRowSet = variantTables.values.fold<EntityTable<*>, ColumnSet>(this) { join, table -> join.leftJoin(table) }
            .selectAll()
            .orderBy(orderBy, sortOrder)
            //               v-- We add one to check if we reached the end
            .limit(quantity + 1, (page * quantity).toLong())
            .toList().also { println(it.size) }
            .map { row -> variantTables.values.first { vt -> row.getOrNull(vt.uuid) != null } to row } // Make a map of rows indexed by the VT tehy worked with

        baseRowSet.map { it.first.convert(queryContext, it.second).get() } to false
    }

    override suspend fun obtain(queryContext: QueryContext, id: UUID): Sr<TEntity> = query {
        val baseRow = select {
            uuid eq id
        }.single()

        var goodVariantTable: EntityTable<out TEntity>? = null
        var goodVariantRow: ResultRow? = null
        for ((_, vt) in variantTables) {
            vt.select { vt.uuid eq id }.singleOrNull()?.let { row ->
                goodVariantTable = vt
                goodVariantRow = row
            }
            if (goodVariantTable != null) break
        }

        if (goodVariantTable == null || goodVariantRow == null)
            error("not variant table row was found with uuid $id")

        fun ResultRow.exprMap() = fieldIndex.keys.associateWith { expr -> this[expr] }

        val finalRow = ResultRow.createAndFillValues(baseRow.exprMap() + goodVariantRow!!.exprMap())

        goodVariantTable!!.convert(queryContext, finalRow).get()
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun insert(entity: TEntity, forBinding: SqlBinding<*, *, *>?, parentEntity: Entity?): Sr<TEntity> = query {
        require (forBinding == null || parentEntity != null) { "call to insert() for a binding must specify parent entity" }

        val klass = entity::class as KClass<TEntity>
        val entityVariantTable = klass.table

        // Insert into the base table

        this.insert {
            for (binding in bindings) {
                if (binding !is SqlBinding.ReferenceToMany<*, *>)
                    applyBindingToInsertOrUpdate(entity, it, binding)
            }
        }

        for (binding in bindings.filterIsInstance<SqlBinding.ReferenceToMany<TEntity, Any>>()) {
            val instances = binding.property.get(entity)
            val bindingTable = binding.otherTable

            bindingTable.batchInsert(instances) { item ->
                binding.insertionFunction(entity, item, this)
            }
        }

        for (binding in bindings.filterIsInstance<SqlBinding.ReferenceToManyEntities<TEntity, Entity>>()) {
            binding.property.get(entity).forEach { item -> binding.otherTable.insert(item, forBinding = binding) }
        }

        // Run insert algorithm for the variant table

        entityVariantTable.insert {
            it[entityVariantTable.uuid] = entity.uuid

            for (binding in entityVariantTable.bindings) {
                if (binding !is SqlBinding.ReferenceToMany<*, *>)
                    applyBindingToInsertOrUpdate(entity, it, binding)
            }
        }

        for (binding in entityVariantTable.bindings.filterIsInstance<SqlBinding.ReferenceToMany<TEntity, Any>>()) {
            val instances = binding.property.get(entity)
            val bindingTable = binding.otherTable

            bindingTable.batchInsert(instances) { item ->
                binding.insertionFunction(entity, item, this)
            }
        }

        for (binding in entityVariantTable.bindings.filterIsInstance<SqlBinding.ReferenceToManyEntities<TEntity, Entity>>()) {
            binding.property.get(entity).forEach { item -> binding.otherTable.insert(item, forBinding = binding) }
        }
    }.map { entity }

    @Suppress("UNCHECKED_CAST")
    override suspend fun update(entity: TEntity): Sr<TEntity> = query {
        val klass = entity::class as KClass<TEntity>
        val entityVariantTable = klass.table

        // Update in the base table

        this.update({ uuid eq entity.uuid }) {
            for (binding in bindings) {
                if (binding !is SqlBinding.ReferenceToMany<*, *>)
                    applyBindingToInsertOrUpdate(entity, it, binding)
            }
        }

        for (binding in bindings.filterIsInstance<SqlBinding.ReferenceToMany<TEntity, Any>>()) {
            val newInstances = binding.property.get(entity)

            binding.otherTable.deleteWhere { binding.otherTableFkToPkCol eq entity.uuid }

            binding.otherTable.batchInsert(newInstances) { item -> binding.insertionFunction(entity, item, this) }
        }

        // Run update algorithm for the variant table

        entityVariantTable.update({ entityVariantTable.uuid eq entity.uuid }) {
            for (binding in entityVariantTable.bindings) {
                if (binding !is SqlBinding.ReferenceToMany<*, *>)
                    applyBindingToInsertOrUpdate(entity, it, binding)
            }
        }

        for (binding in entityVariantTable.bindings.filterIsInstance<SqlBinding.ReferenceToMany<TEntity, Any>>()) {
            val newInstances = binding.property.get(entity)

            binding.otherTable.deleteWhere { binding.otherTableFkToPkCol eq entity.uuid }

            binding.otherTable.batchInsert(newInstances) { item -> binding.insertionFunction(entity, item, this) }
        }
    }.map { entity }

}
