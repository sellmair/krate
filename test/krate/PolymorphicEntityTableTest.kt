package krate

import krate.models.*
import krate.annotations.SqlTable
import krate.util.MapCache

import reflectr.entity.Entity
import reflectr.extensions.okHandle

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.ForeignKeyConstraint
import org.jetbrains.exposed.sql.ReferenceOption

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Assertions.*

import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

import java.util.*

@DisplayName("PolymorphicEntityTable basic CRUD operations test")
class PolymorphicEntityTableTest : DatabaseConnectedTest(TestTable, TestTable.A, TestTable.B) {

    @SqlTable(TestTable::class)
    sealed class TestEntity(val name: String, val age: Int, uuid: UUID = UUID.randomUUID()) : Entity(uuid) {

        @SqlTable(TestTable.A::class)
        class A(val extra: String, name: String, age: Int, uuid: UUID = UUID.randomUUID()) : TestEntity(name, age, uuid)

        @SqlTable(TestTable.B::class)
        class B(val another: Float, name: String, age: Int, uuid: UUID = UUID.randomUUID()) : TestEntity(name, age, uuid)

    }

    object TestTable : PolymorphicEntityTable<TestEntity>(klass = TestEntity::class, name = "test_entity") {

        val name = text    ("name")
        val age  = integer ("age")

        init {
            bind(uuid, TestEntity::uuid)
            bind(name, TestEntity::name)
            bind(age, TestEntity::age)
        }

        object A : EntityTable<TestEntity.A>(TestEntity.A::class) {
            val extra = text ("extra")

            init {
                bind(extra, TestEntity.A::extra)
            }
        }

        object B : EntityTable<TestEntity.B>(TestEntity.B::class) {
            val another = float ("another")

            init {
                bind(another, TestEntity.B::another)
            }
        }

    }

    @Test fun `variant table uuid columns should have appropriate FKs to base table uuid column`() {
        TestTable.variantTables.forEach { (_, vt) ->
            assertTrue(vt.uuid.foreignKey == ForeignKeyConstraint(TestTable.uuid, vt.uuid, ReferenceOption.RESTRICT, ReferenceOption.CASCADE, null))
        }
    }

    @Test fun `should generate correct name for base and variant tables`() {
        assertEquals("test_entity", TestTable.tableName)
        TestTable.variantTables.forEach { (klass, vt) ->
            assertEquals("test_entity_variant_${klass.simpleName}", vt.tableName)
        }
    }

    private fun <T> blockingTransaction(block: suspend Transaction.() -> T) = transaction { runBlocking { block() } }

    private fun queryContext() = object : QueryContext {
        override val databaseContext = object : DatabaseContext {
            override val repoCache = MapCache<KClass<out Entity>, Repository<Entity>>()
        }

        override val entityCache = MapCache<UUID, Entity>()

        override val objectMapper = jacksonObjectMapper()
    }

    @Test fun `should be able to insert and select instance of variant in base table`() {
        val entity = TestEntity.A("fries", "James", 19)

        assertDoesNotThrow {
            blockingTransaction {
                TestTable.insert(entity).get()
            }
        }

        val insertedEntity = blockingTransaction {
            TestTable.obtain(queryContext(), entity.uuid)
        }.get() as TestEntity.A

        setOf(TestEntity.A::uuid, TestEntity.A::name, TestEntity.A::age, TestEntity.A::extra).forEach { prop ->
            assertEquals(prop.get(entity), prop.get(insertedEntity))
        }
    }

    @Test fun `should be able to insert and select instance of variant in variant table`() {
        val entity = TestEntity.A("fries", "James", 19)

        assertDoesNotThrow {
            blockingTransaction {
                TestTable.A.insert(entity).get()
            }
        }

        val insertedEntity = blockingTransaction {
            TestTable.A.obtain(queryContext(), entity.uuid)
        }.get()

        setOf(TestEntity.A::uuid, TestEntity.A::name, TestEntity.A::age, TestEntity.A::extra).forEach { prop ->
            assertEquals(prop.get(entity), prop.get(insertedEntity))
        }
    }

    @Test fun `should be able to delete instance of variant in base table`() {
        assertDoesNotThrow {
            blockingTransaction {
                val entity = TestEntity.A("fries", "James", 19)

                TestTable.insert(entity).get()
                TestTable.delete(entity)

                val rowsInBaseTable = TestTable.select { TestTable.uuid eq entity.uuid }.toList()
                val rowsInVariantTable = TestTable.A.select { TestTable.A.uuid eq entity.uuid }.toList()

                assertEquals(0, rowsInBaseTable.size)
                assertEquals(0, rowsInVariantTable.size)
            }
        }
    }

    @Test fun `should be able to delete instance of variant in variant table`() {
        assertDoesNotThrow {
            blockingTransaction {
                val entity = TestEntity.A("fries", "James", 19)

                TestTable.A.insert(entity).get()
                TestTable.A.delete(entity)

                val rowsInBaseTable = TestTable.select { TestTable.uuid eq entity.uuid }.toList()
                val rowsInVariantTable = TestTable.A.select { TestTable.A.uuid eq entity.uuid }.toList()

                assertEquals(0, rowsInBaseTable.size)
                assertEquals(0, rowsInVariantTable.size)
            }
        }
    }

    @Test fun `should be able to update instance of variant in base table`() {
        assertDoesNotThrow {
            blockingTransaction {
                val entity = TestEntity.A("fries", "James", 19)

                TestTable.insert(entity).get()

                with (queryContext()) {
                    val updated = entity.update(mapOf(TestEntity.A::extra.okHandle!! to "burger", TestEntity::name.okHandle!! to "Carl")).get()
                    TestTable.update(updated)
                }

                val updatedEntity = TestTable.obtain(queryContext(), entity.uuid).get() as TestEntity.A

                assertEquals("burger", updatedEntity.extra)
                assertEquals("Carl", updatedEntity.name)
            }
        }
    }

    @Test fun `should be able to update instance of variant in variant table`() {
        assertDoesNotThrow {
            blockingTransaction {
                val entity = TestEntity.A("fries", "James", 19)

                TestTable.A.insert(entity).get()

                with (queryContext()) {
                    val updated = entity.update(mapOf(TestEntity.A::extra.okHandle!! to "burger", TestEntity::name.okHandle!! to "Carl")).get()
                    TestTable.A.update(updated)
                }

                val updatedEntity = TestTable.A.obtain(queryContext(), entity.uuid).get()

                assertEquals("burger", updatedEntity.extra)
                assertEquals("Carl", updatedEntity.name)
            }
        }
    }

}
