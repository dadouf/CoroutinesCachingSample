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
            // If there is a running getData() job, a click just cancels it
            runningGetDataJob?.let {
                log("Click to cancel")
                it.cancel()
                return@setOnClickListener
            }

            log("Click to get data")
            val startMs = System.currentTimeMillis()

            try {
                runningGetDataJob = launch {
                    get_data_progress.visibility = View.VISIBLE
                    get_data_button.text = "Cancel"

                    // TODO button to cancel load
                    //  --> expectation it cancels the load to display but not the underlying network call (never the underlying network call!)

                    try {
                        val result = log("agent.getData()") { agent.getData() }
                        val duration = System.currentTimeMillis() - startMs

                        get_data_result.text = "${Date().formatAsTime()}: " + if (result != null) {
                            val (data, source) = result
                            "Got data from $source after ${duration}ms: $data"

                        } else {
                            "Tried but got NO data after ${duration}ms"
                        }.also { log(it) }

                    } catch (t: Throwable) {
                        log("")
                        get_data_result.text =
                            "${Date().formatAsTime()}: Error trying to get data: $t".also {
                                log(
                                    it,
                                    t
                                )
                            }

                        throw t

                    } finally {
                        runningGetDataJob = null
                        get_data_progress.visibility = View.INVISIBLE
                        get_data_button.text = "Get data"
                    }
                }
            } catch (t: Throwable) {
                log("thrown by launch{}")
                throw t
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

        launch {
            while (true) {
                delay(1_000)
                val time = Date().formatAsTime()

                clock.text = "NOW: $time"
                Log.v("NOW", time)
            }
        }

        updateRamStatus()
        updateDiskStatus()
        updateApiStatus()
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

