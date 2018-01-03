package io.prometheus.common

import com.google.common.util.concurrent.AbstractExecutionThreadService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS

abstract class GenericExecutionThreadService : AbstractExecutionThreadService() {

    fun startSync(maxWaitSecs: Long = 15, timeUnit: TimeUnit = SECONDS) {
        startAsync()
        awaitRunning(maxWaitSecs, timeUnit)
    }

    fun stopSync(maxWaitSecs: Long = 15, timeUnit: TimeUnit = SECONDS) {
        stopAsync()
        awaitTerminated(maxWaitSecs, timeUnit)
    }
}