
import ExpoModulesCore
import AVFoundation
import UIKit

public class MediaEngineModule: Module {
    public func definition() -> ModuleDefinition {
        Name("MediaEngine")

        // MARK: - Audio Extraction
        AsyncFunction("extractAudio") { (videoUri: String, outputUri: String) -> String in
            let videoURL = URL(fileURLWithPath: videoUri.replacingOccurrences(of: "file://", with: ""))
            let outputURL = URL(fileURLWithPath: outputUri.replacingOccurrences(of: "file://", with: ""))
            
            // Delete existing file
            try? FileManager.default.removeItem(at: outputURL)
            
            let asset = AVAsset(url: videoURL)
            
            guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
            }
            
            exportSession.outputURL = outputURL
            exportSession.outputFileType = .m4a
            exportSession.timeRange = CMTimeRange(start: .zero, duration: asset.duration)
            
            await exportSession.export()
            
            if exportSession.status == .completed {
                return outputUri
            } else {
                throw NSError(domain: "MediaEngine", code: 2, userInfo: [NSLocalizedDescriptionKey: exportSession.error?.localizedDescription ?? "Unknown error"])
            }
        }

        // MARK: - Waveform Generation
        AsyncFunction("getWaveform") { (audioUri: String, samples: Int) -> [Float] in
            let audioURL = URL(fileURLWithPath: audioUri.replacingOccurrences(of: "file://", with: ""))
            let file = try AVAudioFile(forReading: audioURL)
            let format = file.processingFormat
            let frameCount = UInt32(file.length)
            
            guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
                return []
            }
            
            try file.read(into: buffer)
            
            let _ = Int(format.channelCount)
            guard let floatData = buffer.floatChannelData?[0] else { return [] }
            
            let samplesPerPixel = Int(frameCount) / samples
            var result: [Float] = []
            
            for i in 0..<samples {
                let start = i * samplesPerPixel
                var rms: Float = 0
                for j in 0..<samplesPerPixel {
                    if start + j < Int(frameCount) {
                        let sample = floatData[start + j]
                        rms += sample * sample
                    }
                }
                rms = sqrt(rms / Float(samplesPerPixel))
                
                result.append(min(1.0, rms * 5.0)) 
            }
            
            return result
        }

        // MARK: - Video Composition (Export)
        AsyncFunction("exportComposition") { (config: [String: Any]) -> String in
            guard let outputPath = config["outputPath"] as? String,
                  let videoPath = config["videoPath"] as? String else {
                throw NSError(domain: "MediaEngine", code: 1, userInfo: [NSLocalizedDescriptionKey: "Missing required paths"])
            }
            
            let duration = config["duration"] as? Double ?? 10.0
            
            // Parse text overlays with timing
            let textArray = config["textArray"] as? [String] ?? []
            let textX = config["textX"] as? [Double] ?? []
            let textY = config["textY"] as? [Double] ?? []
            let textColors = config["textColors"] as? [String] ?? []
            let textSizes = config["textSizes"] as? [Double] ?? []
            let textStarts = config["textStarts"] as? [Double] ?? []
            let textDurations = config["textDurations"] as? [Double] ?? []
            
            // Parse emoji overlays with timing
            let emojiArray = config["emojiArray"] as? [String] ?? []
            let emojiX = config["emojiX"] as? [Double] ?? []
            let emojiY = config["emojiY"] as? [Double] ?? []
            let emojiSizes = config["emojiSizes"] as? [Double] ?? []
            let emojiStarts = config["emojiStarts"] as? [Double] ?? []
            let emojiDurations = config["emojiDurations"] as? [Double] ?? []
            
            // Parse audio settings
            let musicPath = config["musicPath"] as? String
            let musicVolume = config["musicVolume"] as? Double ?? 0.5
            let originalVolume = config["originalVolume"] as? Double ?? 1.0
            
            let videoURL = URL(fileURLWithPath: videoPath.replacingOccurrences(of: "file://", with: ""))
            let outputURL = URL(fileURLWithPath: outputPath.replacingOccurrences(of: "file://", with: ""))
            
            try? FileManager.default.removeItem(at: outputURL)

            let composition = AVMutableComposition()
            
            let asset = AVAsset(url: videoURL)
            guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
                throw NSError(domain: "MediaEngine", code: 3, userInfo: [NSLocalizedDescriptionKey: "No video track found"])
            }
            
            let compVideoTrack = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid)
            let assetDuration = try await asset.load(.duration)
            let finalDuration = duration > 0 ? CMTime(seconds: duration, preferredTimescale: 600) : assetDuration
            
            try compVideoTrack?.insertTimeRange(CMTimeRange(start: .zero, duration: finalDuration), of: videoTrack, at: .zero)
            
            let videoTransform = try await videoTrack.load(.preferredTransform)
            compVideoTrack?.preferredTransform = videoTransform
            
            // Audio mixing setup
            var audioMixParams: [AVMutableAudioMixInputParameters] = []
            
            // Original audio with volume control
            if let originalAudioTrack = try? await asset.loadTracks(withMediaType: .audio).first {
                let compAudioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
                try compAudioTrack?.insertTimeRange(CMTimeRange(start: .zero, duration: finalDuration), of: originalAudioTrack, at: .zero)
                
                // Apply volume
                if let track = compAudioTrack {
                    let params = AVMutableAudioMixInputParameters(track: track)
                    params.setVolume(Float(originalVolume), at: .zero)
                    audioMixParams.append(params)
                }
            }
            
            // Music track with volume control
            if let musicPath = musicPath, !musicPath.isEmpty, musicVolume > 0 {
                let musicURL = URL(fileURLWithPath: musicPath.replacingOccurrences(of: "file://", with: ""))
                let musicAsset = AVAsset(url: musicURL)
                
                if let musicAudioTrack = try? await musicAsset.loadTracks(withMediaType: .audio).first {
                    let compMusicTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
                    let musicDuration = try await musicAsset.load(.duration)
                    
                    try compMusicTrack?.insertTimeRange(
                        CMTimeRange(start: .zero, duration: min(musicDuration, finalDuration)),
                        of: musicAudioTrack,
                        at: .zero
                    )
                    
                    // Apply volume
                    if let track = compMusicTrack {
                        let params = AVMutableAudioMixInputParameters(track: track)
                        params.setVolume(Float(musicVolume), at: .zero)
                        audioMixParams.append(params)
                    }
                }
            }
            
            // Create audio mix
            let audioMix = AVMutableAudioMix()
            audioMix.inputParameters = audioMixParams

            // Video Composition with proper size handling for rotation
            let naturalSize = try await videoTrack.load(.naturalSize)
            var videoSize = naturalSize
            
            // Handle rotation - swap dimensions if rotated 90 or 270 degrees
            let angle = atan2(videoTransform.b, videoTransform.a)
            let angleDegrees = angle * 180 / .pi
            if abs(abs(angleDegrees) - 90) < 1 {
                videoSize = CGSize(width: naturalSize.height, height: naturalSize.width)
            }
            
            let videoComposition = AVMutableVideoComposition()
            videoComposition.renderSize = videoSize
            videoComposition.frameDuration = CMTime(value: 1, timescale: 30)
            
            let instruction = AVMutableVideoCompositionInstruction()
            instruction.timeRange = CMTimeRange(start: .zero, duration: finalDuration)
            
            let layerInstruction = AVMutableVideoCompositionLayerInstruction(assetTrack: compVideoTrack!)
            
            // Apply transform to correct rotation
            var transform = videoTransform
            if abs(abs(angleDegrees) - 90) < 1 {
                if angleDegrees > 0 {
                    // 90 degree rotation
                    transform = CGAffineTransform(translationX: videoSize.width, y: 0).rotated(by: .pi / 2)
                } else {
                    // -90 degree rotation
                    transform = CGAffineTransform(translationX: 0, y: videoSize.height).rotated(by: -.pi / 2)
                }
            }
            layerInstruction.setTransform(transform, at: .zero)
            instruction.layerInstructions = [layerInstruction]
            videoComposition.instructions = [instruction]
            
            // Overlay Burn-in with timing animations
            if !textArray.isEmpty || !emojiArray.isEmpty {
                let parentLayer = CALayer()
                parentLayer.frame = CGRect(origin: .zero, size: videoSize)
                parentLayer.isGeometryFlipped = true  // Fix coordinate system
                
                let videoLayer = CALayer()
                videoLayer.frame = CGRect(origin: .zero, size: videoSize)
                parentLayer.addSublayer(videoLayer)
                
                // Scale factor for text sizes (match Android's scaling)
                let scaleFactor = videoSize.width / 375.0
                
                // Text overlays with timing
                for i in 0..<textArray.count {
                    let textLayer = CATextLayer()
                    textLayer.string = textArray[i]
                    textLayer.fontSize = CGFloat(textSizes.indices.contains(i) ? textSizes[i] : 24) * scaleFactor
                    
                    // Parse color
                    let colorString = textColors.indices.contains(i) ? textColors[i] : "#FFFFFF"
                    textLayer.foregroundColor = UIColor(hex: colorString)?.cgColor ?? UIColor.white.cgColor
                    
                    textLayer.alignmentMode = .center
                    textLayer.contentsScale = UIScreen.main.scale
                    
                    // Position
                    let xPos = textX.indices.contains(i) ? textX[i] : 0.5
                    let yPos = textY.indices.contains(i) ? textY[i] : 0.5
                    
                    let width: CGFloat = 400 * scaleFactor
                    let height: CGFloat = 100 * scaleFactor
                    textLayer.frame = CGRect(
                        x: videoSize.width * CGFloat(xPos) - width/2,
                        y: videoSize.height * CGFloat(yPos) - height/2,
                        width: width,
                        height: height
                    )
                    
                    // Add timing animation
                    let startTime = textStarts.indices.contains(i) ? textStarts[i] : 0.0
                    let itemDuration = textDurations.indices.contains(i) ? textDurations[i] : duration
                    
                    // Initially hidden
                    textLayer.opacity = 0
                    
                    // Show animation
                    let showAnim = CABasicAnimation(keyPath: "opacity")
                    showAnim.fromValue = 0
                    showAnim.toValue = 1
                    showAnim.beginTime = AVCoreAnimationBeginTimeAtZero + startTime
                    showAnim.duration = 0.01
                    showAnim.fillMode = .forwards
                    showAnim.isRemovedOnCompletion = false
                    
                    // Hide animation
                    let hideAnim = CABasicAnimation(keyPath: "opacity")
                    hideAnim.fromValue = 1
                    hideAnim.toValue = 0
                    hideAnim.beginTime = AVCoreAnimationBeginTimeAtZero + startTime + itemDuration
                    hideAnim.duration = 0.01
                    hideAnim.fillMode = .forwards
                    hideAnim.isRemovedOnCompletion = false
                    
                    let animGroup = CAAnimationGroup()
                    animGroup.animations = [showAnim, hideAnim]
                    animGroup.duration = duration
                    animGroup.beginTime = AVCoreAnimationBeginTimeAtZero
                    animGroup.fillMode = .forwards
                    animGroup.isRemovedOnCompletion = false
                    
                    textLayer.add(animGroup, forKey: "visibility")
                    parentLayer.addSublayer(textLayer)
                }
                
                // Emoji overlays with timing
                for i in 0..<emojiArray.count {
                    let emojiLayer = CATextLayer()
                    emojiLayer.string = emojiArray[i]
                    let size = CGFloat(emojiSizes.indices.contains(i) ? emojiSizes[i] : 48) * scaleFactor
                    emojiLayer.fontSize = size
                    emojiLayer.alignmentMode = .center
                    emojiLayer.contentsScale = UIScreen.main.scale
                    
                    // Position
                    let xPos = emojiX.indices.contains(i) ? emojiX[i] : 0.5
                    let yPos = emojiY.indices.contains(i) ? emojiY[i] : 0.5
                    
                    emojiLayer.frame = CGRect(
                        x: videoSize.width * CGFloat(xPos) - size/2,
                        y: videoSize.height * CGFloat(yPos) - size/2,
                        width: size,
                        height: size
                    )
                    
                    // Add timing animation
                    let startTime = emojiStarts.indices.contains(i) ? emojiStarts[i] : 0.0
                    let itemDuration = emojiDurations.indices.contains(i) ? emojiDurations[i] : duration
                    
                    // Initially hidden
                    emojiLayer.opacity = 0
                    
                    // Show animation
                    let showAnim = CABasicAnimation(keyPath: "opacity")
                    showAnim.fromValue = 0
                    showAnim.toValue = 1
                    showAnim.beginTime = AVCoreAnimationBeginTimeAtZero + startTime
                    showAnim.duration = 0.01
                    showAnim.fillMode = .forwards
                    showAnim.isRemovedOnCompletion = false
                    
                    // Hide animation
                    let hideAnim = CABasicAnimation(keyPath: "opacity")
                    hideAnim.fromValue = 1
                    hideAnim.toValue = 0
                    hideAnim.beginTime = AVCoreAnimationBeginTimeAtZero + startTime + itemDuration
                    hideAnim.duration = 0.01
                    hideAnim.fillMode = .forwards
                    hideAnim.isRemovedOnCompletion = false
                    
                    let animGroup = CAAnimationGroup()
                    animGroup.animations = [showAnim, hideAnim]
                    animGroup.duration = duration
                    animGroup.beginTime = AVCoreAnimationBeginTimeAtZero
                    animGroup.fillMode = .forwards
                    animGroup.isRemovedOnCompletion = false
                    
                    emojiLayer.add(animGroup, forKey: "visibility")
                    parentLayer.addSublayer(emojiLayer)
                }
                
                videoComposition.animationTool = AVVideoCompositionCoreAnimationTool(postProcessingAsVideoLayer: videoLayer, in: parentLayer)
            }
            
            guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else {
                 throw NSError(domain: "MediaEngine", code: 4, userInfo: [NSLocalizedDescriptionKey: "Failed to create video export session"])
            }
            
            exportSession.outputURL = outputURL
            exportSession.outputFileType = .mp4
            exportSession.videoComposition = videoComposition
            exportSession.audioMix = audioMix
            
            await exportSession.export()
             
            if exportSession.status == .completed {
                return outputPath
            } else {
                throw NSError(domain: "MediaEngine", code: 5, userInfo: [NSLocalizedDescriptionKey: exportSession.error?.localizedDescription ?? "Video Export Failed"])
            }
        }
    }
}

// Helper extension to parse hex colors
extension UIColor {
    convenience init?(hex: String) {
        var hexSanitized = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        hexSanitized = hexSanitized.replacingOccurrences(of: "#", with: "")
        
        var rgb: UInt64 = 0
        guard Scanner(string: hexSanitized).scanHexInt64(&rgb) else { return nil }
        
        self.init(
            red: CGFloat((rgb & 0xFF0000) >> 16) / 255.0,
            green: CGFloat((rgb & 0x00FF00) >> 8) / 255.0,
            blue: CGFloat(rgb & 0x0000FF) / 255.0,
            alpha: 1.0
        )
    }
}
