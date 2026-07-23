package com.embedded.terminal

class PtyBackpressureManager {
    private var lastCheckTime = System.currentTimeMillis()
    private var lastReadTime = System.currentTimeMillis()
    private var bytesReadThisPeriod = 0L
    private var throttleDelayMs = 0L

    // Registers read bytes and returns the delay in milliseconds to apply to the reader thread
    fun registerRead(bytes: Int): Long {
        synchronized(this) {
            val now = System.currentTimeMillis()
            
            // If there was no activity for > 100ms, clear throttling immediately
            if (now - lastReadTime > 100) {
                throttleDelayMs = 0
                bytesReadThisPeriod = 0
                lastCheckTime = now
            }
            lastReadTime = now

            bytesReadThisPeriod += bytes
            val elapsed = now - lastCheckTime
            if (elapsed >= 1000) {
                val throughput = (bytesReadThisPeriod * 1000) / elapsed
                
                // Adaptive throttle logic:
                // - Under 100 KB/s: no delay
                // - 100 KB/s to 1 MB/s: 1ms delay
                // - Over 1 MB/s: 5ms delay to prevent starvation of the EDT
                throttleDelayMs = when {
                    throughput < 100_000 -> 0
                    throughput < 1_000_000 -> 1
                    else -> 5
                }
                
                bytesReadThisPeriod = 0
                lastCheckTime = now
            }
            return throttleDelayMs
        }
    }
}
