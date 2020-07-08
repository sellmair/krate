package krate

import krate.models.EntityTable
import krate.extensions.weaKey
import krate.annotations.SqlTable

import reflectr.entity.Entity
import reflectr.extensions.okHandle

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested

import kotlinx.coroutines.runBlocking

import java.util.*

class EntityTableBindingTest : DatabaseConnectedTest(Houses, People) {

    @SqlTable(People::class)
    class Person(val name: String, uuid: UUID = UUID.randomUUID()) : Entity(uuid)

    @SqlTable(Houses::class)
    class House(val length: Int, val width: Int, val occupants: List<Person>, uuid: UUID = UUID.randomUUID()) : Entity(uuid)

    object People : EntityTable<Person>(Person::class, name = "people") {

        val name  = text   ("name")
        val house = weaKey ("house", Houses)

        init {
            bind(uuid, Person::uuid)
            bind(name, Person::name)
        }

    }

    object Houses : EntityTable<House>(House::class, name = "houses") {

        val length = integer ("length")
        val width  = integer ("width")

        init {
            bind(uuid, House::uuid)
            bind(length, House::length)
            bind(width, House::width)
            bind(People, House::occupants)
        }

    }

    @Nested
    inner class EntityManyRef {

        @Test fun `should insert and select properly`() {
            val rose = Person("Rose")
            val ben = Person("Ben")

            val house = House(length = 150, width = 175, occupants = listOf(rose, ben))

            runBlocking {
                Houses.insert(house).get()

                val selectedHouse = Houses.obtain(queryContext(), house.uuid).get()

                assertEquals(150, selectedHouse.length)
                assertEquals(175, selectedHouse.width)
                assertEquals(setOf(rose, ben).map { it.name }, selectedHouse.occupants.map { it.name })

                transaction {
                    Houses.deleteAll()
                }
            }
        }

        @Test fun `should update entity manyref property properly`() {
            val rose = Person("Rose")
            val ben = Person("Ben")

            val house = House(length = 150, width = 175, occupants = listOf(rose, ben)).also { println(it.uuid) }

            runBlocking {
                Houses.insert(house).get()
                val newHouse = with (queryContext()) {
                    house.update(mapOf(House::occupants.okHandle!! to listOf(Person("Kira"), Person("James")))).get()
                }
                Houses.update(newHouse).get()

                val selectedHouse = Houses.obtain(queryContext(), house.uuid).get()

                assertEquals(listOf("Kira", "James"), selectedHouse.occupants.map { it.name })

                transaction {
                    Houses.deleteAll()
                }
            }
        }

    }

}
