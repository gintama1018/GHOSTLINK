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
    ),

    // Mode 3: Multi-Carrier OFDM QPSK (16 subcarriers @ 17-21 kHz, 5ms symbol)
    MODE_3_OFDM_16_QPSK(
        modeId = 3,
        description = "OFDM-16 QPSK (17.1-20.9 kHz, 5ms)",
        symbolDurationMs = 5,
        bitsPerSymbol = 32, // 16 carriers * 2 bits
        frequenciesHz = doubleArrayOf(
            17125.0, 17375.0, 17625.0, 17875.0,
            18125.0, 18375.0, 18625.0, 18875.0,
            19125.0, 19375.0, 19625.0, 19875.0,
            20125.0, 20375.0, 20625.0, 20875.0
        ),
        nominalBps = 6400.0 // ~800 B/s raw
    ),

    // Mode 4: Multi-Carrier OFDM 16-QAM (16 subcarriers @ 17-21 kHz, 5ms symbol)
    MODE_4_OFDM_16_16QAM(
        modeId = 4,
        description = "OFDM-16 16-QAM (17.1-20.9 kHz, 5ms)",
        symbolDurationMs = 5,
        bitsPerSymbol = 64, // 16 carriers * 4 bits
        frequenciesHz = doubleArrayOf(
            17125.0, 17375.0, 17625.0, 17875.0,
            18125.0, 18375.0, 18625.0, 18875.0,
            19125.0, 19375.0, 19625.0, 19875.0,
            20125.0, 20375.0, 20625.0, 20875.0
        ),
        nominalBps = 12800.0 // ~1.6 KB/s raw
    ),

    // Mode 5: High-Acoustic OFDM 32-Carrier 16-QAM (12-21 kHz, 5ms symbol)
    MODE_5_OFDM_32_16QAM(
        modeId = 5,
        description = "OFDM-32 16-QAM (12.0-21.0 kHz, 5ms)",
        symbolDurationMs = 5,
        bitsPerSymbol = 128, // 32 carriers * 4 bits
        frequenciesHz = doubleArrayOf(
            12000.0, 12290.0, 12580.0, 12870.0, 13160.0, 13450.0, 13740.0, 14030.0,
            14320.0, 14610.0, 14900.0, 15190.0, 15480.0, 15770.0, 16060.0, 16350.0,
            16640.0, 16930.0, 17220.0, 17510.0, 17800.0, 18090.0, 18380.0, 18670.0,
            18960.0, 19250.0, 19540.0, 19830.0, 20120.0, 20410.0, 20700.0, 20990.0
        ),
        nominalBps = 25600.0 // ~3.2 KB/s raw
    ),

    // Mode 6: Wideband Research OFDM 64-Carrier 16-QAM (8-21 kHz, 5ms symbol)
    MODE_6_OFDM_64_WIDEBAND(
        modeId = 6,
        description = "OFDM-64 Wideband (8.0-21.0 kHz, 5ms)",
        symbolDurationMs = 5,
        bitsPerSymbol = 256, // 64 carriers * 4 bits
        frequenciesHz = doubleArrayOf(
            8000.0, 8206.0, 8412.0, 8618.0, 8824.0, 9030.0, 9236.0, 9442.0,
            9648.0, 9854.0, 10060.0, 10266.0, 10472.0, 10678.0, 10884.0, 11090.0,
            11296.0, 11502.0, 11708.0, 11914.0, 12120.0, 12326.0, 12532.0, 12738.0,
            12944.0, 13150.0, 13356.0, 13562.0, 13768.0, 13974.0, 14180.0, 14386.0,
            14592.0, 14798.0, 15004.0, 15210.0, 15416.0, 15622.0, 15828.0, 16034.0,
            16240.0, 16446.0, 16652.0, 16858.0, 17064.0, 17270.0, 17476.0, 17682.0,
            17888.0, 18094.0, 18300.0, 18506.0, 18712.0, 18918.0, 19124.0, 19330.0,
            19536.0, 19742.0, 19948.0, 20154.0, 20360.0, 20566.0, 20772.0, 20978.0
        ),
        nominalBps = 51200.0 // ~6.4 KB/s raw
    );

    val isMultiCarrier: Boolean get() = modeId >= 3
    val subcarrierCount: Int get() = when (modeId) {
        3, 4 -> 16
        5 -> 32
        6 -> 64
        else -> 1
    }

    companion object {
        const val SAMPLE_RATE = 44100
        val DEFAULT_MODE = MODE_0_BFSK

        fun fromId(id: Int): AcousticMode {
            return values().firstOrNull { it.modeId == id } ?: DEFAULT_MODE
        }
    }
}
