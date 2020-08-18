package krate.binding

import krate.models.EntityTable

import reflectr.entity.Entity

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Associates an [Entity] class to a [EntityTable]. Used for finding which table to operate on when using a `repository<...>()` call.
 *
 * @param table the table to associate
 *
 * @author Benjozork
 */
annotation class SqlTable (
    val table: KClass<out EntityTable<*>>
)

/**
 * Computed value that finds the [EntityTable] for a given subclass of [Entity] using the [SqlTable] annotation
 */
@Suppress("UNCHECKED_CAST")
val <R : Entity> KClass<R>.table: EntityTable<R>
    get() {
        val annotation = this.findAnnotation<SqlTable>() ?: error("no @SqlTable annotation on class ${this.simpleName}")

        return (annotation.table.objectInstance ?: error("class referenced in @SqlTable annotation must be singleton"))
                as EntityTable<R>
    }

/**
 * Computed value that finds the [EntityTable] for a given subclass of [Entity] using the [SqlTable] annotation or returns null if there is none
 */
@Suppress("UNCHECKED_CAST")
val <R : Entity> KClass<R>.safeTable: EntityTable<R>?
    get() {
        val annotation = this.findAnnotation<SqlTable>() ?: return null

        return (annotation.table.objectInstance ?: error("class referenced in @SqlTable annotation must be singleton"))
                as EntityTable<R>
    }
