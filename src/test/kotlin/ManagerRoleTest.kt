package com.deviante

import com.deviante.repository.ManagerRepository
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
