package com.ghostlink.zerorf.channels.ultrasonic.modem

/**
 * Physical layer configurations for the GhostLink Acoustic Modem.
 */
enum class AcousticMode(
    val modeId: Int,
    val description: String,
    val symbolDurationMs: Int,
    val bitsPerSymbol: Int,
    val frequenciesHz: DoubleArray,
    val nominalBps: Double
) {
    // Mode 0: Robust baseline BFSK (high noise immunity, maximum range)
    MODE_0_BFSK(
        modeId = 0,
        description = "BFSK Robust (18/19 kHz, 15ms)",
        symbolDurationMs = 15,
        bitsPerSymbol = 1,
        frequenciesHz = doubleArrayOf(18000.0, 19000.0),
        nominalBps = 66.7 // ~8.3 B/s
    ),

    // Mode 1: Fast 4-FSK (2 bits per symbol @ 10ms)
    MODE_1_4FSK(
        modeId = 1,
        description = "4-FSK Medium (17.5-20.5 kHz, 10ms)",
        symbolDurationMs = 10,
        bitsPerSymbol = 2,
        frequenciesHz = doubleArrayOf(17500.0, 18500.0, 19500.0, 20500.0),
        nominalBps = 200.0 // ~25 B/s
    ),

    // Mode 2: High-Rate 8-FSK (3 bits per symbol @ 8ms)
    MODE_2_8FSK(
        modeId = 2,
        description = "8-FSK High-Rate (17.2-20.7 kHz, 8ms)",
        symbolDurationMs = 8,
        bitsPerSymbol = 3,
        frequenciesHz = doubleArrayOf(
            17200.0, 17700.0, 18200.0, 18700.0,
            19200.0, 19700.0, 20200.0, 20700.0
        ),
        nominalBps = 375.0 // ~47 B/s
    );

    companion object {
        const val SAMPLE_RATE = 44100
        val DEFAULT_MODE = MODE_0_BFSK

        fun fromId(id: Int): AcousticMode {
            return values().firstOrNull { it.modeId == id } ?: DEFAULT_MODE
        }
    }
}
