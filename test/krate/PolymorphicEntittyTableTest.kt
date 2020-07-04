package krate

import reflectr.entity.Entity

import krate.annotations.SqlTable

import org.jetbrains.exposed.sql.UUIDColumnType

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class PolymorphicEntittyTableTest {

    @SqlTable(TestTable::class)
    sealed class TestEntity(val name: String, val age: Int) : Entity() {

        @SqlTable(TestTable.A::class)
        class A(val extra: String, name: String, age: Int) : TestEntity(name, age)

        @SqlTable(TestTable.B::class)
        class B(val another: Float, name: String, age: Int) : TestEntity(name, age)

    }

    object TestTable : PolymorphicEntityTable<TestEntity>(TestEntity::class) {

        val name = text    ("name")
        val age  = integer ("age")

        init {
            bind(uuid, TestEntity::uuid)
            bind(name, TestEntity::name)
            bind(age, TestEntity::age)
        }

        object A : EntityTable<TestEntity.A>() {
            val extra = text ("extra")

            init {
                bind(extra, TestEntity.A::extra)
            }
        }

        object B : EntityTable<TestEntity.B>() {
            val another = float ("another")

            init {
                bind(another, TestEntity.B::another)
            }
        }

    }

    @Test fun `should work bruh`() {
        assertTrue(TestTable.columns.any { it.name == "variant_id" && it.columnType is UUIDColumnType })
        TestTable.variantTables.forEach { (_, vt) ->
            assertTrue(vt.columns.any { it.name == "variant_id" && it.columnType is UUIDColumnType })
        }
    }

}
