package com.davidferrand.coroutinessample

import org.hamcrest.CoreMatchers.equalTo
import org.junit.After
import org.junit.Assert.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith


/**
 * Test for [Agent]
 */
class AgentTest {
     private lateinit var underTest: Agent

    @Before
    fun setUp() {
//        underTest = com.davidferrand.coroutinessample.Agent()
    }

    @Test
    fun test() {
    }

    // Agent is responsible for:
    // - reading from local
    // - reading from remote
    // - updating local
    // - respecting timeout

    // LOCAL X +        API fails
    // LOCAL X +        API succeeds <5s +  LOCAL write fails
    // LOCAL X +        API succeeds <5s +  LOCAL write succeeds
    // LOCAL X +        API succeeds >5s +  LOCAL write fails
    // LOCAL X +        API succeeds >5s +  LOCAL write succeeds

    // To test with X taking the values: empty, stale, fresh, read fails
    // Note: if we manage to ensure that LOCAL write failure will not affect the Agent,
    // we can simplify the tests further


}