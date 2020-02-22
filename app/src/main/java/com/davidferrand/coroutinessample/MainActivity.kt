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
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    // TODO use the correct coroutineContext at top level
    // TODO share resources (network call)
    // TODO use a real disk cache
    // TODO timeout
    // TODO introduce Retrofit and room

    private val ram: Cache = RamCache()
//        onDataChangedObserver = { _, _, newValue -> launch { updateRamStatus(newValue) } },
//        onActiveJobCountChangedObserver = { _, _, newValue -> launch { updateRamActivity(newValue > 0) } })


    private val disk: Cache = DiskCache()
//        onDataChangedObserver = { _, _, newValue -> launch { updateDiskStatus(newValue) } },
//        onActiveJobCountChangedObserver = { _, _, newValue -> launch { updateDiskActivity(newValue > 0) } })

    private val api: Api = Api(
        onActiveJobCountChangedObserver = { _, _, newValue ->
            launch { updateNetworkActivity(newValue > 0) }
        }
    )

    private val agent = Agent(ram, disk, api)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        button_get_data.setOnClickListener {
            log("Click")
            val startMs = System.currentTimeMillis()

            launch {
                progress_data.visibility = View.VISIBLE
                try {
                    val result = log("agent.getData()") { agent.getData() }
                    val duration = System.currentTimeMillis() - startMs

                    main_status.text = if (result != null) {
                        val (data, source) = result
                        "Got data from $source after ${duration}ms: $data"

                    } else {
                        "Tried but got NO data after ${duration}ms"
                    }.also { log(it) }
                } finally {
                    progress_data.visibility = View.INVISIBLE
                }
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

        // Init status
        api_status_success.setSelection(Api.ProgrammableResponse.values().indexOf(api.nextResponse))
        api_status_delay.setText(api.nextResponseDelay.toString())

    }

    override fun onStart() {
        super.onStart()

        launch {
            while (true) {
                delay(1_000)
                status_now.text =
                    "NOW: ${SimpleDateFormat.getTimeInstance().format(Date())}".also { log(it) }
            }
        }

        launch { updateRamStatus(ram.read()) }
        launch { updateDiskStatus(disk.read()) }

        launch { updateRamActivity(ram.activeJobCount > 0) }
        launch { updateDiskActivity(disk.activeJobCount > 0) }
        launch { updateNetworkActivity(api.activeJobCount > 0) }
    }

    override fun onStop() {
        coroutineContext.cancelChildren()
        super.onStop()
    }

    private fun updateRamStatus(newValue: Data?) {
        status_ram.text = "RAM contents: $newValue".also { log(it) }
    }

    private fun updateDiskStatus(newValue: Data?) {
        status_disk.text = "DISK contents: $newValue".also { log(it) }
    }

    private fun updateRamActivity(isActive: Boolean) {
        progress_ram.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
    }

    private fun updateDiskActivity(isActive: Boolean) {
        progress_disk.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
    }

    private fun updateNetworkActivity(isActive: Boolean) {
        progress_api.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
    }
}

