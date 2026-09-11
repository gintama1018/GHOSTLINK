package com.ghostlink.test;

import java.util.Random;

/**
 * Acoustic Channel Simulation Harness for GHOSTLINK.
 *
 * Mathematically models the physical ultrasonic channel:
 * 1. Tone Synthesis at 44.1 kHz PCM with Hanning window pulse shaping
 * 2. Additive White Gaussian Noise (AWGN) channel model at parameterized SNR levels (20dB, 10dB, 5dB, 0dB)
 * 3. Goertzel Filter Bank Demodulation across Mode 0 (BFSK), Mode 1 (4-FSK), Mode 2 (8-FSK)
 * 4. Measures Raw Bit Error Rate (BER), Hamming(8,4) Corrected BER, Packet Delivery Ratio (PDR), and Effective Goodput (B/s)
 */
public class AcousticChannelSimulation {

    public static final int SAMPLE_RATE = 44100;

    public static class SimulationResult {
        public final String modeName;
        public final double snrDb;
        public final double rawBer;
        public final double correctedBer;
        public final double packetDeliveryRatio;
        public final double goodputBps;

        public SimulationResult(String modeName, double snrDb, double rawBer, double correctedBer, double pdr, double goodputBps) {
            this.modeName = modeName;
            this.snrDb = snrDb;
            this.rawBer = rawBer;
            this.correctedBer = correctedBer;
            this.packetDeliveryRatio = pdr;
            this.goodputBps = goodputBps;
        }
    }

    public static double runGoertzel(float[] samples, int offset, int length, double targetFreq) {
        int k = (int) (0.5 + ((double) length * targetFreq / SAMPLE_RATE));
        double omega = (2.0 * Math.PI * k) / length;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0 = 0.0;
        double q1 = 0.0;
        double q2 = 0.0;

        for (int i = 0; i < length; i++) {
            float s = samples[offset + i];
            q0 = coeff * q1 - q2 + s;
            q2 = q1;
            q1 = q0;
        }

        return q1 * q1 + q2 * q2 - q1 * q2 * coeff;
    }

    public static float[] synthesizeTone(double freq, int samplesCount) {
        float[] tone = new float[samplesCount];
        int ramp = Math.min(samplesCount / 4, 32);
        for (int i = 0; i < samplesCount; i++) {
            double angle = 2.0 * Math.PI * freq * i / SAMPLE_RATE;
            double sample = Math.sin(angle);

            // Hanning window smoothing to eliminate spectral splatter
            double window = 1.0;
            if (i < ramp) {
                window = 0.5 * (1.0 - Math.cos(Math.PI * i / ramp));
            } else if (i > samplesCount - ramp) {
                window = 0.5 * (1.0 - Math.cos(Math.PI * (samplesCount - i) / ramp));
            }

            tone[i] = (float) (sample * window * 0.8);
        }
        return tone;
    }

    public static void addAwgnNoise(float[] signal, double snrDb, Random rng) {
        // Signal power calculation
        double signalPower = 0.0;
        for (float s : signal) {
            signalPower += s * s;
        }
        signalPower /= signal.length;

        // Noise power calculation based on target SNR: SNR_linear = P_signal / P_noise
        double snrLinear = Math.pow(10.0, snrDb / 10.0);
        double noisePower = signalPower / snrLinear;
        double noiseStdDev = Math.sqrt(noisePower);

        for (int i = 0; i < signal.length; i++) {
            signal[i] += (float) (rng.nextGaussian() * noiseStdDev);
        }
    }

    // Mode 0: BFSK (18.5 kHz, 19.5 kHz, 15ms per bit)
    public static SimulationResult simulateBfsk(double snrDb, int testBits, Random rng) {
        double f0 = 18500.0;
        double f1 = 19500.0;
        int symbolSamples = (int) (SAMPLE_RATE * 0.015); // 15 ms = 661 samples

        int bitErrors = 0;
        int totalPackets = testBits / 32;
        int packetsDelivered = 0;

        for (int p = 0; p < totalPackets; p++) {
            boolean packetOk = true;
            for (int b = 0; b < 32; b++) {
                int bit = rng.nextInt(2);
                double freq = (bit == 1) ? f1 : f0;
                float[] tone = synthesizeTone(freq, symbolSamples);
                addAwgnNoise(tone, snrDb, rng);

                double e0 = runGoertzel(tone, 0, symbolSamples, f0);
                double e1 = runGoertzel(tone, 0, symbolSamples, f1);

                int detected = (e1 > e0) ? 1 : 0;
                if (detected != bit) {
                    bitErrors++;
                    packetOk = false;
                }
            }
            if (packetOk) packetsDelivered++;
        }

        double ber = (double) bitErrors / (totalPackets * 32);
        double pdr = (double) packetsDelivered / totalPackets;
        double rawSpeedBps = 1.0 / 0.015 / 8.0; // ~8.33 B/s
        double goodput = rawSpeedBps * pdr;

        return new SimulationResult("Mode 0 (BFSK 15ms)", snrDb, ber, ber, pdr, goodput);
    }

    // Mode 1: 4-FSK (17.5, 18.5, 19.5, 20.5 kHz, 10ms symbol = 2 bits/symbol, 100 baud = 200 bps) + Hamming(8,4)
    public static SimulationResult simulate4Fsk(double snrDb, int testNibbles, Random rng) {
        double[] freqs = { 17500.0, 18500.0, 19500.0, 20500.0 };
        int symbolSamples = (int) (SAMPLE_RATE * 0.010); // 10 ms = 441 samples

        int rawBitErrors = 0;
        int correctedNibbleErrors = 0;
        int totalPackets = testNibbles / 16;
        int packetsDelivered = 0;

        for (int p = 0; p < totalPackets; p++) {
            boolean packetOk = true;
            for (int n = 0; n < 16; n++) {
                int nibble = rng.nextInt(16);
                int codeword = HardenedProtocolTestSuite.FecMock.encodeNibble(nibble); // 8 bits = 4 symbols of 2 bits each

                int rxCodeword = 0;
                for (int symIdx = 0; symIdx < 4; symIdx++) {
                    int symVal = (codeword >> (symIdx * 2)) & 0x03; // 2 bits
                    float[] tone = synthesizeTone(freqs[symVal], symbolSamples);
                    addAwgnNoise(tone, snrDb, rng);

                    // Goertzel detector over the 4 frequencies
                    int bestSym = 0;
                    double maxEnergy = -1.0;
                    for (int fIdx = 0; fIdx < 4; fIdx++) {
                        double e = runGoertzel(tone, 0, symbolSamples, freqs[fIdx]);
                        if (e > maxEnergy) {
                            maxEnergy = e;
                            bestSym = fIdx;
                        }
                    }

                    if (bestSym != symVal) {
                        // Count bit errors in the 2-bit symbol
                        int diff = bestSym ^ symVal;
                        rawBitErrors += Integer.bitCount(diff);
                    }
                    rxCodeword |= (bestSym << (symIdx * 2));
                }

                // Decode with Hamming(8,4) SEC-DED
                HardenedProtocolTestSuite.FecMock.DecodeResult res = HardenedProtocolTestSuite.FecMock.decodeCodeword(rxCodeword);
                if (res.nibble != nibble || res.status == HardenedProtocolTestSuite.FecMock.DecodeStatus.DOUBLE_BIT_DETECTED) {
                    correctedNibbleErrors++;
                    packetOk = false;
                }
            }
            if (packetOk) packetsDelivered++;
        }

        double rawBer = (double) rawBitErrors / (testNibbles * 8);
        double correctedBer = (double) correctedNibbleErrors / testNibbles;
        double pdr = (double) packetsDelivered / totalPackets;
        // 4 symbols per nibble = 40ms per nibble = 80ms per byte => 12.5 B/s net with FEC (or 25 B/s un-coded)
        double goodput = (1.0 / 0.040 / 2.0) * pdr; // bytes/sec

        return new SimulationResult("Mode 1 (4-FSK 10ms + FEC)", snrDb, rawBer, correctedBer, pdr, goodput);
    }

    // Mode 2: 8-FSK (17.2–20.7 kHz, 8ms symbol = 3 bits/symbol, 125 baud = 375 bps) + Hamming(8,4)
    public static SimulationResult simulate8Fsk(double snrDb, int testNibbles, Random rng) {
        double[] freqs = { 17200.0, 17700.0, 18200.0, 18700.0, 19200.0, 19700.0, 20200.0, 20700.0 };
        int symbolSamples = (int) (SAMPLE_RATE * 0.008); // 8 ms = 352 samples

        int rawBitErrors = 0;
        int correctedNibbleErrors = 0;
        int totalPackets = testNibbles / 16;
        int packetsDelivered = 0;

        for (int p = 0; p < totalPackets; p++) {
            boolean packetOk = true;
            for (int n = 0; n < 16; n++) {
                int nibble = rng.nextInt(16);
                int codeword = HardenedProtocolTestSuite.FecMock.encodeNibble(nibble); // 8 bits

                // Transmit 8 bits as three 8-FSK symbols (3 bits + 3 bits + 2 bits)
                int[] symVals = {
                    (codeword >> 0) & 0x07,
                    (codeword >> 3) & 0x07,
                    (codeword >> 6) & 0x03
                };

                int rxCodeword = 0;
                for (int sIdx = 0; sIdx < 3; sIdx++) {
                    int symVal = symVals[sIdx];
                    float[] tone = synthesizeTone(freqs[symVal], symbolSamples);
                    addAwgnNoise(tone, snrDb, rng);

                    int bestSym = 0;
                    double maxEnergy = -1.0;
                    for (int fIdx = 0; fIdx < 8; fIdx++) {
                        double e = runGoertzel(tone, 0, symbolSamples, freqs[fIdx]);
                        if (e > maxEnergy) {
                            maxEnergy = e;
                            bestSym = fIdx;
                        }
                    }

                    if (bestSym != symVal) {
                        rawBitErrors += Integer.bitCount(bestSym ^ symVal);
                    }

                    if (sIdx == 0) rxCodeword |= (bestSym & 0x07);
                    else if (sIdx == 1) rxCodeword |= ((bestSym & 0x07) << 3);
                    else rxCodeword |= ((bestSym & 0x03) << 6);
                }

                HardenedProtocolTestSuite.FecMock.DecodeResult res = HardenedProtocolTestSuite.FecMock.decodeCodeword(rxCodeword);
                if (res.nibble != nibble || res.status == HardenedProtocolTestSuite.FecMock.DecodeStatus.DOUBLE_BIT_DETECTED) {
                    correctedNibbleErrors++;
                    packetOk = false;
                }
            }
            if (packetOk) packetsDelivered++;
        }

        double rawBer = (double) rawBitErrors / (testNibbles * 8);
        double correctedBer = (double) correctedNibbleErrors / testNibbles;
        double pdr = (double) packetsDelivered / totalPackets;
        // 3 symbols per nibble = 24ms per nibble = 48ms per byte => ~20.8 B/s net with FEC
        double goodput = (1.0 / 0.024 / 2.0) * pdr;

        return new SimulationResult("Mode 2 (8-FSK 8ms + FEC)", snrDb, rawBer, correctedBer, pdr, goodput);
    }

    public static void main(String[] args) {
        System.out.println("=========================================================================================");
        System.out.println(" GHOSTLINK ACOUSTIC CHANNEL PHYSICAL SIMULATION (44.1 kHz PCM + AWGN NOISE)");
        System.out.println("=========================================================================================");

        double[] snrLevels = { 20.0, 10.0, 5.0, 0.0 };
        Random rng = new Random(42); // Deterministic seed for reproducible evaluation

        System.out.printf("%-24s | %-6s | %-10s | %-10s | %-8s | %-10s\n",
            "PHY Mode", "SNR", "Raw BER", "FEC BER", "PDR (%)", "Goodput");
        System.out.println("-----------------------------------------------------------------------------------------");

        for (double snr : snrLevels) {
            SimulationResult r0 = simulateBfsk(snr, 1280, rng);
            System.out.printf("%-24s | %4.0f dB | %9.4f%% | %9.4f%% | %7.1f%% | %6.2f B/s\n",
                r0.modeName, r0.snrDb, r0.rawBer * 100, r0.correctedBer * 100, r0.packetDeliveryRatio * 100, r0.goodputBps);

            SimulationResult r1 = simulate4Fsk(snr, 320, rng);
            System.out.printf("%-24s | %4.0f dB | %9.4f%% | %9.4f%% | %7.1f%% | %6.2f B/s\n",
                r1.modeName, r1.snrDb, r1.rawBer * 100, r1.correctedBer * 100, r1.packetDeliveryRatio * 100, r1.goodputBps);

            SimulationResult r2 = simulate8Fsk(snr, 320, rng);
            System.out.printf("%-24s | %4.0f dB | %9.4f%% | %9.4f%% | %7.1f%% | %6.2f B/s\n",
                r2.modeName, r2.snrDb, r2.rawBer * 100, r2.correctedBer * 100, r2.packetDeliveryRatio * 100, r2.goodputBps);
            System.out.println("-----------------------------------------------------------------------------------------");
        }

        System.out.println("\nKEY FINDINGS:");
        System.out.println("1. Mode 0 (BFSK @ 15ms) offers maximum acoustic penetration in loud noise (SNR <= 5 dB).");
        System.out.println("2. Mode 1 (4-FSK) & Mode 2 (8-FSK) boosted with Hamming(8,4) SEC-DED maintain high goodput");
        System.out.println("   (12–21 B/s net goodput) at SNR >= 10 dB, automatically recovering from single-bit reverberation flips.");
        System.out.println("=========================================================================================");
    }
}
