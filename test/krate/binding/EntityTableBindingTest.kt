package krate.binding

import krate.models.EntityTable
import krate.extensions.parentKey
import krate.extensions.weaKey
import krate.DatabaseConnectedTest

import reflectr.entity.Entity
import reflectr.extensions.okHandle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher

import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction

import java.lang.IllegalStateException
import java.util.*

/**
 * Tests all [SqlBinding] variants
 */
@ExperimentalCoroutinesApi
class EntityTableBindingTest : DatabaseConnectedTest(Cities, Houses, People) {

    @SqlTable(People::class)
    class Person(val name: String, val hobby: String? = null, uuid: UUID = UUID.randomUUID()) : Entity(uuid) {
        override fun toString() = "Person(name='$name', hobby=$hobby)"
    }

    @SqlTable(Cities::class)
    class City(val nname: String, val population: Int, uuid: UUID = UUID.randomUUID()) : Entity(uuid) {
        override fun toString() = "City(nname='$nname', population=$population)"
    }

    @SqlTable(Houses::class)
    class House(val length: Int, val width: Int, val occupants: List<Person>, val city: City, val color: String? = null, uuid: UUID = UUID.randomUUID()) : Entity(uuid) {
        override fun toString() = "House(length=$length, width=$width, occupants=$occupants, city=$city, color=$color)"
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

    object Cities : EntityTable<City>(City::class, name = "cities") {

        val name       = text ("name")
        val population = integer("population")

        init {
            bind(uuid, City::uuid)
            bind(name, City::nname)
            bind(population, City::population)
        }

    }

    object Houses : EntityTable<House>(House::class, name = "houses") {

        val length = integer   ("length")
        val width  = integer   ("width")
        val city   = parentKey ("city", Cities)
        val color  = text      ("color").nullable()

        init {
            bind(uuid, House::uuid)         // SqlBinding.Value
            bind(length, House::length)     // SqlBinding.Value
            bind(width, House::width)       // SqlBinding.Value
            bind(city, House::city)         // SqlBinding.OneToOne
            bind(color, House::color)       // SqlBinding.Value
            bind(People, House::occupants)  // SqlBinding.OneToMany
        }

    }

    @Test fun `should insert and select with non-existing child entities properly`() = runBlocking(TestCoroutineDispatcher()) {
        val rose = Person("Rose")
        val ben = Person("Ben")

        val city = City("Montreal", 1_760_000)

        val house = House (
            length = 150,
            width = 175,
            city = city,
            occupants = listOf(rose, ben)
        )

        Houses.insert(house).get()

        val selectedHouse = Houses.obtain(queryContext(), house.uuid).get()

        assertEquals(150, selectedHouse.length)
        assertEquals(175, selectedHouse.width)
        assertEquals(city.toString(), selectedHouse.city.toString())
        assertEquals(setOf(rose, ben).map { it.name }, selectedHouse.occupants.map { it.name })
    }

    @Test fun `should not insert and select with existing child entities properly`() = runBlocking(TestCoroutineDispatcher()) {
        val rose = Person("Rose")
        val ben = Person("Ben")

        val city = City("Montreal", 1_760_000)

        val house = House (
            length = 150,
            width = 175,
            city = city,
            occupants = listOf(rose, ben)
        )

        assertThrows(IllegalStateException::class.java) {
            blockingTransaction {
                Houses.insert(house).get()
            }
        }
    }

    @Test fun `should obtain listing properly`() = runBlocking(TestCoroutineDispatcher()) {
        val rose = Person("Rose")
        val ben = Person("Ben")

        val city = City("Montreal", 1_760_000)

        val house = House (
            length = 150,
            width = 175,
            city = city,
            occupants = listOf(rose, ben)
        ).also { println(it.uuid) }

        val kira = Person("Kira")
        val james = Person("James")

        val secondCity = City("New York City", 9_420_000)

        val secondHouse = House (
            length = 200,
            width = 170,
            city = secondCity,
            occupants = listOf(kira, james)
        )

        Houses.insert(house).get()
        Houses.insert(secondHouse).get()

        val obtainedHouses = Houses.obtainListing (
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

    @Test fun `should update properly`() = runBlocking(TestCoroutineDispatcher()) {
        val rose = Person("Rose")
        val ben = Person("Ben")

        val city = City("Montreal", 1_760_000)

        val house = House (
            length = 150,
            width = 175,
            city = city,
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

    @BeforeEach fun `delete everything from tables` () {
        setOf(Cities, Houses, People).forEach { table -> transaction { table.deleteAll() } }
    }

}
