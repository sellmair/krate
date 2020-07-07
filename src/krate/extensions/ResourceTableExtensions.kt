package krate.extensions

import krate.models.EntityTable

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

import java.util.*

/**
 * Returns the column in the left side table with a foreign key to [other]'s `uuid` column, or null if none or multiple exist.
 *
 * @author Benjozork
 */
@Suppress("UNCHECKED_CAST")
fun Table.foreignKeyTo(other: EntityTable<*>): Column<UUID>?
        = this.columns.singleOrNull { it.referee == other.uuid } as? Column<UUID>
