package krate.extensions

import reflectr.entity.Entity
import krate.models.DatabaseContext
import krate.models.QueryContext
import krate.binding.table
import krate.models.Repository

import kotlin.reflect.KClass

/**
 * Provides a repository for [TEntity]
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified TEntity : Entity> DatabaseContext.repository(): Repository<TEntity> =
    this.repoCache.findOr(TEntity::class) {
        Repository(TEntity::class.table) as Repository<Entity>
    }.get() as Repository<TEntity>

/**
 * Provides a repository for [TEntity]
 */
@Suppress("UNCHECKED_CAST")
fun <TEntity : Entity> DatabaseContext.repository(klass: KClass<out TEntity>): Repository<TEntity> =
    this.repoCache.findOr(klass) {
        Repository(klass.table) as Repository<Entity>
    }.get() as Repository<TEntity>

/**
 * Provides a repository for [TEntity]
 */
inline fun <reified TEntity : Entity> QueryContext.repository(): Repository<TEntity> =
    databaseContext.repository()

/**
 * Provides a repository for [TEntity]
 */
inline fun <reified TEntity : Entity> QueryContext.repository(klass: KClass<out TEntity>): Repository<TEntity> =
    databaseContext.repository(klass)
