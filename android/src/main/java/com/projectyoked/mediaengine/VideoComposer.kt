package com.projectyoked.mediaengine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayList

/**
 * Robust VideoComposer
 * Implements complete Video Pipeline (YUV->Bitmap->YUV->H.264)
 * Implements complete Audio Pipeline (Format->PCM->AAC)
 * Handles Muxer interleaving via Audio Buffering.
 */
class VideoComposer(
    private val inputPath: String,
    private val outputPath: String
) {
    private val TAG = "VideoComposer"
    
    // Media Components
    private var videoExtractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null
    private var videoDecoder: MediaCodec? = null
    private var videoEncoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    
    // Config
    private var videoWidth = 0
    private var videoHeight = 0
    private var realWidth = 0
    private var realHeight = 0
    private var videoBitrate = 2000000
    private var videoFrameRate = 30
    private var rotation = 0
    private var muxerStarted = false
    
    // Track Management
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var sourceAudioTrackIndex = -1
    
    // Audio Buffering for Interleaving
    private data class AudioSample(
        val data: ByteArray,
        val info: MediaCodec.BufferInfo
    )
    private val audioBuffer = ArrayList<AudioSample>()

    data class TextOverlay(
        val text: String, 
        val x: Double, 
        val y: Double, 
        val color: String, 
        val size: Double,
        val start: Double,
        val duration: Double
    )
    
    data class EmojiOverlay(
        val emoji: String, 
        val x: Double, 
        val y: Double, 
        val size: Double,
        val start: Double,
        val duration: Double
    )

    fun composeVideo(
        textOverlays: List<TextOverlay>,
        emojiOverlays: List<EmojiOverlay>,
        filterId: String?,
        filterIntensity: Double,
        musicPath: String?,
        musicVolume: Double,
        originalVolume: Double
    ): String {
        Log.d(TAG, "Starting composition for: $inputPath")
        try {
            setupExtractors()
            
            // Validate dimensions
            if (videoWidth % 2 != 0) videoWidth--
            if (videoHeight % 2 != 0) videoHeight--
            
            setupMuxer()
            
            // 1. Process Audio First (Buffer it)
            // This ensures we have the Audio Track format ready for the Muxer
            if (originalVolume > 0 && sourceAudioTrackIndex != -1) {
                Log.d(TAG, "Processing audio track...")
                processAudioTranscode()
            } else {
                Log.d(TAG, "Skipping audio (Volume: $originalVolume, TrackIndex: $sourceAudioTrackIndex)")
            }
            
            // 2. Process Video & Mux (Interleave)
            Log.d(TAG, "Processing video track...")
            processVideo(textOverlays, emojiOverlays, filterId, filterIntensity)
            
            return outputPath
        } catch (e: Exception) {
            Log.e(TAG, "Composition fatal error", e)
            throw Exception("Video composition failed: ${e.message}")
        } finally {
            cleanup()
        }
    }

    private fun setupExtractors() {
        val vExtractor = MediaExtractor()
        vExtractor.setDataSource(inputPath)
        var videoFound = false
        for (i in 0 until vExtractor.trackCount) {
            val format = vExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                vExtractor.selectTrack(i)
                videoFound = true
                videoWidth = safeGetInteger(format, MediaFormat.KEY_WIDTH, 1280)
                videoHeight = safeGetInteger(format, MediaFormat.KEY_HEIGHT, 720)
                realWidth = videoWidth
                realHeight = videoHeight
                videoBitrate = safeGetInteger(format, MediaFormat.KEY_BIT_RATE, 2000000)
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    if (rotation == 90 || rotation == 270) {
                        val t = videoWidth; videoWidth = videoHeight; videoHeight = t
                    }
                }
                break
            }
        }
        videoExtractor = vExtractor
        if (!videoFound) throw Exception("No video track found")

        val aExtractor = MediaExtractor()
        try {
            aExtractor.setDataSource(inputPath)
            for (i in 0 until aExtractor.trackCount) {
                val format = aExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    aExtractor.selectTrack(i)
                    sourceAudioTrackIndex = i
                    audioExtractor = aExtractor
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio extractor init failed", e)
        }
    }
    
    private fun setupMuxer() {
        val file = File(outputPath)
        if (file.exists()) file.delete()
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotation != 0) muxer?.setOrientationHint(rotation)
    }

    // --- Audio Pipeline ---
    
    private fun processAudioTranscode() {
        val extractor = audioExtractor ?: return
        val inputFormat = extractor.getTrackFormat(sourceAudioTrackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
        
        audioDecoder = MediaCodec.createDecoderByType(mime).apply {
            configure(inputFormat, null, null, 0)
            start()
        }
        
        val sampleRate = safeGetInteger(inputFormat, MediaFormat.KEY_SAMPLE_RATE, 44100)
        val channelCount = safeGetInteger(inputFormat, MediaFormat.KEY_CHANNEL_COUNT, 2)
        
        val outputFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        
        audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        val decoder = audioDecoder!!
        val encoder = audioEncoder!!
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeout = 5000L
        
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(timeout)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            
            // Dec -> Enc
            var outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val pcmData = decoder.getOutputBuffer(outIndex)!!
                    val encIndex = encoder.dequeueInputBuffer(timeout)
                    if (encIndex >= 0) {
                        val encBuf = encoder.getInputBuffer(encIndex)!!
                        encBuf.clear()
                        encBuf.put(pcmData)
                        encoder.queueInputBuffer(encIndex, 0, bufferInfo.size, bufferInfo.presentationTimeUs, 0)
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    val eIdx = encoder.dequeueInputBuffer(timeout)
                    if (eIdx >= 0) {
                        encoder.queueInputBuffer(eIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            }
            
            // Enc Output -> Buffer
            var encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, timeout)
            while (encOutIndex >= 0) {
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                    val buffer = encoder.getOutputBuffer(encOutIndex)!!
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    val data = ByteArray(bufferInfo.size)
                    buffer.get(data)
                    
                    // Copy info
                    val infoCopy = MediaCodec.BufferInfo()
                    infoCopy.set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                    audioBuffer.add(AudioSample(data, infoCopy))
                }
                encoder.releaseOutputBuffer(encOutIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true
                }
                encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 0) // drain
            }
            
            if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = encoder.outputFormat
                audioTrackIndex = muxer?.addTrack(newFormat) ?: -1
                Log.d(TAG, "Audio track added: $audioTrackIndex")
            }
        }
    }
    
    // --- Video Pipeline ---

    private fun processVideo(
        textOverlays: List<TextOverlay>,
        emojiOverlays: List<EmojiOverlay>,
        filterId: String?,
        filterIntensity: Double
    ) {
        val extractor = videoExtractor!!
        val inputFormat = extractor.getTrackFormat(extractor.sampleTrackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
        
        videoDecoder = MediaCodec.createDecoderByType(mime).apply {
            configure(inputFormat, null, null, 0)
            start()
        }
        
        val outputFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, realWidth, realHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, videoFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        
        videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        
        val decoder = videoDecoder!!
        val encoder = videoEncoder!!
        val mux = muxer!!
        val bufferInfo = MediaCodec.BufferInfo()
        val timeout = 10000L
        
        var inputDone = false
        var outputDone = false
        var audioSampleIndex = 0
        
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(timeout)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            
            // Decode -> Edit -> Encode
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout)
            if (outIndex >= 0) {
                val doRender = bufferInfo.size > 0
                if (doRender) {
                    val image = decoder.getOutputImage(outIndex)
                    if (image != null) {
                        val bitmap = yuvImageToBitmap(image, realWidth, realHeight)
                        image.close()
                        
                        if (bitmap != null) {
                            val canvas = Canvas(bitmap)
                            val timeSec = bufferInfo.presentationTimeUs / 1_000_000.0
                            drawOverlays(canvas, textOverlays, emojiOverlays, timeSec)
                            if (filterId != null) applyFilter(bitmap, filterId, filterIntensity)
                            
                            val encIndex = encoder.dequeueInputBuffer(timeout)
                            if (encIndex >= 0) {
                                val encBuf = encoder.getInputBuffer(encIndex)!!
                                encBuf.clear()
                                val nv12 = bitmapToNV12(bitmap)
                                encBuf.put(nv12)
                                encoder.queueInputBuffer(encIndex, 0, nv12.size, bufferInfo.presentationTimeUs, 0)
                            }
                            bitmap.recycle()
                        }
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    val eIdx = encoder.dequeueInputBuffer(timeout)
                    if (eIdx >= 0) {
                        encoder.queueInputBuffer(eIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }
            }
            
            // Encode -> Mux
            var encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, timeout)
            while (encOutIndex >= 0) {
                 if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufferInfo.size = 0
                 
                 if (bufferInfo.size > 0) {
                     if (!muxerStarted) {
                         // Must have dropped frames before track format
                     } else {
                         val buffer = encoder.getOutputBuffer(encOutIndex)!!
                         buffer.position(bufferInfo.offset)
                         buffer.limit(bufferInfo.offset + bufferInfo.size)
                         
                         // Interleave Audio
                         while (audioSampleIndex < audioBuffer.size) {
                             val sample = audioBuffer[audioSampleIndex]
                             if (sample.info.presentationTimeUs <= bufferInfo.presentationTimeUs) {
                                 val buf = ByteBuffer.wrap(sample.data)
                                 mux.writeSampleData(audioTrackIndex, buf, sample.info)
                                 audioSampleIndex++
                             } else {
                                 break
                             }
                         }
                         
                         mux.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                     }
                 }
                 encoder.releaseOutputBuffer(encOutIndex, false)
                 if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                 encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            }
            
            if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackIndex = mux.addTrack(encoder.outputFormat)
                if (videoTrackIndex != -1 && (sourceAudioTrackIndex == -1 || audioTrackIndex != -1)) {
                    mux.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started!")
                }
            }
        }
        
        // Write remaining audio
        while (audioSampleIndex < audioBuffer.size && muxerStarted) {
             val sample = audioBuffer[audioSampleIndex]
             val buf = ByteBuffer.wrap(sample.data)
             mux.writeSampleData(audioTrackIndex, buf, sample.info)
             audioSampleIndex++
        }
    }

    // --- Helpers ---
    
    private fun yuvImageToBitmap(image: android.media.Image, width: Int, height: Int): Bitmap? {
        return try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val yStride = planes[0].rowStride
            val uvStride = planes[1].rowStride
            val pixelStride = planes[1].pixelStride
            
            // NV21 expects: Y plane followed by V U V U...
            // Total size = W*H + W*H/2
            val nv21 = ByteArray(width * height * 3 / 2)
            
            // 1. Copy Y Plane (Handling Stride)
            var inputOffset = 0
            var outputOffset = 0
            
            for (row in 0 until height) {
                yBuffer.position(row * yStride)
                yBuffer.get(nv21, outputOffset, width)
                outputOffset += width
            }
            
            // 2. Copy UV Planes (Handling Stride & Pixel Stride)
            // NV21: V then U (interleaved)
            // Subsample: UV height is H/2, width is W/2
            
            val uvHeight = height / 2
            val uvWidth = width / 2
            
            // Temp buffers for one row of U and V data if needed?
            // Safer to read directly from buffer with absolute positioning?
            // Unfortunately ByteBuffer.get(index) doesn't advance.
            // Let's iterate using get(index).
            
            val vOffset = 0 // V buffer start
            val uOffset = 0 // U buffer start
            
            // Optimized loop for UV
            var nv21Index = width * height
            
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    // NV21 wants V then U
                    val vVal = vBuffer.get(row * uvStride + col * pixelStride)
                    val uVal = uBuffer.get(row * uvStride + col * pixelStride)
                    
                    nv21[nv21Index++] = vVal
                    nv21[nv21Index++] = uVal
                }
            }

            val yuvImage = YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            // High quality for intermediate frame
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val imageBytes = out.toByteArray()
            
            val options = BitmapFactory.Options()
            // Make mutable directly? No, decode is immutable.
            
            val immutable = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            return immutable?.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            Log.e(TAG, "YUV Conversion Error", e)
            null
        }
    }
    
    private fun bitmapToNV12(bitmap: Bitmap): ByteArray {
        val width = bitmap.width; val height = bitmap.height
        val size = width * height
        val yuv = ByteArray(size * 3 / 2)
        val argb = IntArray(size)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        var yIdx = 0; var uvIdx = size
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[yIdx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuv[yIdx++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                     val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                     val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                     yuv[uvIdx++] = u.coerceIn(0, 255).toByte() // NV12: U then V? No, standard is UV
                     yuv[uvIdx++] = v.coerceIn(0, 255).toByte() 
                }
            }
        }
        return yuv
    }

    private fun drawOverlays(c: Canvas, texts: List<TextOverlay>, emojis: List<EmojiOverlay>, currentTimeSec: Double) {
        // SCALING FIX:
        // React Native text size is in DP (approx 1/360 of screen width).
        // Video text size is in Pixels.
        // We need to scale the font based on the ratio of Video Width to a "Standard Phone Width".
        // Using 375.0 as standard width (iPhone standard).
        // If Video is 720p (720px width), scale is ~2.0. Font 24 -> 48px.
        // If Video is 1080p (1080px width), scale is ~3.0. Font 24 -> 72px.
        // Use 'realWidth' for the logic because drawing happens on raw bitmap (even if logic uses swapped).
        // Actually, for Rotation=90/270, the visual width is videoHeight (short dimension).
        // We should scale based on the "Visual Width" of the video to match Screen Width.
        
        val visualWidth = if (rotation == 90 || rotation == 270) realHeight else realWidth
        val scale = visualWidth / 375.0
        
        val p = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        
        fun drawItem(text: String, xPerc: Double, yPerc: Double, logicalSize: Double, color: Int) {
            val finalSize = (logicalSize * scale).toFloat()
            p.color = color
            p.textSize = finalSize
            
            var drawX = 0f
            var drawY = 0f
            var angle = 0f
            
            // Map logical coordinates (0.0-1.0) to Raw Bitmap pixels based on rotation
            when (rotation) {
                90 -> { // Portrait (Recorded on phone)
                    drawX = (yPerc * realWidth).toFloat()
                    drawY = ((1.0 - xPerc) * realHeight).toFloat()
                    angle = -90f 
                }
                270 -> { // Reverse Portrait
                     drawX = ((1.0 - yPerc) * realWidth).toFloat()
                     drawY = (xPerc * realHeight).toFloat()
                     angle = 90f
                }
                180 -> { // Reverse Landscape
                     drawX = ((1.0 - xPerc) * realWidth).toFloat()
                     drawY = ((1.0 - yPerc) * realHeight).toFloat()
                     angle = 180f
                }
                else -> { // 0 - Landscape
                     drawX = (xPerc * realWidth).toFloat()
                     drawY = (yPerc * realHeight).toFloat()
                     angle = 0f
                }
            }
            
            c.save()
            c.rotate(angle, drawX, drawY)
            c.drawText(text, drawX, drawY, p)
            c.restore()
        }

        // Filter and Draw with Logging
        // if (frameIndex % 30 == 0) Log.d(TAG, "Frame Time: $currentTimeSec")
        
        texts.forEach { 
            // Log.d(TAG, "Text '${it.text}': ${it.start} -> ${it.start + it.duration}")
            if (currentTimeSec >= it.start && currentTimeSec < (it.start + it.duration)) {
                drawItem(it.text, it.x, it.y, it.size, parseColor(it.color)) 
            }
        }
        emojis.forEach { 
            if (currentTimeSec >= it.start && currentTimeSec < (it.start + it.duration)) {
                drawItem(it.emoji, it.x, it.y, it.size, Color.BLACK)
            }
        }
    }
    
    private fun applyFilter(b: Bitmap, id: String, i: Double) {
        // Placeholder for filter logic if needed
    }
    
    private fun parseColor(s: String) = try { Color.parseColor(s) } catch (e: Exception) { Color.WHITE }
    private fun safeGetInteger(f: MediaFormat, k: String, d: Int) = if (f.containsKey(k)) f.getInteger(k) else d
    
    private fun cleanup() {
        try { videoDecoder?.stop(); videoDecoder?.release() } catch (e: Exception) {}
        try { videoEncoder?.stop(); videoEncoder?.release() } catch (e: Exception) {}
        try { audioDecoder?.stop(); audioDecoder?.release() } catch (e: Exception) {}
        try { audioEncoder?.stop(); audioEncoder?.release() } catch (e: Exception) {}
        try { if (muxerStarted) muxer?.stop(); muxer?.release() } catch (e: Exception) {}
        try { videoExtractor?.release() } catch (e: Exception) {}
        try { audioExtractor?.release() } catch (e: Exception) {}
    }
}
