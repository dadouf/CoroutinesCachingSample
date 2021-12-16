package com.davidferrand.coroutinessample

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Illustrate some properties of coroutines and some HOWTOs
 */
@RunWith(AndroidJUnit4::class)
class CoroutineRecipes {
    @Test
    fun threading() {
        // Note: the current thread would be the main thread if the test was run with @UiThreadTest
        log("1: on the current thread")

        CoroutineScope(Dispatchers.IO).launch {
            log("2: on a background thread")
        }

        CoroutineScope(Dispatchers.Main).launch {
            log("3: on the main thread")

            withContext(Dispatchers.IO) {
                log("4: on a background thread")
            }
        }

        Thread {
            log("5: on a background thread")

            CoroutineScope(Dispatchers.Main).launch {
                log("6: on the main thread")
            }
        }.start()

        // This test is close to real conditions so it doesn't have synchronization of the dispatchers
        // => artificially wait
        Thread.sleep(2000)
    }
}