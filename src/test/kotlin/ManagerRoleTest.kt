package com.deviante

import com.deviante.dto.SCHEDULE_STATUSES
import com.deviante.dto.traceNoteError
import com.deviante.model.ManagerRecord
import com.deviante.repository.ManagerRepository
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun record(role: String) =
        ManagerRecord(UUID.randomUUID(), UUID.randomUUID(), "x@y.z", "X", role, OffsetDateTime.now(), OffsetDateTime.now())

    @Test
    fun `only owner admin and manager can edit`() {
        assertEquals(setOf("owner", "admin", "manager"), ManagerRecord.EDIT_ROLES)
        listOf("owner", "admin", "manager").forEach { assertTrue(record(it).canEdit(), it) }
        listOf("operator", "technician", "mentor").forEach { assertFalse(record(it).canEdit(), it) }
    }

    @Test
    fun `editors and operator write trace notes`() {
        assertEquals(setOf("owner", "admin", "manager", "operator"), ManagerRecord.NOTE_ROLES)
        listOf("owner", "admin", "manager", "operator").forEach { assertTrue(record(it).writesTraceNotes(), it) }
        listOf("technician", "mentor").forEach { assertFalse(record(it).writesTraceNotes(), it) }
    }

    @Test
    fun `delete and catalog roles stay unchanged`() {
        assertEquals(setOf("owner", "admin", "manager"), ManagerRecord.DELETE_ROLES)
        assertEquals(setOf("owner", "admin"), ManagerRecord.CATALOG_ROLES)
    }

    @Test
    fun `only technician is technician`() {
        assertTrue(record("technician").isTechnician())
        listOf("owner", "admin", "manager", "operator", "mentor").forEach { assertFalse(record(it).isTechnician(), it) }
    }

    @Test
    fun `objects can be hidden only from operator and technician`() {
        assertEquals(setOf("operator", "technician"), ManagerRecord.HIDEABLE_ROLES)
    }

    @Test
    fun `schedule statuses match the board`() {
        assertEquals(setOf("planned", "in_progress", "completed", "canceled"), SCHEDULE_STATUSES)
    }

    @Test
    fun `trace note must be non blank and at most 2000 chars`() {
        assertNull(traceNoteError("ok", "A"))
        assertNull(traceNoteError("x".repeat(2000), ""))
        assertNotNull(traceNoteError("   ", "A"))
        assertNotNull(traceNoteError("x".repeat(2001), "A"))
        assertNotNull(traceNoteError("ok", "a".repeat(256)))
    }
}
