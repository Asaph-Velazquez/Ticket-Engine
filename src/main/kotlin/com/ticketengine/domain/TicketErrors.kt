package com.ticketengine.domain

class TicketValidationException(message: String) : RuntimeException(message)
class TicketConflictException(val code: String, message: String) : RuntimeException(message)
class TicketNotFoundException : RuntimeException("Boleto no encontrado")
