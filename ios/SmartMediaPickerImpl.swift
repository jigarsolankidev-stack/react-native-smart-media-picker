import UIKit
import Photos
import PhotosUI
import AVFoundation
import MobileCoreServices
import UniformTypeIdentifiers
import React

@objc(SmartMediaPickerImpl)
public class SmartMediaPickerImpl: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate, PHPickerViewControllerDelegate, UIDocumentPickerDelegate {

    private var pendingResolve: RCTPromiseResolveBlock?
    private var pendingReject: RCTPromiseRejectBlock?
    private var pendingOptions: NSDictionary?

    // Global presentation helper to find top view controller
    private func getTopViewController() -> UIViewController? {
        guard let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
              let rootViewController = windowScene.windows.first(where: { $0.isKeyWindow })?.rootViewController else {
            return nil
        }
        var topController = rootViewController
        while let presented = topController.presentedViewController {
            topController = presented
        }
        return topController
    }

    // --- PERMISSIONS API ---

    @objc public func checkPermission(_ type: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        switch type {
        case "camera":
            let status = AVCaptureDevice.authorizationStatus(for: .video)
            switch status {
            case .authorized: resolve("FULL")
            case .denied: resolve("DENIED")
            case .restricted: resolve("BLOCKED")
            case .notDetermined: resolve("DENIED")
            @unknown default: resolve("DENIED")
            }
        case "photoLibrary":
            let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            switch status {
            case .authorized: resolve("FULL")
            case .limited: resolve("LIMITED")
            case .denied: resolve("DENIED")
            case .restricted: resolve("BLOCKED")
            case .notDetermined: resolve("DENIED")
            @unknown default: resolve("DENIED")
            }
        default:
            // Documents and Audio do not require explicit runtime permissions on iOS for Picker UI
            resolve("FULL")
        }
    }

    @objc public func requestPermission(_ type: String, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        switch type {
        case "camera":
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        resolve("FULL")
                    } else {
                        // On iOS, if denied previously, we cannot request again, so it's BLOCKED
                        let status = AVCaptureDevice.authorizationStatus(for: .video)
                        if status == .denied || status == .restricted {
                            resolve("BLOCKED")
                        } else {
                            resolve("DENIED")
                        }
                    }
                }
            }
        case "photoLibrary":
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                DispatchQueue.main.async {
                    switch status {
                    case .authorized: resolve("FULL")
                    case .limited: resolve("LIMITED")
                    case .denied, .restricted: resolve("BLOCKED")
                    case .notDetermined: resolve("DENIED")
                    @unknown default: resolve("DENIED")
                    }
                }
            }
        default:
            resolve("FULL")
        }
    }

    @objc public func openSettings(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            resolve(false)
            return
        }
        if UIApplication.shared.canOpenURL(url) {
            UIApplication.shared.open(url, options: [:]) { success in
                resolve(success)
            }
        } else {
            resolve(false)
        }
    }

    @objc public func presentLimitedLibraryPicker(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let vc = getTopViewController() else {
            reject("VC_ERROR", "Could not find top view controller", nil)
            return
        }
        PHPhotoLibrary.shared().presentLimitedLibraryPicker(from: vc)
        resolve(nil)
    }

    // --- CAMERA API ---

    @objc public func openCamera(_ options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            reject("CAMERA_UNAVAILABLE", "Camera is not available on this device", nil)
            return
        }

        self.pendingResolve = resolve
        self.pendingReject = reject
        self.pendingOptions = options

        DispatchQueue.main.async {
            let picker = UIImagePickerController()
            picker.sourceType = .camera
            picker.delegate = self

            let mediaType = options["mediaType"] as? String ?? "photo"
            if mediaType == "video" {
                picker.mediaTypes = [kUTTypeMovie as String]
                if let durationLimit = options["durationLimit"] as? Double {
                    picker.videoMaximumDuration = durationLimit
                }
            } else if mediaType == "mixed" {
                picker.mediaTypes = [kUTTypeImage as String, kUTTypeMovie as String]
            } else {
                picker.mediaTypes = [kUTTypeImage as String]
            }

            if let cameraType = options["cameraType"] as? String {
                picker.cameraDevice = cameraType == "front" ? .front : .rear
            }

            self.getTopViewController()?.present(picker, animated: true)
        }
    }

    // UIImagePickerControllerDelegate implementation
    public func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
        picker.dismiss(animated: true) {
            guard let resolve = self.pendingResolve,
                  let options = self.pendingOptions else { return }
            self.pendingResolve = nil
            self.pendingOptions = nil

            let mediaType = info[.mediaType] as? String

            if mediaType == (kUTTypeMovie as String) {
                guard let videoURL = info[.mediaURL] as? URL else {
                    self.pendingReject?("CAPTURE_ERROR", "Failed to retrieve video url", nil)
                    return
                }
                self.processAndResolveVideo(videoURL, options: options, resolve: resolve)
            } else {
                guard let image = info[.originalImage] as? UIImage else {
                    self.pendingReject?("CAPTURE_ERROR", "Failed to retrieve photo", nil)
                    return
                }
                // Save captured photo to temporary directory
                let tempDir = NSTemporaryDirectory()
                let fileName = "camera_\(UUID().uuidString).jpg"
                let fileURL = URL(fileURLWithPath: tempDir).appendingPathComponent(fileName)

                if let data = image.jpegData(compressionQuality: 0.9) {
                    do {
                        try data.write(to: fileURL)
                        // Metadata extraction if present
                        var exifDict: [String: Any]? = nil
                        if let metadata = info[.mediaMetadata] as? [String: Any] {
                            exifDict = metadata[kCGImagePropertyExifDictionary as String] as? [String: Any]
                        }
                        self.processAndResolveImage(fileURL, options: options, exif: exifDict, resolve: resolve)
                    } catch {
                        self.pendingReject?("CAPTURE_ERROR", "Failed to save photo to cache: \(error.localizedDescription)", nil)
                    }
                }
            }
        }
    }

    public func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true) {
            self.pendingReject?("USER_CANCELLED", "User cancelled camera capture", nil)
            self.pendingResolve = nil
            self.pendingReject = nil
            self.pendingOptions = nil
        }
    }

    // --- GALLERY API ---

    @objc public func openGallery(_ options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        self.pendingResolve = resolve
        self.pendingReject = reject
        self.pendingOptions = options

        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)

        DispatchQueue.main.async {
            if status == .limited {
                self.launchLimitedAssetPicker(options)
                return
            }

            var config = PHPickerConfiguration(photoLibrary: .shared())
            let mediaType = options["mediaType"] as? String ?? "photo"

            switch mediaType {
            case "photo": config.filter = .images
            case "video": config.filter = .videos
            default: config.filter = .any(of: [.images, .videos])
            }

            let selectionLimit = options["selectionLimit"] as? Int ?? 1
            config.selectionLimit = selectionLimit

            let picker = PHPickerViewController(configuration: config)
            picker.delegate = self
            self.getTopViewController()?.present(picker, animated: true)
        }
    }

    private func launchLimitedAssetPicker(_ options: NSDictionary) {
        let mediaType = options["mediaType"] as? String ?? "photo"
        let selectionLimit = options["selectionLimit"] as? Int ?? 1
        
        let picker = SmartMediaPickerLimitedAssetPickerController(mediaType: mediaType, selectionLimit: selectionLimit)
        picker.onSelectionComplete = { [weak self] urls in
            guard let self = self else { return }
            guard let resolve = self.pendingResolve else { return }
            self.pendingResolve = nil
            self.pendingReject = nil
            
            if urls.isEmpty {
                self.pendingReject?("USER_CANCELLED", "User cancelled gallery selection", nil)
                return
            }
            
            var assetsList = [[String: Any]]()
            for url in urls {
                let mimeType = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "image/jpeg"
                if mimeType.hasPrefix("video/") {
                    let asset = self.resolveVideoAsset(url)
                    assetsList.append(asset)
                } else {
                    var exifDict: [String: Any]? = nil
                    if let imageSource = CGImageSourceCreateWithURL(url as CFURL, nil) {
                        if let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any] {
                            exifDict = properties[kCGImagePropertyExifDictionary as String] as? [String: Any]
                        }
                    }
                    
                    if urls.count == 1 {
                        self.processAndResolveImage(url, options: options, exif: exifDict, resolve: resolve)
                        return
                    } else {
                        let asset = self.resolveImageAsset(url, exif: exifDict)
                        assetsList.append(asset)
                    }
                }
            }
            resolve(["assets": assetsList])
        }
        
        let navController = UINavigationController(rootViewController: picker)
        navController.modalPresentationStyle = .fullScreen
        self.getTopViewController()?.present(navController, animated: true)
    }


    // PHPickerViewControllerDelegate implementation
    public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true) {
            guard !results.isEmpty else {
                self.pendingReject?("USER_CANCELLED", "User cancelled gallery selection", nil)
                self.pendingResolve = nil
                self.pendingReject = nil
                self.pendingOptions = nil
                return
            }

            guard let resolve = self.pendingResolve,
                  let options = self.pendingOptions else { return }
            self.pendingResolve = nil
            self.pendingOptions = nil

            let group = DispatchGroup()
            var assetsList = [[String: Any]]()
            var finalError: Error?

            for result in results {
                group.enter()
                let itemProvider = result.itemProvider

                if itemProvider.hasItemConformingToTypeIdentifier(UTType.movie.identifier) {
                    itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.movie.identifier) { url, error in
                        defer { group.leave() }
                        if let error = error {
                            finalError = error
                            return
                        }
                        guard let url = url else { return }
                        // Create a persistent local copy because the source file representation is automatically cleaned up when handler block finishes
                        let tempDir = NSTemporaryDirectory()
                        let ext = url.pathExtension.isEmpty ? "mp4" : url.pathExtension
                        let destinationURL = URL(fileURLWithPath: tempDir).appendingPathComponent("gallery_\(UUID().uuidString).\(ext)")
                        do {
                            if FileManager.default.fileExists(atPath: destinationURL.path) {
                                try FileManager.default.removeItem(at: destinationURL)
                            }
                            try FileManager.default.copyItem(at: url, to: destinationURL)
                            let asset = self.resolveVideoAsset(destinationURL)
                            assetsList.append(asset)
                        } catch {
                            finalError = error
                        }
                    }
                } else if itemProvider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                    itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, error in
                        defer { group.leave() }
                        if let error = error {
                            finalError = error
                            return
                        }
                        guard let url = url else { return }
                        let tempDir = NSTemporaryDirectory()
                        let ext = url.pathExtension.isEmpty ? "jpg" : url.pathExtension
                        let destinationURL = URL(fileURLWithPath: tempDir).appendingPathComponent("gallery_\(UUID().uuidString).\(ext)")
                        do {
                            if FileManager.default.fileExists(atPath: destinationURL.path) {
                                try FileManager.default.removeItem(at: destinationURL)
                            }
                            try FileManager.default.copyItem(at: url, to: destinationURL)

                            // Read original EXIF data
                            var exifDict: [String: Any]? = nil
                            if let imageSource = CGImageSourceCreateWithURL(destinationURL as CFURL, nil) {
                                if let properties = CGImageSourceCopyPropertiesAtIndex(imageSource, 0, nil) as? [String: Any] {
                                    exifDict = properties[kCGImagePropertyExifDictionary as String] as? [String: Any]
                                }
                            }

                            // Pass single asset handling for cropping/compression if single selection
                            if results.count == 1 {
                                DispatchQueue.main.async {
                                    self.processAndResolveImage(destinationURL, options: options, exif: exifDict, resolve: resolve)
                                }
                            } else {
                                let asset = self.resolveImageAsset(destinationURL, exif: exifDict)
                                assetsList.append(asset)
                            }
                        } catch {
                            finalError = error
                        }
                    }
                } else {
                    group.leave()
                }
            }

            group.notify(queue: .main) {
                if results.count > 1 {
                    if let error = finalError {
                        self.pendingReject?("PICKER_ERROR", "Failed to retrieve selected media: \(error.localizedDescription)", nil)
                    } else {
                        resolve(["assets": assetsList])
                    }
                }
            }
        }
    }

    // --- DOCUMENTS & AUDIO PICKER ---

    @objc public func openAudioPicker(_ options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        openDocumentPickerWithOptions(types: [UTType.audio], options: options, resolve: resolve, reject: reject)
    }

    @objc public func openDocumentPicker(_ options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        var types = [UTType.item]
        if let mimeTypes = options["mimeTypes"] as? [String] {
            types = mimeTypes.compactMap { mime -> UTType? in
                if mime == "application/pdf" { return .pdf }
                if mime.contains("msword") || mime.contains("officedocument") { return .flatRTFD }
                return UTType(mimeType: mime)
            }
            if types.isEmpty { types = [.item] }
        }
        openDocumentPickerWithOptions(types: types, options: options, resolve: resolve, reject: reject)
    }

    private func openDocumentPickerWithOptions(types: [UTType], options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        self.pendingResolve = resolve
        self.pendingReject = reject
        self.pendingOptions = options

        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
            picker.delegate = self
            let selectionLimit = options["selectionLimit"] as? Int ?? 1
            picker.allowsMultipleSelection = selectionLimit == 0 || selectionLimit > 1
            self.getTopViewController()?.present(picker, animated: true)
        }
    }

    // UIDocumentPickerDelegate implementation
    public func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let resolve = self.pendingResolve else { return }
        self.pendingResolve = nil
        self.pendingReject = nil

        var assetsList = [[String: Any]]()
        for url in urls {
            let fileCoordinator = NSFileCoordinator()
            fileCoordinator.coordinate(readingItemAt: url, options: [], error: nil) { securityScopedURL in
                // Copy selected document to temp folder so we have local sandbox access
                let tempDir = NSTemporaryDirectory()
                let fileName = securityScopedURL.lastPathComponent
                let destinationURL = URL(fileURLWithPath: tempDir).appendingPathComponent("doc_\(UUID().uuidString)_\(fileName)")
                do {
                    if FileManager.default.fileExists(atPath: destinationURL.path) {
                        try FileManager.default.removeItem(at: destinationURL)
                    }
                    try FileManager.default.copyItem(at: securityScopedURL, to: destinationURL)
                    let fileAttributes = try FileManager.default.attributesOfItem(atPath: destinationURL.path)
                    let fileSize = fileAttributes[.size] as? Int64 ?? 0
                    
                    let utType = UTType(filenameExtension: destinationURL.pathExtension)
                    let mimeType = utType?.preferredMIMEType ?? "application/octet-stream"

                    var asset: [String: Any] = [
                        "uri": destinationURL.absoluteString,
                        "name": fileName,
                        "type": mimeType,
                        "size": fileSize
                    ]

                    if mimeType.hasPrefix("audio/") {
                        let assetDuration = self.getVideoDuration(destinationURL)
                        asset["duration"] = assetDuration
                    }

                    assetsList.append(asset)
                } catch {
                    // Skip or handle copy error
                }
            }
        }
        resolve(["assets": assetsList])
    }

    public func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        self.pendingReject?("USER_CANCELLED", "User cancelled document selection", nil)
        self.pendingResolve = nil
        self.pendingReject = nil
        self.pendingOptions = nil
    }

    // --- PROCESSING API ---

    @objc public func compressImage(_ uri: String, options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let url = URL(string: uri) else {
            reject("INVALID_URI", "The provided image URI is invalid", nil)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let compressedURL = try self.compressImageInternal(url, compressOptions: options)
                guard let image = UIImage(contentsOfFile: compressedURL.path) else {
                    throw NSError(domain: "SmartMediaPicker", code: 500, userInfo: [NSLocalizedDescriptionKey: "Failed to load compressed image"])
                }
                let fileAttributes = try FileManager.default.attributesOfItem(atPath: compressedURL.path)
                let fileSize = fileAttributes[.size] as? Int64 ?? 0

                resolve([
                    "uri": compressedURL.absoluteString,
                    "size": fileSize,
                    "width": Int(image.size.width),
                    "height": Int(image.size.height)
                ])
            } catch {
                reject("COMPRESSION_FAILED", "Failed to compress image: \(error.localizedDescription)", nil)
            }
        }
    }

    @objc public func compressVideo(_ uri: String, options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let url = URL(string: uri) else {
            reject("INVALID_URI", "The provided video URI is invalid", nil)
            return
        }
        let quality = options["quality"] as? String ?? "medium"
        let preset: String
        switch quality {
        case "low": preset = AVAssetExportPresetLowQuality
        case "high": preset = AVAssetExportPresetHighestQuality
        default: preset = AVAssetExportPresetMediumQuality
        }

        let asset = AVAsset(url: url)
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: preset) else {
            reject("COMPRESSION_FAILED", "Could not initialize video export session", nil)
            return
        }

        let tempDir = NSTemporaryDirectory()
        let outputURL = URL(fileURLWithPath: tempDir).appendingPathComponent("compressed_\(UUID().uuidString).mp4")
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .mp4
        exportSession.shouldOptimizeForNetworkUse = true

        exportSession.exportAsynchronously {
            DispatchQueue.main.async {
                switch exportSession.status {
                case .completed:
                    do {
                        let fileAttributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
                        let fileSize = fileAttributes[.size] as? Int64 ?? 0
                        let duration = CMTimeGetSeconds(asset.duration)

                        resolve([
                            "uri": outputURL.absoluteString,
                            "size": fileSize,
                            "duration": duration
                        ])
                    } catch {
                        reject("COMPRESSION_FAILED", "Failed to compile compressed video stats", nil)
                    }
                case .failed, .cancelled:
                    reject("COMPRESSION_FAILED", exportSession.error?.localizedDescription ?? "Video export cancelled", nil)
                default:
                    reject("COMPRESSION_FAILED", "Unknown video compression error", nil)
                }
            }
        }
    }

    @objc public func getVideoThumbnail(_ uri: String, options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let url = URL(string: uri) else {
            reject("INVALID_URI", "The provided video URI is invalid", nil)
            return
        }
        let timeOffset = options["timeOffset"] as? Double ?? 1.0
        let quality = options["quality"] as? Double ?? 0.8

        DispatchQueue.global(qos: .userInitiated).async {
            let asset = AVAsset(url: url)
            let imageGenerator = AVAssetImageGenerator(asset: asset)
            imageGenerator.appliesPreferredTrackTransform = true

            let time = CMTime(seconds: timeOffset, preferredTimescale: 600)
            do {
                let cgImage = try imageGenerator.copyCGImage(at: time, actualTime: nil)
                let image = UIImage(cgImage: cgImage)

                let tempDir = NSTemporaryDirectory()
                let outputURL = URL(fileURLWithPath: tempDir).appendingPathComponent("thumb_\(UUID().uuidString).jpg")

                if let data = image.jpegData(compressionQuality: CGFloat(quality)) {
                    try data.write(to: outputURL)
                    resolve([
                        "uri": outputURL.absoluteString,
                        "width": Int(image.size.width),
                        "height": Int(image.size.height)
                    ])
                } else {
                    throw NSError(domain: "SmartMediaPicker", code: 500, userInfo: [NSLocalizedDescriptionKey: "Failed to generate JPEG representation"])
                }
            } catch {
                reject("THUMBNAIL_FAILED", "Failed to extract thumbnail frame: \(error.localizedDescription)", nil)
            }
        }
    }

    // --- CROP & COMPRESSION COORDINATION FLOWS ---

    private func processAndResolveImage(_ fileURL: URL, options: NSDictionary, exif: [String: Any]?, resolve: @escaping RCTPromiseResolveBlock) {
        // 1. Interactive Cropping
        if let cropOptions = options["cropOptions"] as? NSDictionary {
            DispatchQueue.main.async {
                self.launchCropViewController(fileURL, cropOptions: cropOptions) { croppedURL in
                    if let croppedURL = croppedURL {
                        // 2. Image Compression (post-crop)
                        let finalURL: URL
                        if let compOptions = options["compressOptions"] as? NSDictionary {
                            do {
                                finalURL = try self.compressImageInternal(croppedURL, compressOptions: compOptions)
                            } catch {
                                finalURL = croppedURL
                            }
                        } else {
                            finalURL = croppedURL
                        }
                        let asset = self.resolveImageAsset(finalURL, exif: exif)
                        resolve(["assets": [asset]])
                    } else {
                        self.pendingReject?("USER_CANCELLED", "User cancelled image cropping", nil)
                    }
                }
            }
            return
        }

        // 2. Image Compression (no-crop)
        let finalURL: URL
        if let compOptions = options["compressOptions"] as? NSDictionary {
            do {
                finalURL = try self.compressImageInternal(fileURL, compressOptions: compOptions)
            } catch {
                finalURL = fileURL
            }
        } else {
            finalURL = fileURL
        }

        let asset = self.resolveImageAsset(finalURL, exif: exif)
        resolve(["assets": [asset]])
    }

    private func processAndResolveVideo(_ fileURL: URL, options: NSDictionary, resolve: @escaping RCTPromiseResolveBlock) {
        let asset = self.resolveVideoAsset(fileURL)
        resolve(["assets": [asset]])
    }

    private func compressImageInternal(_ fileURL: URL, compressOptions: NSDictionary) throws -> URL {
        guard let data = try? Data(contentsOf: fileURL),
              var image = UIImage(data: data) else {
            throw NSError(domain: "SmartMediaPicker", code: 500, userInfo: [NSLocalizedDescriptionKey: "Failed to read image source"])
        }

        let maxW = compressOptions["maxWidth"] as? CGFloat ?? 0
        let maxH = compressOptions["maxHeight"] as? CGFloat ?? 0
        let quality = compressOptions["quality"] as? Double ?? 0.9

        // Scale maintaining aspect ratio if maxW or maxH is specified
        if (maxW > 0 && image.size.width > maxW) || (maxH > 0 && image.size.height > maxH) {
            let scale = min(maxW / image.size.width, maxH / image.size.height)
            let targetSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            
            UIGraphicsBeginImageContextWithOptions(targetSize, false, 1.0)
            image.draw(in: CGRect(origin: .zero, size: targetSize))
            if let scaledImage = UIGraphicsGetImageFromCurrentImageContext() {
                image = scaledImage
            }
            UIGraphicsEndImageContext()
        }

        let tempDir = NSTemporaryDirectory()
        let outputURL = URL(fileURLWithPath: tempDir).appendingPathComponent("comp_\(UUID().uuidString).jpg")

        if let compressedData = image.jpegData(compressionQuality: CGFloat(quality)) {
            try compressedData.write(to: outputURL)
            return outputURL
        } else {
            throw NSError(domain: "SmartMediaPicker", code: 500, userInfo: [NSLocalizedDescriptionKey: "Failed to generate JPEG representation"])
        }
    }

    // Interactive custom Cropping View Controller
    private func launchCropViewController(_ imageURL: URL, cropOptions: NSDictionary, completion: @escaping (URL?) -> Void) {
        guard let data = try? Data(contentsOf: imageURL),
              let image = UIImage(data: data) else {
            completion(nil)
            return
        }

        let cropVC = SmartMediaPickerCropViewController(image: image, cropOptions: cropOptions)
        cropVC.onCropComplete = completion
        
        let navController = UINavigationController(rootViewController: cropVC)
        navController.modalPresentationStyle = .fullScreen
        self.getTopViewController()?.present(navController, animated: true)
    }

    // --- ASSET RESOLUTION UTILITIES ---

    private func resolveImageAsset(_ fileURL: URL, exif: [String: Any]?) -> [String: Any] {
        let fileAttributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
        let fileSize = fileAttributes?[.size] as? Int64 ?? 0
        let image = UIImage(contentsOfFile: fileURL.path)

        var asset: [String: Any] = [
            "uri": fileURL.absoluteString,
            "name": fileURL.lastPathComponent,
            "type": "image/jpeg",
            "size": fileSize,
            "width": Int(image?.size.width ?? 0),
            "height": Int(image?.size.height ?? 0)
        ]
        if let exif = exif {
            asset["exif"] = exif
        }
        return asset
    }

    private func resolveVideoAsset(_ fileURL: URL) -> [String: Any] {
        let fileAttributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path)
        let fileSize = fileAttributes?[.size] as? Int64 ?? 0
        let duration = getVideoDuration(fileURL)

        // Get video dimensions
        var width = 0
        var height = 0
        let track = AVAsset(url: fileURL).tracks(withMediaType: .video).first
        if let size = track?.naturalSize {
            let transform = track?.preferredTransform
            if transform?.b != 0 {
                // Video is rotated portrait
                width = Int(size.height)
                height = Int(size.width)
            } else {
                width = Int(size.width)
                height = Int(size.height)
            }
        }

        var asset: [String: Any] = [
            "uri": fileURL.absoluteString,
            "name": fileURL.lastPathComponent,
            "type": "video/mp4",
            "size": fileSize,
            "width": width,
            "height": height,
            "duration": duration
        ]

        // Auto generate thumbnail
        let imageGenerator = AVAssetImageGenerator(asset: AVAsset(url: fileURL))
        imageGenerator.appliesPreferredTrackTransform = true
        let time = CMTime(seconds: 1.0, preferredTimescale: 600)
        if let cgImage = try? imageGenerator.copyCGImage(at: time, actualTime: nil) {
            let uiImage = UIImage(cgImage: cgImage)
            let tempDir = NSTemporaryDirectory()
            let thumbURL = URL(fileURLWithPath: tempDir).appendingPathComponent("thumb_\(UUID().uuidString).jpg")
            if let data = uiImage.jpegData(compressionQuality: 0.8) {
                try? data.write(to: thumbURL)
                asset["thumbnailUri"] = thumbURL.absoluteString
            }
        }

        return asset
    }

    private func getVideoDuration(_ fileURL: URL) -> Double {
        let asset = AVAsset(url: fileURL)
        return CMTimeGetSeconds(asset.duration)
    }
}

// Custom programmatic View Controller for image cropping in Swift
internal class SmartMediaPickerCropViewController: UIViewController, UIScrollViewDelegate {

    var onCropComplete: ((URL?) -> Void)?

    private let image: UIImage
    private let cropOptions: NSDictionary

    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private let cropOverlayView = UIView()

    init(image: UIImage, cropOptions: NSDictionary) {
        self.image = image
        self.cropOptions = cropOptions
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        setupNavigationBar()
        setupScrollView()
        setupCropOverlay()
    }

    private func setupNavigationBar() {
        title = "Crop Image"
        navigationController?.navigationBar.barStyle = .black
        navigationController?.navigationBar.titleTextAttributes = [.foregroundColor: UIColor.white]

        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancelTapped))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Done", style: .done, target: self, action: #selector(doneTapped))
    }

    private func setupScrollView() {
        scrollView.frame = view.bounds
        scrollView.delegate = self
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = 6.0
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        view.addSubview(scrollView)

        imageView.image = image
        imageView.contentMode = .scaleAspectFit
        imageView.frame = scrollView.bounds
        scrollView.addSubview(imageView)
    }

    private func setupCropOverlay() {
        cropOverlayView.isUserInteractionEnabled = false
        cropOverlayView.layer.borderColor = UIColor.white.cgColor
        cropOverlayView.layer.borderWidth = 2.0
        view.addSubview(cropOverlayView)

        // Overlay size
        let padding: CGFloat = 40.0
        let cropW = view.bounds.width - padding * 2
        var cropH = cropW // default square

        if let ratioArray = cropOptions["aspectRatio"] as? [CGFloat], ratioArray.count >= 2 {
            let aspectX = ratioArray[0]
            let aspectY = ratioArray[1]
            if aspectX > 0 && aspectY > 0 {
                cropH = cropW * (aspectY / aspectX)
            }
        }

        cropOverlayView.frame = CGRect(
            x: padding,
            y: (view.bounds.height - cropH) / 2.0,
            width: cropW,
            height: cropH
        )

        // Add dimming overlays
        addDimBackground()
    }

    private func addDimBackground() {
        let maskLayer = CAShapeLayer()
        let path = CGMutablePath()

        path.addRect(view.bounds)
        path.addRect(cropOverlayView.frame)

        maskLayer.path = path
        maskLayer.fillRule = .evenOdd
        maskLayer.fillColor = UIColor.black.withAlphaComponent(0.65).cgColor

        let overlayDim = UIView(frame: view.bounds)
        overlayDim.isUserInteractionEnabled = false
        overlayDim.layer.addSublayer(maskLayer)
        view.addSubview(overlayDim)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        return imageView
    }

    @objc private func cancelTapped() {
        dismiss(animated: true) {
            self.onCropComplete?(nil)
        }
    }

    @objc private func doneTapped() {
        // Calculate crop bounds relative to UIImage size
        let zoomScale = scrollView.zoomScale
        let cropFrameInScroll = view.convert(cropOverlayView.frame, to: imageView)

        // Map crop coordinates back to source CGImage space
        let imageSize = image.size
        let viewSize = imageView.bounds.size

        let scaleX = imageSize.width / viewSize.width
        let scaleY = imageSize.height / viewSize.height

        let cropRect = CGRect(
            x: cropFrameInScroll.origin.x * scaleX * zoomScale,
            y: cropFrameInScroll.origin.y * scaleY * zoomScale,
            width: cropFrameInScroll.size.width * scaleX * zoomScale,
            height: cropFrameInScroll.size.height * scaleY * zoomScale
        )

        guard let cgImage = image.cgImage?.cropping(to: cropRect) else {
            dismiss(animated: true) {
                self.onCropComplete?(nil)
            }
            return
        }

        let croppedImage = UIImage(cgImage: cgImage, scale: image.scale, orientation: image.imageOrientation)

        let tempDir = NSTemporaryDirectory()
        let outputURL = URL(fileURLWithPath: tempDir).appendingPathComponent("cropped_\(UUID().uuidString).jpg")

        if let data = croppedImage.jpegData(compressionQuality: 0.9) {
            do {
                try data.write(to: outputURL)
                dismiss(animated: true) {
                    self.onCropComplete?(outputURL)
                }
            } catch {
                dismiss(animated: true) {
                    self.onCropComplete?(nil)
                }
            }
        } else {
            dismiss(animated: true) {
                self.onCropComplete?(nil)
            }
        }
    }
}

internal class LimitedAssetCell: UICollectionViewCell {
    let imageView = UIImageView()
    let checkmarkView = UIView()
    let checkmarkInner = UIView()
    let videoDurationLabel = UILabel()
    
    override init(frame: CGRect) {
        super.init(frame: frame)
        
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        contentView.addSubview(imageView)
        
        checkmarkView.backgroundColor = .clear
        checkmarkView.layer.cornerRadius = 12
        checkmarkView.layer.borderColor = UIColor.white.cgColor
        checkmarkView.layer.borderWidth = 2.0
        contentView.addSubview(checkmarkView)
        
        checkmarkInner.backgroundColor = UIColor(red: 0, green: 122/255, blue: 1, alpha: 1)
        checkmarkInner.layer.cornerRadius = 8
        checkmarkInner.isHidden = true
        checkmarkView.addSubview(checkmarkInner)
        
        videoDurationLabel.textColor = .white
        videoDurationLabel.font = .systemFont(ofSize: 11, weight: .semibold)
        videoDurationLabel.textAlignment = .right
        videoDurationLabel.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        videoDurationLabel.layer.cornerRadius = 4
        videoDurationLabel.clipsToBounds = true
        videoDurationLabel.isHidden = true
        contentView.addSubview(videoDurationLabel)
    }
    
    override func layoutSubviews() {
        super.layoutSubviews()
        imageView.frame = contentView.bounds
        checkmarkView.frame = CGRect(x: contentView.bounds.width - 28, y: 4, width: 24, height: 24)
        checkmarkInner.frame = CGRect(x: 4, y: 4, width: 16, height: 16)
        videoDurationLabel.frame = CGRect(x: 4, y: contentView.bounds.height - 18, width: contentView.bounds.width - 8, height: 14)
    }
    
    func configure(isSelected: Bool) {
        checkmarkInner.isHidden = !isSelected
        checkmarkView.backgroundColor = isSelected ? UIColor(red: 0, green: 122/255, blue: 1, alpha: 1) : .clear
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

internal class SmartMediaPickerLimitedAssetPickerController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    
    var onSelectionComplete: (([URL]) -> Void)?
    
    private let mediaType: String
    private let selectionLimit: Int
    
    private var collectionView: UICollectionView!
    private var phAssets = [PHAsset]()
    private var selectedAssets = [PHAsset]()
    private let imageManager = PHCachingImageManager()
    
    private let loadingIndicator = UIActivityIndicatorView(style: .large)
    
    init(mediaType: String, selectionLimit: Int) {
        self.mediaType = mediaType
        self.selectionLimit = selectionLimit
        super.init(nibName: nil, bundle: nil)
    }
    
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        
        setupNavigationBar()
        setupCollectionView()
        setupLoadingIndicator()
        loadLimitedAssets()
    }
    
    private func setupNavigationBar() {
        title = "Limited Photos Access"
        navigationController?.navigationBar.barStyle = .black
        navigationController?.navigationBar.titleTextAttributes = [.foregroundColor: UIColor.white]
        
        navigationItem.leftBarButtonItem = UIBarButtonItem(barButtonSystemItem: .cancel, target: self, action: #selector(cancelTapped))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "Done", style: .done, target: self, action: #selector(doneTapped))
    }
    
    private func setupCollectionView() {
        let layout = UICollectionViewFlowLayout()
        layout.minimumLineSpacing = 2
        layout.minimumInteritemSpacing = 2
        
        collectionView = UICollectionView(frame: view.bounds, collectionViewLayout: layout)
        collectionView.backgroundColor = .black
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(LimitedAssetCell.self, forCellWithReuseIdentifier: "LimitedAssetCell")
        view.addSubview(collectionView)
    }
    
    private func setupLoadingIndicator() {
        loadingIndicator.center = view.center
        loadingIndicator.color = .white
        loadingIndicator.hidesWhenStopped = true
        view.addSubview(loadingIndicator)
    }
    
    private func loadLimitedAssets() {
        let fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        
        let fetchResult: PHFetchResult<PHAsset>
        if mediaType == "photo" {
            fetchResult = PHAsset.fetchAssets(with: .image, options: fetchOptions)
        } else if mediaType == "video" {
            fetchResult = PHAsset.fetchAssets(with: .video, options: fetchOptions)
        } else {
            fetchResult = PHAsset.fetchAssets(with: fetchOptions)
        }
        
        fetchResult.enumerateObjects { asset, _, _ in
            self.phAssets.append(asset)
        }
        collectionView.reloadData()
    }
    
    @objc private func cancelTapped() {
        dismiss(animated: true) {
            self.onSelectionComplete?([])
        }
    }
    
    @objc private func doneTapped() {
        guard !selectedAssets.isEmpty else {
            dismiss(animated: true) {
                self.onSelectionComplete?([])
            }
            return
        }
        
        loadingIndicator.startAnimating()
        view.isUserInteractionEnabled = false
        
        var urls = [URL]()
        let group = DispatchGroup()
        
        for asset in selectedAssets {
            group.enter()
            let resources = PHAssetResource.assetResources(for: asset)
            guard let resource = resources.first else {
                group.leave()
                continue
            }
            
            let tempDir = NSTemporaryDirectory()
            let fileName = resource.originalFilename
            let destinationURL = URL(fileURLWithPath: tempDir).appendingPathComponent("limited_\(UUID().uuidString)_\(fileName)")
            
            PHAssetResourceManager.default().writeData(for: resource, toFile: destinationURL, options: nil) { error in
                if error == nil {
                    urls.append(destinationURL)
                }
                group.leave()
            }
        }
        
        group.notify(queue: .main) {
            self.loadingIndicator.stopAnimating()
            self.dismiss(animated: true) {
                self.onSelectionComplete?(urls)
            }
        }
    }
    
    // UICollectionViewDataSource implementation
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return phAssets.count
    }
    
    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "LimitedAssetCell", for: indexPath) as! LimitedAssetCell
        let asset = phAssets[indexPath.item]
        
        // Load thumbnail
        cell.imageView.image = nil
        imageManager.requestImage(for: asset, targetSize: CGSize(width: 200, height: 200), contentMode: .aspectFill, options: nil) { image, _ in
            cell.imageView.image = image
        }
        
        let isSelected = selectedAssets.contains(asset)
        cell.configure(isSelected: isSelected)
        
        if asset.mediaType == .video {
            cell.videoDurationLabel.isHidden = false
            let duration = Int(asset.duration)
            let min = duration / 60
            let sec = duration % 60
            cell.videoDurationLabel.text = String(format: " %d:%02d ", min, sec)
        } else {
            cell.videoDurationLabel.isHidden = true
        }
        
        return cell
    }
    
    // UICollectionViewDelegateFlowLayout implementation
    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let cols: CGFloat = 3
        let spacing: CGFloat = 2
        let width = (collectionView.bounds.width - (spacing * (cols - 1))) / cols
        return CGSize(width: width, height: width)
    }
    
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let asset = phAssets[indexPath.item]
        
        if let idx = selectedAssets.firstIndex(of: asset) {
            selectedAssets.remove(at: idx)
        } else {
            if selectionLimit > 0 && selectedAssets.count >= selectionLimit {
                if selectionLimit == 1 {
                    // Single pick swap
                    selectedAssets.removeAll()
                    selectedAssets.append(asset)
                } else {
                    // Alert limit reached
                    let alert = UIAlertController(title: "Limit Reached", message: "You can only select up to \(selectionLimit) items.", preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "OK", style: .default))
                    present(alert, animated: true)
                    return
                }
            } else {
                selectedAssets.append(asset)
            }
        }
        collectionView.reloadData()
    }
}

