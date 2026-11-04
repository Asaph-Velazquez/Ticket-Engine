package com.ticketengine.persistence

import com.ticketengine.TicketEngineApplication
import com.ticketengine.application.TicketEmissionService
import com.ticketengine.dto.PaymentConfirmationRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(classes = [TicketEngineApplication::class])
class TicketImmutabilityTest @Autowired constructor(
    private val service: TicketEmissionService,
    private val jdbc: JdbcTemplate,
    @Value("\${spring.datasource.url}") private val databaseUrl: String,
    @Value("\${test.bootstrap.password}") private val bootstrapPassword: String
) {
    @Test
    fun `rol runtime solo puede leer e insertar boletos`() {
        assertEquals("ticket_engine_app", jdbc.queryForObject("SELECT current_user", String::class.java))
        for (privilege in listOf("SELECT", "INSERT")) {
            assertTrue(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'tickets', ?)", Boolean::class.java, privilege)!!)
        }
        for (privilege in listOf("UPDATE", "DELETE", "TRUNCATE", "TRIGGER", "REFERENCES")) {
            assertFalse(jdbc.queryForObject("SELECT has_table_privilege(current_user, 'tickets', ?)", Boolean::class.java, privilege)!!)
        }
        assertFalse(jdbc.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean::class.java)!!)
        assertFailsWith<DataAccessException> { jdbc.update("DELETE FROM tickets WHERE false") }
        assertFailsWith<DataAccessException> { jdbc.update("UPDATE tickets SET venue = 'alterado' WHERE false") }
        assertFailsWith<DataAccessException> { jdbc.execute("TRUNCATE tickets") }
        assertFailsWith<DataAccessException> { jdbc.execute("ALTER TABLE tickets ADD COLUMN unsafe text") }
    }

    @Test
    fun `triggers tambien impiden modificar y truncar usando propietario en base de pruebas`() {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val ticket = service.emit(PaymentConfirmationRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "A-1", "Prueba de inmutabilidad", "Auditorio", now.plusSeconds(3600), now.plusSeconds(7200), 100L, "MXN", "PAID")).ticket
        // Credentials belong only to the isolated test-db; production runtime never receives this secret.
        assertTrue(databaseUrl.contains("ticket_engine_test"))
        DriverManager.getConnection(databaseUrl, "ticket_engine", bootstrapPassword).use { owner ->
            owner.prepareStatement("UPDATE tickets SET venue = 'alterado' WHERE ticket_id = ?").use { statement ->
                statement.setObject(1, ticket.ticketId)
                assertFailsWith<SQLException> { statement.executeUpdate() }
            }
            owner.prepareStatement("DELETE FROM tickets WHERE ticket_id = ?").use { statement ->
                statement.setObject(1, ticket.ticketId)
                assertFailsWith<SQLException> { statement.executeUpdate() }
            }
            owner.createStatement().use { statement -> assertFailsWith<SQLException> { statement.execute("TRUNCATE tickets") } }
        }
        assertEquals(ticket, service.get(ticket.ticketId))
    }
}
