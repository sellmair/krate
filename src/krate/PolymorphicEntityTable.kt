package krate

import reflectr.entity.Entity

import krate.handling.query
import krate.binding.SqlBinding
import krate.annotations.table
import krate.extensions.parentKey
import krate.util.Sr

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert

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
 * This is achieved by creating a [UUID] called a "variant ID" or `vid` for short. This `vid` is stored in a column
 * of both the base and variant table, with a [parentKey] FK from the variant table column to the base table column.
 *
 * Whenever operations on an entity are ran on an instance of this, the `vid` associated with the entity's UUID in the
 * base table is used to perform reads or updates on the variant tables.
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
 * **Note** : It is important to consider that usage of this class implies a significantly higher quantity of SQL `join` operations, as
 * every operation except [delete] requires joining of the base table and a variant table. Performance degradation is to be expected,
 * and query optimization should be of a higher concern.
 *
 * @param kklass the sealed parent class, required for initialization of variant tables
 *
 * @author Benjozork
 */
abstract class PolymorphicEntityTable<TEntity : Entity>(val kklass: KClass<out TEntity>) : EntityTable<TEntity>() {

    val variantId = uuid ("variant_id")

    val variantTables = this.kklass.sealedSubclasses.associateWith { variant -> variant.table }

    init {
        require(this.kklass.isSealed) { "polymorphic entity tables can only be used on sealed classes" }

        variantTables.forEach { (_, table) -> // Create vid parent key column on each variant table
            with(table) {
                parentKey ("variant_id", this@PolymorphicEntityTable)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun insert(entity: TEntity): Sr<TEntity> = query {
        val klass = entity::class as KClass<TEntity>
        val entityVariantTable = klass.table

        val vid = UUID.randomUUID()!!

        // Insert into the base table

        this.insert {
            it[variantId] = vid

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

        // Run insert algorithm for the variant table

        entityVariantTable.insert {
            it[entityVariantTable.columns.first { c -> c.name == "variant_id" } as Column<UUID>] = vid

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
    }.map { entity }

}
