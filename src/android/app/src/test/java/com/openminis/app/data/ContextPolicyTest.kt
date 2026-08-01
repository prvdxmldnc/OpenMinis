package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPolicyTest {
    @Test
    fun `16K local model offloads early and permits manual compact`() {
        val policy = ContextPolicy.forContextWindow(16_384)

        assertEquals(11_468, policy.offloadThreshold)
        assertEquals(9_011, policy.offloadTarget)
        assertEquals(0, policy.compactThreshold)
        assertTrue(policy.exhaustedOnly)
        assertTrue(policy.manualCompactAllowed)
        assertFalse(policy.shouldOffload(11_467))
        assertTrue(policy.shouldOffload(11_468))
        assertEquals(
            ContextPolicy.CheckResult.EXHAUSTED,
            policy.check(11_468, 16_384),
        )
    }

    @Test
    fun `provider overflow matcher recognizes llama context error only`() {
        assertTrue(
            ContextPolicy.isProviderContextOverflow(
                "request (16523 tokens) exceeds the available context size (16384 tokens)",
            ),
        )
        assertTrue(ContextPolicy.isProviderContextOverflow("context_length_exceeded"))
        assertFalse(
            ContextPolicy.isProviderContextOverflow(
                "Input should be less than or equal to 2048 for max_tokens",
            ),
        )
    }
}
