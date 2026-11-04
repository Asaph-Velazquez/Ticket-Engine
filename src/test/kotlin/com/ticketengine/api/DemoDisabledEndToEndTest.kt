package com.ticketengine.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = ["ticket.demo-enabled=false"])
class DemoDisabledEndToEndTest @Autowired constructor(
    private val http: TestRestTemplate,
    private val json: ObjectMapper
) {
    @Test
    fun `sandbox deshabilitado rechaza emision incluso con administrador valido`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(Files.readString(Path.of("/run/secrets/ticket.admin-token")).trim())
        }
        val before = count(headers)
        val start = Instant.now().plusSeconds(86_400)
        val request = mapOf(
            "paymentId" to UUID.randomUUID().toString(),
            "eventId" to UUID.randomUUID().toString(),
            "buyerId" to UUID.randomUUID().toString(),
            "seatId" to "B-17",
            "eventName" to "Concierto sandbox deshabilitado",
            "venue" to "Auditorio de pruebas",
            "eventStartsAt" to start.toString(),
            "eventEndsAt" to start.plusSeconds(7200).toString(),
            "amountMinor" to 125_000L,
            "currency" to "MXN",
            "paymentStatus" to "PAID"
        )
        val result = http.exchange(
            "/api/demo/tickets", HttpMethod.POST,
            HttpEntity(json.writeValueAsBytes(request), headers), String::class.java
        )
        assertEquals(HttpStatus.FORBIDDEN, result.statusCode)
        assertEquals(before, count(headers), "El sandbox deshabilitado no persiste boletos")
    }

    private fun count(headers: HttpHeaders): Long {
        val result = http.exchange("/api/tickets?limit=1", HttpMethod.GET, HttpEntity<Any>(headers), String::class.java)
        assertEquals(HttpStatus.OK, result.statusCode)
        return json.readTree(result.body).path("total").asLong()
    }
}
