package com.davidferrand.coroutinessample

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import java.util.*

/**
 * Resources used to achieve this:
 * - https://medium.com/androiddevelopers/coroutines-on-android-part-i-getting-the-background-3e0e54d20bb
 * -
 */
@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    // TODO timeout
    // TODO introduce Retrofit
    // TODO fix error with network timeout

    private val dao by lazy { AppDatabase.getInstance(this).dataDao() }
    private val statusLogger by lazy {
        StatusLogger { status ->
            launch {
                when (status) {
                    is StatusLogger.Status.ApiStatus -> {
                        progress_api.visibility =
                            if (status.isActive) View.VISIBLE else View.INVISIBLE
                        api_status_success.setSelection(
                            Api.ProgrammableResponse.values().indexOf(
                                status.nextResponse
                            )
                        )
                        api_status_delay.setText(status.nextResponseDelay.toString())
                    }
                    is StatusLogger.Status.RamStatus -> {
                        status_ram.text = "RAM contents: ${status.description}"
                        progress_ram.visibility =
                            if (status.isActive) View.VISIBLE else View.INVISIBLE
                    }
                    is StatusLogger.Status.DiskStatus -> {
                        status_disk.text = "DISK contents: ${status.description}"
                        progress_disk.visibility =
                            if (status.isActive) View.VISIBLE else View.INVISIBLE
                    }
                }
            }
        }
    }

    private val ram: Cache by lazy { RamCache(statusLogger) }
    private val disk: Cache by lazy { DiskCache(dao, statusLogger) }
    private val api by lazy { Api(statusLogger) }

    private val agent by lazy { Agent(ram, disk, api) }

    private var runningGetDataJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        button_get_data.setOnClickListener {
            // If there is a running getData() job, a click just cancels it
            runningGetDataJob?.let {
                log("Click to cancel")
                cancel()
                return@setOnClickListener
            }

            log("Click to get data")
            val startMs = System.currentTimeMillis()

            try {
                runningGetDataJob = launch {
                    progress_data.visibility = View.VISIBLE
                    button_get_data.text = "Cancel"

                    // TODO button to cancel load
                    //  --> expectation it cancels the load to display but not the underlying network call (never the underlying network call!)

                    try {
                        val result = log("agent.getData()") { agent.getData() }
                        val duration = System.currentTimeMillis() - startMs

                        main_status.text = "${Date().formatAsTime()}: " + if (result != null) {
                            val (data, source) = result
                            "Got data from $source after ${duration}ms: $data"

                        } else {
                            "Tried but got NO data after ${duration}ms"
                        }.also { log(it) }

                    } catch (t: Throwable) {
                        main_status.text =
                            "${Date().formatAsTime()}: Error trying to get data: $t".also {
                                log(
                                    it,
                                    t
                                )
                            }

                        throw t

                    } finally {
                        runningGetDataJob = null
                        progress_data.visibility = View.INVISIBLE
                        button_get_data.text = "Get data"
                    }
                }
            } catch (t: Throwable) {
                log("thrown by launch{}")
                throw t
            }
        }

        api_status_success.adapter = ArrayAdapter(
            this, R.layout.support_simple_spinner_dropdown_item,
            Api.ProgrammableResponse.values()
        )
        api_status_success.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p0: AdapterView<*>?) {
            }

            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                api.nextResponse =
                    api_status_success.getItemAtPosition(p2) as Api.ProgrammableResponse
            }
        }

        api_status_delay.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable) {
                try {
                    api.nextResponseDelay = p0.toString().toLong()
                } catch (ignored: NumberFormatException) {
                }
            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }
        })

        button_clear_ram.setOnClickListener { launch { ram.clear() } }
        button_clear_disk.setOnClickListener { launch { disk.clear() } }
    }

    override fun onStart() {
        super.onStart()

        launch {
            while (true) {
                delay(1_000)
                status_now.text =
                    "NOW: ${Date().formatAsTime()}".also { log(it) }
            }
        }

        launch { ram.initStatus() }
        launch { api.initStatus() }
        launch { disk.initStatus() }
    }

    override fun onStop() {
        coroutineContext.cancelChildren()
        super.onStop()
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}

