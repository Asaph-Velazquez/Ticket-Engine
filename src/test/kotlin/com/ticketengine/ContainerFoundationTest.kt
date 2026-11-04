package com.ticketengine

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

@SpringBootTest(
    classes = [TicketEngineApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ContainerFoundationTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val jdbc: JdbcTemplate
) {
    @Test
    fun `Kotlin carga la aplicacion Java y consulta su salud por HTTP`() {
        val response = http.getForEntity("/actuator/health", Map::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("UP", response.body?.get("status"))
    }

    @Test
    fun `la aplicacion utiliza PostgreSQL exclusivo del modulo`() {
        assertEquals("ticket_engine_test", jdbc.queryForObject("select current_database()", String::class.java))
        assertEquals("ticket_engine_app", jdbc.queryForObject("select current_user", String::class.java))
        assertEquals("PostgreSQL", jdbc.dataSource!!.connection.use { it.metaData.databaseProductName })
    }

    @Test
    fun `el rol de aplicacion carece de privilegios administrativos y mutaciones`() {
        assertEquals(false, jdbc.queryForObject("select rolsuper from pg_roles where rolname = current_user", Boolean::class.java))
        listOf("UPDATE", "DELETE", "TRUNCATE", "TRIGGER", "REFERENCES").forEach { privilege ->
            assertEquals(false, jdbc.queryForObject("select has_table_privilege(current_user, 'tickets', ?)", Boolean::class.java, privilege))
        }
        assertEquals(false, jdbc.queryForObject("select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean::class.java))
    }
}
