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
 * Some rules I've discovered
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
 *
 * TODO to discover
 *   - I'm still not sure what coroutineScope{} is about
 *   - I'm still not sure about the proper syntax to ensure that some given work is done on a specific
 *     context/dispatcher
 */
@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    // TODO introduce Retrofit
    // TODO fix error with network timeout

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
                log("Click to cancel")
                it.cancel()
                return@setOnClickListener
            }

            log("Click to get data")
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

        // "Wake up" disk cache so that it describes its contents
        // (this is only needed for debug info)
        launch { disk.read() }
    }

    override fun onStart() {
        super.onStart()

        startClock()

        updateRamStatus()
        updateDiskStatus()
        updateApiStatus()
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
        disk_contents.text = "RAM contents: ${disk.describeContents()}"
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

