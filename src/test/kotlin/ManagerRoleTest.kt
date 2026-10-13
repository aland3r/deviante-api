package com.deviante

import com.deviante.model.ManagerRecord
import com.deviante.repository.ManagerRepository
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagerRoleTest {
    @Test
    fun `assigns owner role only to Alander accounts`() {
        assertEquals("owner", ManagerRepository.roleForEmail("design@alander.io"))
        assertEquals("owner", ManagerRepository.roleForEmail(" ALANDERAVILA@GMAIL.COM "))
    }

    @Test
    fun `assigns mentor role to Luiz and Eduardo`() {
        assertEquals("mentor", ManagerRepository.roleForEmail("pafileiro@gmail.com"))
        assertEquals("mentor", ManagerRepository.roleForEmail("erloures@gmail.com"))
    }

    @Test
    fun `keeps other authenticated users as managers`() {
        assertEquals("manager", ManagerRepository.roleForEmail("manager@example.com"))
    }

    @Test
    fun `assigns one role per demo account`() {
        assertEquals("admin", ManagerRepository.roleForEmail("demo-administrador@deviante.alander.io"))
        assertEquals("manager", ManagerRepository.roleForEmail("demo-gestor@deviante.alander.io"))
        assertEquals("operator", ManagerRepository.roleForEmail("demo-operador@deviante.alander.io"))
        assertEquals("technician", ManagerRepository.roleForEmail("demo-tecnico@deviante.alander.io"))
    }

    @Test
    fun `only admin and manager can delete`() {
        fun record(role: String) = ManagerRecord(UUID.randomUUID(), UUID.randomUUID(), "x@y.z", "X", role, OffsetDateTime.now(), OffsetDateTime.now())
        listOf("owner", "admin", "manager").forEach { assertTrue(record(it).canDelete(), it) }
        listOf("operator", "technician", "mentor").forEach { assertFalse(record(it).canDelete(), it) }
    }
}
