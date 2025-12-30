/**
 * TypeScript type validation tests
 * This file will be type-checked but not executed
 */

import MediaEngine, { ExportCompositionConfig, TextOverlay, EmojiOverlay } from '../src/index';

// Test that all methods exist and have correct signatures
const testExtractAudio = async () => {
    const result: string = await MediaEngine.extractAudio('/video.mp4', '/audio.m4a');
    return result;
};

const testGetWaveform = async () => {
    const result: number[] = await MediaEngine.getWaveform('/audio.m4a', 100);
    return result;
};

const testExportComposition = async () => {
    const config: ExportCompositionConfig = {
        videoPath: '/video.mp4',
        outputPath: '/output.mp4',
        duration: 10.5,
        textArray: ['Hello'],
        textX: [0.5],
        textY: [0.5],
        textColors: ['#FFFFFF'],
        textSizes: [48],
        textStarts: [0],
        textDurations: [3],
    };

    const result: string = await MediaEngine.exportComposition(config);
    return result;
};

const testIsAvailable = () => {
    const result: boolean = MediaEngine.isAvailable();
    return result;
};

// Test interface types
const textOverlay: TextOverlay = {
    text: 'Hello',
    x: 0.5,
    y: 0.5,
    color: '#FFFFFF',
    size: 48,
    start: 0,
    duration: 3,
};

const emojiOverlay: EmojiOverlay = {
    emoji: '🔥',
    x: 0.5,
    y: 0.5,
    size: 64,
    start: 0,
    duration: 3,
};

// Export to avoid TypeScript errors about unused code
export { testExtractAudio, testGetWaveform, testExportComposition, testIsAvailable, textOverlay, emojiOverlay };
