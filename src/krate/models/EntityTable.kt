package krate.models

import krate.binding.SqlBinding
import krate.optimizer.QueryOptimizer
import krate.handling.query
import krate.handling.unwrappedQuery
import krate.binding.table
import krate.binding.safeTable
import krate.util.*

import reflectr.entity.Entity
import reflectr.entity.instantiation.MissingArgumentsException
import reflectr.SlicedProperty
import reflectr.getPropValueOnInstance
import reflectr.extensions.*

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.statements.UpdateBuilder

import epgx.models.PgTable

import com.github.kittinunf.result.coroutines.map
import com.github.kittinunf.result.coroutines.mapError

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.allSuperclasses
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.superclasses

import java.util.*

/**
 * Generic table for storing [entities][Entity] inside a postgres table.
 *
 * Using [SQL bindings][SqlBinding], this takes care of automagically running CRUD operations to and from the database.
 *
 * For example, if you provide all necessary bindings for a class, simply calling [EntityTable.insert] should be
 * enough to store an instance of [TEntity] in the table. Likewise for [delete], [update], [obtain], [obtainListing]
 * and [obtainAll].
 *
 * @param TEntity the type of [Entity] to be stored
 *
 * @see PgTable
 * @see Entity
 * @see SqlBinding
 *
 * @author Benjozork, hamza1311
 */
abstract class EntityTable<TEntity : Entity>(val klass: KClass<out TEntity>, name: String = "") : PgTable(name) {

    @Suppress("UNCHECKED_CAST")
    private fun isPolymorphicVariantTable() =
        this.klass.superclasses
            .firstOrNull { it.isSubclassOf(Entity::class) && it.isSealed }
            ?.takeIf { (it as KClass<out Entity>).safeTable?.let { t -> t is PolymorphicEntityTable } ?: false }
            ?.let { true }
            ?: false

    @Suppress("UNCHECKED_CAST")
    private val baseTableSealedClass: KClass<Entity>? get() =
        if (isPolymorphicVariantTable())
            this.klass.allSuperclasses.firstOrNull { it.isSubclassOf(Entity::class) && it.isSealed }
                ?.let { it as KClass<Entity> }
        else null

    /**
     * A list of all [bindings][SqlBinding] present for this table
     */
    val bindings = mutableListOf<SqlBinding<TEntity, out Any?, *>>()

    /**
     * Creates a binding between [column] and a [property] containing simple values
     *
     * @param column   the column in which UUIDs of instances of [property] are stored
     * @param property the property of [TEntity]`::class` to bind
     */
    fun <TProperty : Any?> bind(column: Column<TProperty>, property: KProperty1<TEntity, TProperty>): SqlBinding.Value<TEntity, TProperty> {
        return SqlBinding.Value(this@EntityTable, property, column)
            .also { this.bindings += it }
    }

    /**
     * Creates a binding between [column] and a [property] containing nullable references to [entity][Entity]
     *
     * @param column   the column in which UUIDs of instances of [property] are stored
     * @param property the property of [TEntity]`::class` to bind
     */
    fun <TProperty : Entity?> bind(column: Column<UUID?>, property: KProperty1<TEntity, TProperty>): SqlBinding.OneToOneOrNone<TEntity, TProperty> {
        return SqlBinding.OneToOneOrNone(this@EntityTable, property, column)
            .also { this.bindings += it }
    }

    /**
     * Creates a binding between [column] and a [property] containing references to [entity][Entity]
     *
     * @param column   the column in which UUIDs of instances of [property] are stored
     * @param property the property of [TEntity]`::class` to bind
     */
    fun <TProperty : Entity> bind(column: Column<UUID>, property: KProperty1<TEntity, TProperty>): SqlBinding.OneToOne<TEntity, TProperty> {
        return SqlBinding.OneToOne(this@EntityTable, property, column)
            .also { this.bindings += it }
    }

    /**
     * Creates a binding between [table] and a [property]
     *
     * @param table              the table in which instances of [property] are stored for `this` table
     * @param property           the property of [TEntity]`::class` to bind
     * @param conversionFunction the function used to convert rows of [table] into instances of [TProperty]
     * @param insertionFunction  the function used to on an [UpdateBuilder] to insert instances of [TProperty] into [table]
     */
    fun <TProperty : Any> bind (
        table: Table,
        property: KProperty1<TEntity, Collection<TProperty>>,
        conversionFunction: (ResultRow) -> TProperty,
        insertionFunction: (TEntity, TProperty, UpdateBuilder<Number>) -> Unit
    ): SqlBinding.OneToManyValues<TEntity, TProperty> {
        require (
            baseTableSealedClass?.table?.bindings?.any { it is SqlBinding.OneToManyValues<*, *> && it.table == table } ?: true
        ) { "cannot create a ReferenceToMany binding when base table already has a ReferenceToMany binding to the same table" }

        return SqlBinding.OneToManyValues(this@EntityTable, property, table, conversionFunction, insertionFunction)
            .also { this.bindings += it }
    }

    /**
     * Creates a binding between [table] and a [property]
     *
     * Note: this creates a one-to-many relationship, so a FK in [table] to the receiver table's PK is needed.
     *
     * @param table     the table in which instances of [property] are stored for `this` table
     * @param property  the property of [TEntity]`::class` to bind
     */
    fun <TProperty : Entity> bind (
        table: EntityTable<TProperty>,
        property: KProperty1<TEntity, Collection<TProperty>>
    ): SqlBinding.OneToMany<TEntity, TProperty> {
        require (
            baseTableSealedClass?.table?.bindings?.any { it is SqlBinding.OneToManyValues<*, *> && it.table == table } ?: true
        ) { "cannot create a ReferenceToMany binding when base table already has a ReferenceToMany binding to the same table" }

        return SqlBinding.OneToMany(this@EntityTable, property, table)
            .also { this.bindings += it }
    }

    /**
     * Returns an arbitrary list of [limit] items from the table
     */
    open suspend fun obtainAll(queryContext: QueryContext, limit: Int): SrList<TEntity> =
        Wrap {
            query { this.selectAll().limit(limit).toSet() }.get()
                .map { this.convert(queryContext, it).get() }
        }

    /**
     * Queries the table using a listing specification
     *
     * @param queryContext    [QueryContext] for caching
     * @param selectCondition the `where` condition to apply in the query
     * @param quantity        the quantity of items to fetch
     * @param page            the page number
     * @param orderBy         which column to specify in the `order by` clause
     * @param sortOrder       the sort order [SortOrder] (`order by desc / asc`)
     */
    open suspend fun obtainListing (
        queryContext: QueryContext,
        selectCondition: SqlExpressionBuilder.() -> Op<Boolean> = { Op.TRUE },
        quantity: Int,
        page: Int,
        orderBy: Column<*>,
        sortOrder: SortOrder = SortOrder.ASC
    ): Sr<Pair<List<TEntity>, Boolean>> = query {
        QueryOptimizer.optimize(this.klass, selectCondition)
            .orderBy(orderBy, sortOrder)
            //               v-- We add one to check if we reached the end
            .limit(quantity + 1, (page * quantity).toLong())
            .toList()
    }.map { results ->
        QueryOptimizer.convertOptimizedRows(queryContext, klass, results)
            .take(quantity) to (results.size - 1 == quantity)
    }

    /**
     * Returns a specific entity with a certain UUID from the table
     *
     * @param queryContext [QueryContext] for caching
     * @param id           the [UUID] of the entity
     */
    open suspend fun obtain(queryContext: QueryContext, id: UUID): Sr<TEntity> =
        if (isPolymorphicVariantTable())
            baseTableSealedClass?.table?.obtain(queryContext, id) as? Sr<TEntity> ?: error("cannot find base table")
        else query {
            this.select { uuid eq id }.single()
        }.map { this.convert(queryContext, it).get() }

    /**
     * Performs the conversion between a single [ResultRow] and an instance of [TEntity]. Should not be used directly.
     *
     * @param queryContext  [QueryContext] for caching
     * @param source          the result row to get the data from
     * @param aliasToUse      a [table alias][Alias] to specify which columns should be used when data for multiple
     *                        instances of [TEntity] might be present in a single row
     */
    open suspend fun convert (
        queryContext: QueryContext,
        source: ResultRow,
        aliasToUse: Alias<EntityTable<TEntity>>? = null
    ): Sr<TEntity> {
        fun <T> get(column: Column<T>) = if (aliasToUse != null) source[aliasToUse[column]] else source[column]

        // If we are a polymorphic variant table, we need to use both the bindings from the base table and this table
        val bindingsData = (if (isPolymorphicVariantTable()) baseTableSealedClass!!.table.bindings + bindings else bindings).map {
            when (val binding = it) {
                is SqlBinding.HasColumn<*> -> {
                    (binding.property.okHandle ?: never) to get(binding.column)
                }
                is SqlBinding.OneToManyValues<*, *> -> {
                    val entityId = get(this.uuid)

                    (binding.property.okHandle ?: never) to unwrappedQuery {
                        binding.otherTable.select { binding.otherTableFkToPkCol eq entityId }
                            .toSet().map { row -> binding.conversionFunction(row) }
                    }
                }
                is SqlBinding.OneToMany<*, *> -> {
                    val entityId = get(this.uuid)

                    (binding.property.okHandle ?: never) to unwrappedQuery {
                        binding.otherTable.select { binding.otherTableFkToPkCol eq entityId }
                            .toSet().map { row -> binding.otherTable.convert(queryContext, row).get() }
                    }
                }
                else -> never
            }

        }.toMap()

        return with(queryContext) {
            klass.construct(bindingsData)
                .mapError { e ->
                    if (e is MissingArgumentsException)
                        IllegalStateException("there were missing arguments when calling construct() during SQL conversion - " +
                                "you might want to implement convert() yourself", e)
                    else e
                }
        }
    }

    /**
     * Applies a value of the binding's property for [entity] on an [`insert` / `update` statement][insertStatement]
     *
     * @param entity          the instance on which the value of [binding]'s property should be collected
     * @param insertStatement an [UpdateBuilder] on which to set the binding's column to the value
     * @param binding         the [SqlBinding] we are working with
     */
    @Suppress("UNCHECKED_CAST")
    protected fun <TEntity : Entity, TProperty : Any?> applyBindingToInsertOrUpdate (
        entity: TEntity,
        insertStatement: UpdateBuilder<Number>,
        binding: SqlBinding<TEntity, TProperty, *>
    ) {
        assert(binding !is SqlBinding.OneToManyValues<*, *>) { "applyBindingToInsertOrUpdate should not be called on SqlBinding.ReferenceToMany" }

        val slicedProperty = getPropValueOnInstance (
            instance = entity,
            propertyHandle = binding.property.handle,
            unsafe = true
        )

        val value = when (slicedProperty) {
            is SlicedProperty.Value -> slicedProperty.value
            is SlicedProperty.NullableValue -> slicedProperty.value
            else -> never
        } as TProperty

        binding.applyToUpdateOrInsert(insertStatement, value)
    }

    /**
     * Inserts [entity] into the table
     *
     * @param forBinding   the binding this call is made for. used internally when a binding needs extra data or context
     * @param parentEntity the entity to which the binding specified in [forBinding] belongs
     *
     * @return the entity itself if the insert was successful
     */
    open suspend fun insert(entity: TEntity, forBinding: SqlBinding<*, *, *>? = null, parentEntity: Entity? = null): Sr<TEntity> = Wrap {
        if (isPolymorphicVariantTable())
            return@Wrap this.baseTableSealedClass?.table?.insert(entity) ?: error("cannot access base table")

        require (forBinding == null || parentEntity != null) { "call to insert() for a binding must specify parent entity" }

        val entityExistsInDb = transaction {
            select { uuid eq entity.uuid }.toSet()
        }.size == 1

        if (entityExistsInDb) {
            when (forBinding) {
                is SqlBinding.OneToOne,
                is SqlBinding.OneToOneOrNone,
                is SqlBinding.OneToMany<*, *>, // OneToX and parent insertion require entity to not already exist in DB
                null -> {
                    var message = "entity '${entity.uuid}' of type '${entity::class.simpleName}' already exists in table '${this.tableName}'"
                    if (forBinding != null)
                        message += " (while applying binding of type ${forBinding::class.simpleName} on property ${forBinding.property.klass.simpleName}::${forBinding.property.name})"

                    throw IllegalStateException(message)
                }
                else -> never
            }
        }

        @Suppress("UNCHECKED_CAST")
        for (binding in bindings.filter { it is SqlBinding.OneToOne || it is SqlBinding.OneToOneOrNone }) {
            binding as SqlBinding<TEntity, Entity?, UUID?>

            val instance = binding.property.get(entity) ?: break
            val instanceClassTable = instance::class.table as EntityTable<Entity>

            instanceClassTable.insert(instance, binding, entity).get()
        }

        transaction {
            insert {
                if (forBinding is SqlBinding.OneToMany<*, *>)
                    it[forBinding.otherTableFkToPkCol] = parentEntity!!.uuid

                for (binding in bindings) {
                    if (binding !is SqlBinding.OneToMany<*, *> && binding !is SqlBinding.OneToManyValues<*, *>)
                        applyBindingToInsertOrUpdate(entity, it, binding)
                }
            }
        }

        for (binding in bindings.filterIsInstance<SqlBinding.OneToManyValues<TEntity, Any>>()) {
            val instances = binding.property.get(entity)
            val bindingTable = binding.otherTable

            transaction {
                bindingTable.batchInsert(instances) { item ->
                    binding.insertionFunction(entity, item, this)
                }
            }
        }
    }.map { entity }.also {
        if (it is SuspendableResult.Failure) // If the main insertion failed don't try to insert OTO entities
            return@also

        // Handle entity manyref bindings in another tx because Exposed doesn't support deferred constraints

        for (binding in bindings.filterIsInstance<SqlBinding.OneToMany<TEntity, Entity>>()) {
            binding.property.get(entity).forEach { item ->
                binding.otherTable.insert(item, forBinding = binding, parentEntity = entity).get()
            }
        }
    }

    /**
     * Updates [entity] into the table using it's uuid for finding the old version
     *
     * @return whether or not the update was successful
     */
    open suspend fun update(entity: TEntity): Sr<TEntity> = query {
        if (isPolymorphicVariantTable())
            return@query this.baseTableSealedClass?.table?.update(entity) ?: error("cannot access base table")

        this.update({ uuid eq entity.uuid }) {
            for (binding in bindings) {
                if (binding !is SqlBinding.OneToManyValues<*, *>)
                    applyBindingToInsertOrUpdate(entity, it, binding)
            }
        }

        for (binding in bindings.filterIsInstance<SqlBinding.OneToManyValues<TEntity, Any>>()) {
            val newInstances = binding.property.get(entity)

            binding.otherTable.deleteWhere { binding.otherTableFkToPkCol eq entity.uuid }

            binding.otherTable.batchInsert(newInstances) { item -> binding.insertionFunction(entity, item, this) }
        }
    }.map { entity }.also {
        // Handle entity manyref bindings in another tx because Exposed doesn't support deferred constraints

        for (binding in bindings.filterIsInstance<SqlBinding.OneToMany<TEntity, Entity>>()) {
            transaction {
                binding.otherTable.deleteWhere { binding.otherTableFkToPkCol eq entity.uuid }
            }

            binding.property.get(entity).forEach { item -> binding.otherTable.insert(item, forBinding = binding, parentEntity = entity).get() }
        }
    }

    /**
     * Deletes [entity] from table using it's uuid for finding the item to delete
     *
     * @return whether or not the deletion was successful
     */
    open suspend fun delete(entity: TEntity): Boolean = query {
        unwrappedQuery {
            this.deleteWhere { uuid eq entity.uuid }
        }

        if (isPolymorphicVariantTable())
            baseTableSealedClass?.table?.delete(entity)
    }.assertGet().let { true }

    val uuid = uuid("uuid")

    override val primaryKey = PrimaryKey(uuid)

    override val tableName get() =
        if (isPolymorphicVariantTable())
            (baseTableSealedClass!!.simpleName?.replace(Regex("(\\w)([A-Z])"), "$1_$2")?.toLowerCase() ?: "?") + "_variant_" + this.klass.simpleName
        else super.tableName

}
