package com.deviante

import com.deviante.dto.DeleteProcessRequest
import com.deviante.dto.PROCESS_DELETE_CONFIRMATION_PHRASE
import com.deviante.dto.validateProcessDeletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessDeletionTest {
    @Test
    fun `accepts exact process name and confirmation phrase`() {
        val errors = validateProcessDeletion(
            DeleteProcessRequest(
                processName = "Linha de Montagem",
                confirmationPhrase = PROCESS_DELETE_CONFIRMATION_PHRASE,
            ),
            expectedProcessName = "Linha de Montagem",
        )

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `rejects approximate deletion confirmation`() {
        val errors = validateProcessDeletion(
            DeleteProcessRequest(
                processName = "linha de montagem",
                confirmationPhrase = "quero excluir",
            ),
            expectedProcessName = "Linha de Montagem",
        )

        assertEquals(
            setOf("processName", "confirmationPhrase"),
            errors.keys,
        )
    }
}
