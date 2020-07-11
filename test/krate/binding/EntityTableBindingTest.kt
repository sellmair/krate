package krate.binding

import krate.models.EntityTable
import krate.extensions.weaKey
import krate.DatabaseConnectedTest

import reflectr.entity.Entity
import reflectr.extensions.okHandle

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher

import java.util.*

@ExperimentalCoroutinesApi
class EntityTableBindingTest : DatabaseConnectedTest(Houses, People) {

    @SqlTable(People::class)
    class Person(val name: String, val hobby: String? = null, uuid: UUID = UUID.randomUUID()) : Entity(uuid) {
        override fun toString() = "Person(name='$name', hobby=$hobby)"
    }

    @SqlTable(Houses::class)
    class House(val length: Int, val width: Int, val occupants: List<Person>, val color: String? = null, uuid: UUID = UUID.randomUUID()) : Entity(uuid) {
        override fun toString() = "House(length=$length, width=$width, color=$color, occupants=$occupants)"
    }

    object People : EntityTable<Person>(Person::class, name = "people") {

        val name  = text   ("name")
        val hobby = text   ("hobby").nullable()
        val house = weaKey ("house", Houses)

        init {
            bind(uuid, Person::uuid)
            bind(name, Person::name)
            bind(hobby, Person::hobby)
        }

    }

    object Houses : EntityTable<House>(House::class, name = "houses") {

        val length = integer ("length")
        val width  = integer ("width")
        val color  = text    ("color").nullable()

        init {
            bind(uuid, House::uuid)
            bind(length, House::length)
            bind(width, House::width)
            bind(color, House::color)
            bind(People, House::occupants)
        }

    }

    @Nested
    inner class EntityManyRef: AfterEachCallback {

        @Test fun `should insert and select properly`() = runBlocking(TestCoroutineDispatcher()) {
            val rose = Person("Rose")
            val ben = Person("Ben")

            val house = House (
                length = 150,
                width = 175,
                occupants = listOf(rose, ben)
            )


            Houses.insert(house).get()

            val selectedHouse = Houses.obtain(queryContext(), house.uuid).get()

            assertEquals(150, selectedHouse.length)
            assertEquals(175, selectedHouse.width)
            assertEquals(setOf(rose, ben).map { it.name }, selectedHouse.occupants.map { it.name })

        }

        @Test fun `should obtain listing with entity manyref`() = runBlocking(TestCoroutineDispatcher()) {
            val rose = Person("Rose")
            val ben = Person("Ben")

            val house = House (
                length = 150,
                width = 175,
                occupants = listOf(rose, ben)
            ).also { println(it.uuid) }

            val kira = Person("Kira")
            val james = Person("James")

            val secondHouse = House (
                length = 200,
                width = 170,
                occupants = listOf(kira, james)
            )

            Houses.insert(house).get()
            Houses.insert(secondHouse).get()

            val obtainedHouses = Houses.obtainListing(
                queryContext = queryContext(),
                quantity = 5,
                page = 0,
                orderBy = Houses.width
            ).get()

            assertEquals(2, obtainedHouses.first.size)
            setOf(house, secondHouse).forEach { h ->
                assertTrue(h.toString() in obtainedHouses.first.map { it.toString() })
            }
        }

        @Test fun `should update entity manyref property properly`() = runBlocking(TestCoroutineDispatcher()) {
            val rose = Person("Rose")
            val ben = Person("Ben")

            val house = House (
                length = 150,
                width = 175,
                occupants = listOf(rose, ben)
            ).also { println(it.uuid) }

            Houses.insert(house).get()
            val newHouse = with (queryContext()) {
                house.update(mapOf(House::occupants.okHandle!! to listOf(Person("Kira"), Person("James")))).get()
            }
            Houses.update(newHouse).get()

            val selectedHouse = Houses.obtain(queryContext(), house.uuid).get()

            assertEquals(listOf("Kira", "James"), selectedHouse.occupants.map { it.name })
        }

        override fun afterEach(context: ExtensionContext?) {
            runBlocking { transaction { Houses.deleteAll() } }
        }
    }

}
