package krate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

import krate.models.DatabaseContext
import krate.models.QueryContext
import krate.models.Repository
import krate.util.MapCache

import reflectr.entity.Entity

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

import epgx.models.PgTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineDispatcher

import kotlinx.coroutines.test.runBlockingTest
import kotlin.reflect.KClass

import java.util.*

@ExtendWith(DatabaseConnectedTest::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class DatabaseConnectedTest(private vararg val tables: PgTable = arrayOf())
    : BeforeEachCallback, AfterEachCallback {

    fun <T> blockingTransaction(block: suspend Transaction.() -> T) = transaction { runBlocking { block() } }

    fun queryContext() = object : QueryContext {
        override val databaseContext = object : DatabaseContext {
            override val repoCache = MapCache<KClass<out Entity>, Repository<Entity>>()
        }

        override val entityCache = MapCache<UUID, Entity>()

        override val objectMapper = jacksonObjectMapper()
    }

    @BeforeAll
    fun setUpDatabase() {
        val dbUrl = System.getenv("KRATE_TEST_DB_URL") ?: error("KRATE_TEST_DB_URL not set")

        val dbUser = System.getenv("KRATE_TEST_DB_USER") ?: error("KRATE_TEST_DB_USER not set")

        val dbPassword = System.getenv("KRATE_TEST_DB_PASSWORD") ?: error("KRATE_TEST_DB_PASSWORD not set")

        Database.connect(dbUrl, "org.postgresql.Driver", dbUser, dbPassword)

        transaction { SchemaUtils.drop(*tables) }
        transaction { SchemaUtils.create(*tables) }
    }

    override fun beforeEach(context: ExtensionContext?) {
        val name = context?.testMethod?.get()?.name ?: error("test method is null")

        println("=== [ Test : $name ] ===\n")
    }

    override fun afterEach(context: ExtensionContext?) = println()

    @AfterAll
    fun tearDownTables() {
        transaction { SchemaUtils.drop(*tables) }
    }

}
