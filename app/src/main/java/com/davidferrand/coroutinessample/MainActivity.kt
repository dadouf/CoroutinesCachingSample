package com.davidferrand.coroutinessample

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import java.util.*

/**
 * Resources used to achieve this:
 * - https://medium.com/androiddevelopers/coroutines-on-android-part-i-getting-the-background-3e0e54d20bb
 *
 * DISCOVERIES: re coroutines
 * - Side-effect work must be launch{}ed in a SEPARATE scope B than the current scope A (fire and
 *   forget on another thread and continue current co-routine in A directly). That way: (1) if A
 *   gets cancelled or errs, B doesn't.
 * - Errors NEVER propagate from INSIDE a launch{} block to OUTSIDE. So they must be caught within,
 *   otherwise they'll crash the app.
 * - Errors DO propagate from INSIDE withTimeout{} / coroutineScope{} block to OUTSIDE
 * - Errors in an async{} block are surfaced/thrown LATER, whenever job.await() is called. That
 *   is where await() must be wrapped with a try/catch.
 * - SupervisorJob is CRUCIAL for scopes that get reused. Without that, any error in the scope
 *   (even more prominent in async{} where we don't usually try/catch) cancels the whole scope for
 *   any future job.
 * - coroutineScope{} ensures that cancellation or failure of any child coroutine within the block
 *   causes cancellation of all other children in the block (explanation from [CoroutineScope]).
 *   That consequently DOES NOT apply to coroutines launched within coroutineScope{} but on a
 *   SEPARATE scope. So in short: coroutineScope{} is good practice.
 * - Which dispatcher should I use when launching heavy-work coroutines? In short: it does NOT matter
 *   when the whole chain is made of suspending functions (e.g. using Room/Retrofit).
 *   In case a function contains a blocking heavy work, it is responsible for using the dispatcher
 *   it needs via withContext{}; and callers of the (now suspending) function don't need to care!
 * - Even in the "worst case" where API fetch operation is done on Dispatchers.Main, we ensure that
 *   it does NOT block the UI thread by doing two things: (1) rely on Retrofit to do the right thing
 *   and (2) wrap our long Api.mapModel operation in withContext(Dispatchers.Default)
 * - ConcurrencyHelpers.kt contains two things that are not part of the language but very useful
 *   and common functionality: [ControlledRunner.joinPreviousOrRun] and [ControlledRunner.cancelPreviousThenRun]
 * - [ControlledRunner.joinPreviousOrRun] can be tricky because it changes the error propagation
 *   flow
 * - To unit test coroutines:
 *   https://github.com/Kotlin/kotlinx.coroutines/tree/master/kotlinx-coroutines-test
 *   https://github.com/marcinOz/TestCoroutineRule
 * - Error handling can be finicky with coroutines: a catch-all block (for all Throwable) will
 *   also catch CancellationException: it might or might not be what we want.
 * - Re cancellation and who throws CancellationException and when:
 *   https://proandroiddev.com/cancelling-kotlin-coroutines-1030f03bf168 (TODO summarize)
 *
 * DISCOVERIES: re other things
 * - OkHttp: the default timeout for connect/read/write is 10s. If any of these operations takes
 *   more than 10s, the whole call fails. It's possible to set a callTimeout for the total
 *   but by default it's 0: i.e. it's not enforced and the timeout of individual operations are used.
 *
 * TODO still need to understand error handling better (and beware of cancellation exception)
 *      see https://proandroiddev.com/rxjava-to-coroutines-on-android-by-example-3736f4ecc1c8
 *          https://proandroiddev.com/kotlin-coroutine-job-hierarchy-finish-cancel-and-fail-2d3d42a768a9
 *          https://kotlinlang.org/docs/reference/coroutines/exception-handling.html
 *
 * TODO Test: when cancellation happens, how is cancellation propagated?
 *      Two cases: child to parent, parent to child. If cancellation is caught by child/parent,
 *      how does it affect the other?
 */
@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private val dao by lazy { AppDatabase.getInstance(this).dataDao() }

    private val ram: Cache by lazy {
        RamCache().apply {
            readAction.onActivityChange = { launch { updateRamStatus() } }
            writeAction.onActivityChange = { launch { updateRamStatus() } }
        }
    }

    private val disk: Cache by lazy {
        DiskCache(dao).apply {
            readAction.onActivityChange = { launch { updateDiskStatus() } }
            writeAction.onActivityChange = { launch { updateDiskStatus() } }
        }
    }

    private val api by lazy {
        Api().apply { fetchAction.onActivityChange = { launch { updateApiStatus() } } }
    }

    private val agent by lazy { Agent(CompoundCache(ram = ram, disk = disk), api) }

    private var runningGetDataJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        get_data_button.setOnClickListener {
            // If there is a running getData() job, a click just cancels it and returns
            runningGetDataJob?.let {
                log("Clicked to cancel")
                it.cancel()
                return@setOnClickListener
            }

            log("Clicked to get data")
            val startMs = System.currentTimeMillis()

            runningGetDataJob = launch {
                get_data_progress.visibility = View.VISIBLE
                get_data_button.text = "Cancel"

                try {
                    val result = agent.getData()
                    val duration = System.currentTimeMillis() - startMs

                    get_data_result.text = "${Date().formatAsTime()}: " + if (result != null) {
                        val (data, source) = result
                        "Got data from $source after ${duration}ms: $data"

                    } else {
                        "Tried but got NO data after ${duration}ms"
                    }.also { log(it) }

                } catch (t: Throwable) {
                    log("Error thrown by agent.getData()", t)

                    get_data_result.text =
                        "${Date().formatAsTime()}: Error trying to get data: $t"

                    throw t

                } finally {
                    runningGetDataJob = null
                    get_data_progress.visibility = View.INVISIBLE
                    get_data_button.text = "Get data"
                }
            }
        }

        setupProgrammableAction(api.fetchAction, api_program_result, api_program_delay)
        setupProgrammableAction(ram.readAction, ram_read_program_result, ram_read_program_delay)
        setupProgrammableAction(ram.writeAction, ram_write_program_result, ram_write_program_delay)
        setupProgrammableAction(disk.readAction, disk_read_program_result, disk_read_program_delay)
        setupProgrammableAction(
            disk.writeAction, disk_write_program_result, disk_write_program_delay
        )

        ram_contents_clear.setOnClickListener { launch { ram.clear() } }
        disk_contents_clear.setOnClickListener { launch { disk.clear() } }

        // Initial values, set via the view (after listeners are set)
        ram_read_program_delay.setText("0")
        ram_write_program_delay.setText("0")
        disk_read_program_delay.setText("500")
        disk_write_program_delay.setText("2000")
        api_program_delay.setText("10000")

        // "Wake up" disk cache so that it describes its contents (this is only needed for debug info)
        launch {
            try {
                disk.read()
            } catch (ignored: NotInCacheException) {
                // Don't crash on a NotInCacheException: it happens on fresh installs, and it makes sense
            }
        }
    }

    override fun onStart() {
        super.onStart()

        startClock()
        startActiveIndicator()

        updateRamStatus()
        updateDiskStatus()
        updateApiStatus()
    }

    /** Animate an indicator that flashes to show that the UI thread is not blocked */
    private fun startActiveIndicator() {
        val frequencyMs = 250L

        launch {
            while (true) {
                active_indicator.visibility = View.VISIBLE
                delay(frequencyMs / 2)
                active_indicator.visibility = View.INVISIBLE
                delay(frequencyMs / 2)
            }
        }
    }

    private fun startClock() {
        launch {
            while (true) {
                val time = Date().formatAsTime()

                clock.text = "NOW: $time"
                Log.v("NOW", time)

                delay(1_000)
            }
        }
    }

    override fun onStop() {
        coroutineContext.cancelChildren()
        super.onStop()
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun setupProgrammableAction(
        action: ProgrammableAction,
        resultSpinner: Spinner,
        delayEditText: EditText
    ) {
        resultSpinner.adapter = ArrayAdapter(
            this, R.layout.support_simple_spinner_dropdown_item,
            ProgrammableAction.NextResult.values()
        )
        resultSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {
            }

            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                val nextResult =
                    resultSpinner.getItemAtPosition(p2) as ProgrammableAction.NextResult
                action.nextResult = nextResult
            }
        }

        delayEditText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable) {
                try {
                    action.delayMs = p0.toString().toLong()
                } catch (ignored: NumberFormatException) {
                }
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }
        })
    }

    private fun updateRamStatus() {
        ram_contents.text = "RAM contents: ${ram.describeContents()}"
        updateBusyIndicator(ram_read_busy, ram.readAction.isBusy)
        updateBusyIndicator(ram_write_busy, ram.writeAction.isBusy)
    }

    private fun updateDiskStatus() {
        disk_contents.text = "DISK contents: ${disk.describeContents()}"
        updateBusyIndicator(disk_read_busy, disk.readAction.isBusy)
        updateBusyIndicator(disk_write_busy, disk.writeAction.isBusy)
    }

    private fun updateApiStatus() {
        updateBusyIndicator(api_busy, api.fetchAction.isBusy)
    }

    private fun updateBusyIndicator(progressBar: ProgressBar, isBusy: Boolean) {
        progressBar.visibility = if (isBusy) View.VISIBLE else View.INVISIBLE
    }
}

