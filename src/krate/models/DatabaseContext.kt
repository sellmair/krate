package krate.models

import krate.models.Repository
import krate.util.MapCache
import reflectr.entity.Entity

import kotlin.reflect.KClass

/**
 * Interface that gives database implementation context. Stores cached [repositories][Repository].
 *
 * @author Benjozork
 */
interface DatabaseContext {

    val repoCache: MapCache<KClass<out Entity>, Repository<Entity>>

}
