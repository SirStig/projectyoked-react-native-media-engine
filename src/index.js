
import { requireNativeModule } from 'expo-modules-core';

let MediaEngine = null;
try {
    MediaEngine = requireNativeModule('MediaEngine');
} catch (e) {
    console.warn('MediaEngine native module not found. Rebuild required.');
}

/**
 * Native Media Engine
 */
export default {
    /**
     * Extract audio from video
     */
    async extractAudio(videoUri, outputUri) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");
        return await MediaEngine.extractAudio(videoUri, outputUri);
    },

    /**
     * Get Waveform Amplitude Data
     */
    async getWaveform(audioUri, samples = 100) {
        if (!MediaEngine) return new Array(samples).fill(0.1);
        return await MediaEngine.getWaveform(audioUri, samples);
    },

    /**
     * Export Video Composition
     */
    async exportComposition(config) {
        if (!MediaEngine) throw new Error("MediaEngine unavailable");
        return await MediaEngine.exportComposition(config);
    },

    isAvailable() {
        return !!MediaEngine;
    }
};
