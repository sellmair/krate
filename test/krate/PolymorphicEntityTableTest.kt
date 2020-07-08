package krate

import krate.models.*
import krate.PolymorphicEntityTableTest.SimpleObjectTable.type
import krate.PolymorphicEntityTableTest.SimpleObjectTable.cte
import krate.extensions.parentKey
import krate.annotations.SqlTable
import krate.util.MapCache

import reflectr.entity.Entity
import reflectr.extensions.okHandle

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assertions.*

import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

import epgx.models.PgTable

import java.util.*

@DisplayName("PolymorphicEntityTable basic CRUD operations test")
class PolymorphicEntityTableTest : DatabaseConnectedTest(TestTable, TestTable.A, TestTable.B, ComplexTestTable, SimpleObjectTable, ComplexTestTable.A) {

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

    @SqlTable(ComplexTestTable::class)
    sealed class ComplexTestEntity(val name: String, val others: List<SimpleObject>, uuid: UUID = UUID.randomUUID()): Entity(uuid) {

        @SqlTable(ComplexTestTable.A::class)
        class A(val extra: String, name: String, others: List<SimpleObject>, uuid: UUID = UUID.randomUUID()) : ComplexTestEntity(name, others, uuid)

    }

    object ComplexTestTable : PolymorphicEntityTable<ComplexTestEntity>(klass = ComplexTestEntity::class, name = "complex_test_entity") {

        val name = text ("name")

        init {
            bind(uuid, ComplexTestEntity::uuid)
            bind(name, ComplexTestEntity::name)
            bind(SimpleObjectTable, ComplexTestEntity::others, { row -> SimpleObject(row[type]) }, { cte_, so, ub ->
                ub[type] = so.type
                ub[cte] = cte_.uuid
            })
        }

        object A : EntityTable<ComplexTestEntity.A>(ComplexTestEntity.A::class) {

            val extra = text ("extra")

            init {
                bind(extra, ComplexTestEntity.A::extra)
            }

        }

    }

    class SimpleObject(val type: String)

    object SimpleObjectTable : PgTable("simple_object") {

        val type = text      ("type")
        val cte  = parentKey ("cte", ComplexTestTable)

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

    @Nested
    inner class References {

        @Test fun `should be able to insert with manyref items in base property in base table`() {
            val othersObjects = Array(5) { SimpleObject(it.toString(16)) }.toList()

            val entity = ComplexTestEntity.A("hi !", "Henry", othersObjects)

            assertDoesNotThrow {
                blockingTransaction {
                    ComplexTestTable.insert(entity).get()

                    val insertedEntity = ComplexTestTable.obtain(queryContext(), entity.uuid).get() as ComplexTestEntity.A

                    assertEquals("hi !", insertedEntity.extra)
                    assertEquals("Henry", insertedEntity.name)
                    assertEquals(othersObjects.map { it.type }, insertedEntity.others.map { it.type })
                }
            }
        }

        @Test fun `should be able to insert with manyref items in base property in variant table`() {
            val othersObjects = Array(5) { SimpleObject(it.toString(16)) }.toList()

            val entity = ComplexTestEntity.A("hi !", "Henry", othersObjects)

            assertDoesNotThrow {
                blockingTransaction {
                    ComplexTestTable.A.insert(entity).get()

                    val insertedEntity = ComplexTestTable.A.obtain(queryContext(), entity.uuid).get()

                    assertEquals("hi !", insertedEntity.extra)
                    assertEquals("Henry", insertedEntity.name)
                    assertEquals(othersObjects.map { it.type }, insertedEntity.others.map { it.type })
                }
            }
        }

        @Test fun `should be able to delete instance of variant with manyref in base table`() {
            val othersObjects = Array(5) { SimpleObject(it.toString(16)) }.toList()

            val entity = ComplexTestEntity.A("hi !", "Henry", othersObjects)

            assertDoesNotThrow {
                blockingTransaction {
                    ComplexTestTable.insert(entity).get()
                    ComplexTestTable.delete(entity)

                    val rowsInManyRefTable = SimpleObjectTable.select { cte eq entity.uuid }.toList()

                    assertEquals(0, rowsInManyRefTable.size)
                }
            }
        }

        @Test fun `should be able to delete instance of variant with manyref in variant table`() {
            val othersObjects = Array(5) { SimpleObject(it.toString(16)) }.toList()

            val entity = ComplexTestEntity.A("hi !", "Henry", othersObjects)

            assertDoesNotThrow {
                blockingTransaction {
                    ComplexTestTable.A.insert(entity).get()
                    ComplexTestTable.A.delete(entity)

                    val rowsInManyRefTable = SimpleObjectTable.select { cte eq entity.uuid }.toList()

                    assertEquals(0, rowsInManyRefTable.size)
                }
            }
        }

    }

    @Nested
    inner class Listings {

        @Test fun `should be able to insert and select instances of multiple variants`() {
            transaction {
                TestTable.deleteAll()
            }

            val entities = listOf (
                TestEntity.A("fries", "James", 19),
                TestEntity.A("burgers", "Simon", 35),
                TestEntity.B(2.0f, "Michael", 14)
            )

            assertDoesNotThrow {
                blockingTransaction {
                    entities.forEach { entity -> TestTable.insert(entity) }

                    val selectedEntities = TestTable.obtainListing(queryContext(), { Op.TRUE }, 10, 0, TestTable.name).get()

                    assertEquals (
                        entities.map { it.uuid }.sorted(),
                        selectedEntities.first.map { it.uuid }.sorted()
                    )
                }
            }
        }

        @Test fun `should be able to insert and select instances of multiple variants with manyref items`() {
            transaction {
                ComplexTestTable.deleteAll()
            }

            val entities = listOf (
                ComplexTestEntity.A("something special", "Jane", listOf(SimpleObject("tool"), SimpleObject("desk"))),
                ComplexTestEntity.A("a boring thing", "Samuel", listOf(SimpleObject("cucumber"), SimpleObject("tomato"), SimpleObject("chair")))
            )

            assertDoesNotThrow {
                blockingTransaction {
                    entities.forEach { entity -> ComplexTestTable.insert(entity) }

                    val selectedEntities = ComplexTestTable.obtainListing(queryContext(), { Op.TRUE }, 10, 0, ComplexTestTable.name).get()

                    assertEquals (
                        entities.map { it.uuid }.sorted(),
                        selectedEntities.first.map { it.uuid }.sorted()
                    )
                }
            }
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
