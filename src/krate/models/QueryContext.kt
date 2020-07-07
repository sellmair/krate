package krate.models

import krate.util.Sr
import krate.util.SrList
import krate.util.MapCache
import krate.extensions.repository
import reflectr.entity.Entity
import reflectr.models.Mapped
import reflectr.models.PropMap
import reflectr.entity.update
import reflectr.entity.instantiation.construct

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder

import com.fasterxml.jackson.databind.ObjectMapper
import reflectr.util.MappedData

import java.util.*

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * Interface that allows creating a context in which entities are queried, created or modified.
 *
 * Allows for in-request caching of entities by their IDs. It also brings in scope extension functions to [EntityTable]
 * as well as overloads for various reflection functions like [construct][reflectr.entity.instantiation.construct] and
 * [update][reflectr.entity.update] that allow a consumer with an implementation of this as receiver to call
 * those functions without passing in lookup functions or [QueryContext] instances.
 *
 * @author Benjozork
 */
interface QueryContext {

    val databaseContext: DatabaseContext

    val entityCache: MapCache<UUID, Entity>

    val objectMapper: ObjectMapper

    /** @see [Repository.getAll] */
    suspend fun <TEntity : Entity> Repository<TEntity>.obtainAll(limit: Int): SrList<TEntity> =
        this.getAll(this@QueryContext, limit)

    /** @see [Repository.get] */
    suspend fun <TEntity : Entity> Repository<TEntity>.obtain(id: UUID): Sr<TEntity> =
        this@QueryContext.entityCache.findOrAsync(id) {
            this.get(this@QueryContext, id).get()
        }

    /** @see [Repository.get] */
    suspend fun <TEntity : Entity> Repository<TEntity>.obtainListing (
        selectCondition: SqlExpressionBuilder.() -> Op<Boolean>,
        quantity: Int,
        page: Int,
        orderBy: Column<*>,
        sortOrder: SortOrder = SortOrder.ASC
    ): Sr<Pair<List<TEntity>, Boolean>> =
        this.queryListing(this@QueryContext, selectCondition, quantity, page, orderBy, sortOrder)

    /** @see [Repository.update] */
    suspend fun <TEntity : Entity> Repository<TEntity>.update(res: TEntity, rawData: MappedData): Sr<TEntity> =
        this.update(this@QueryContext, res, rawData)

    /** @see [Repository.updateWithProperties] */
    suspend fun <TEntity : Entity> Repository<TEntity>.updateWithProperties (
        entity: TEntity,
        data: Map<out KProperty1<TEntity, Any>, Any>
    ): Sr<TEntity> =
        this.updateWithProperties(this@QueryContext, entity, data)

    /** @see [reflectr.entity.instantiation.construct] */
    suspend fun <TMapped : Mapped> KClass<out TMapped>.construct (
        data:               MappedData,
        externallyProvided: Set<PropMap.PropertyHandle.Ok> = setOf()
    ): Sr<TMapped> =
        this.construct(data, objectMapper, { klass, uuid -> this@QueryContext.repository(klass).get(queryContext = this@QueryContext, id = uuid) }, externallyProvided)

    /** @see [reflectr.entity.update] */
    suspend fun <R : Mapped> R.update(rawData: MappedData): Sr<R> =
        this.update(rawData, this@QueryContext.objectMapper, fetcher = { klass, id -> this@QueryContext.repository(klass).get(id = id, queryContext = this@QueryContext) })

}

