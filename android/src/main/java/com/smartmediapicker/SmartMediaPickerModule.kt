package com.smartmediapicker

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import kotlin.math.min
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class SmartMediaPickerModule(private val reactContext: ReactApplicationContext) :
  NativeSmartMediaPickerSpec(reactContext) {

  companion object {
    const val NAME = "SmartMediaPicker"
    private const val RC_CAMERA_PHOTO = 1001
    private const val RC_CAMERA_VIDEO = 1002
    private const val RC_GALLERY = 1003
    private const val RC_AUDIO = 1004
    private const val RC_DOCUMENT = 1005
    private const val RC_CROP = 1006
    private const val RC_LIMITED_GALLERY = 1007

    private const val PERM_RC_CAMERA = 2001
    private const val PERM_RC_GALLERY = 2002
    private const val PERM_RC_AUDIO = 2003
  }

  private var pendingPromise: Promise? = null
  private var pendingOptions: ReadableMap? = null
  private var cameraCaptureUri: Uri? = null

  private val activityEventListener: ActivityEventListener = object : BaseActivityEventListener() {
    override fun onActivityResult(
      activity: Activity,
      requestCode: Int,
      resultCode: Int,
      data: Intent?
    ) {
      handleActivityResult(requestCode, resultCode, data)
    }
  }

  init {
    reactContext.addActivityEventListener(activityEventListener)
  }

  override fun getName(): String {
    return NAME
  }

  // --- PERMISSIONS ---

  override fun checkPermission(type: String, promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.resolve("UNAVAILABLE")
      return
    }

    val permissions = getPermissionsForType(type)
    if (permissions.isEmpty()) {
      // Documents does not require runtime permissions on Android
      promise.resolve("FULL")
      return
    }

    var allGranted = true
    var anyGranted = false

    for (perm in permissions) {
      val res = ContextCompat.checkSelfPermission(activity, perm)
      if (res == PackageManager.PERMISSION_GRANTED) {
        anyGranted = true
      } else {
        allGranted = false
      }
    }

    if (allGranted) {
      promise.resolve("FULL")
    } else if (anyGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type == "photoLibrary") {
      // Android 14 limited media access
      promise.resolve("LIMITED")
    } else {
      promise.resolve("DENIED")
    }
  }

  override fun requestPermission(type: String, promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.resolve("UNAVAILABLE")
      return
    }

    val permissions = getPermissionsForType(type)
    if (permissions.isEmpty()) {
      promise.resolve("FULL")
      return
    }

    if (activity is PermissionAwareActivity) {
      val listener = PermissionListener { requestCode, _, grantResults ->
        var allGranted = true
        var anyGranted = false
        for (res in grantResults) {
          if (res == PackageManager.PERMISSION_GRANTED) {
            anyGranted = true
          } else {
            allGranted = false
          }
        }
        if (allGranted) {
          promise.resolve("FULL")
        } else if (anyGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && type == "photoLibrary") {
          promise.resolve("LIMITED")
        } else {
          // Check if blocked (permanently denied)
          var blocked = false
          for (perm in permissions) {
            if (!activity.shouldShowRequestPermissionRationale(perm)) {
              blocked = true
              break
            }
          }
          if (blocked) {
            promise.resolve("BLOCKED")
          } else {
            promise.resolve("DENIED")
          }
        }
        true
      }

      val rc = when (type) {
        "camera" -> PERM_RC_CAMERA
        "photoLibrary" -> PERM_RC_GALLERY
        "audio" -> PERM_RC_AUDIO
        else -> 2999
      }
      activity.requestPermissions(permissions.toTypedArray(), rc, listener)
    } else {
      promise.resolve("UNAVAILABLE")
    }
  }

  private fun getPermissionsForType(type: String): List<String> {
    return when (type) {
      "camera" -> listOf(Manifest.permission.CAMERA)
      "photoLibrary" -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
          )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
          )
        } else {
          listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
      }
      "audio" -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          listOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
          listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
      }
      else -> emptyList()
    }
  }

  override fun openSettings(promise: Promise) {
    try {
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", reactContext.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      reactContext.startActivity(intent)
      promise.resolve(true)
    } catch (e: Exception) {
      promise.resolve(false)
    }
  }

  override fun presentLimitedLibraryPicker(promise: Promise) {
    // Only applies on iOS, resolved instantly on Android
    promise.resolve(null)
  }

  // --- ACTIONS ---

  override fun openCamera(options: ReadableMap, promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.reject("ACTIVITY_NOT_FOUND", "Current activity is null")
      return
    }

    pendingPromise = promise
    pendingOptions = options

    val mediaType = if (options.hasKey("mediaType")) options.getString("mediaType") else "photo"
    val isVideo = mediaType == "video"

    val tempFile = if (isVideo) {
      File(reactContext.cacheDir, "camera_${UUID.randomUUID()}.mp4")
    } else {
      File(reactContext.cacheDir, "camera_${UUID.randomUUID()}.jpg")
    }

    val authority = "${reactContext.packageName}.smartmediapicker.fileprovider"
    cameraCaptureUri = FileProvider.getUriForFile(activity, authority, tempFile)

    val intent = if (isVideo) {
      Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraCaptureUri)
        if (options.hasKey("durationLimit")) {
          putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, options.getInt("durationLimit"))
        }
      }
    } else {
      Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraCaptureUri)
      }
    }

    val rc = if (isVideo) RC_CAMERA_VIDEO else RC_CAMERA_PHOTO
    activity.startActivityForResult(intent, rc)
  }

  override fun openGallery(options: ReadableMap, promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.reject("ACTIVITY_NOT_FOUND", "Current activity is null")
      return
    }

    pendingPromise = promise
    pendingOptions = options

    if (isLimitedStoragePermission()) {
      launchLimitedAssetPicker(options, promise)
      return
    }

    val mediaType = if (options.hasKey("mediaType")) options.getString("mediaType") else "photo"
    val limit = if (options.hasKey("selectionLimit")) options.getInt("selectionLimit") else 1
    val isMultiple = limit == 0 || limit > 1

    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val action = "android.provider.action.PICK_IMAGES"
      val pickerIntent = Intent(action).apply {
        if (mediaType == "photo") {
          type = "image/*"
        } else if (mediaType == "video") {
          type = "video/*"
        }
        if (isMultiple && limit > 0) {
          putExtra("android.provider.extra.PICK_IMAGES_MAX", limit)
        }
      }
      if (pickerIntent.resolveActivity(activity.packageManager) != null) {
        pickerIntent
      } else {
        createLegacyGalleryIntent(mediaType, isMultiple)
      }
    } else {
      createLegacyGalleryIntent(mediaType, isMultiple)
    }

    activity.startActivityForResult(Intent.createChooser(intent, "Select Media"), RC_GALLERY)
  }

  private fun createLegacyGalleryIntent(mediaType: String?, isMultiple: Boolean): Intent {
    return Intent(Intent.ACTION_GET_CONTENT).apply {
      type = when (mediaType) {
        "photo" -> "image/*"
        "video" -> "video/*"
        else -> "*/*"
      }
      if (mediaType == "mixed") {
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
      }
      if (isMultiple) {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      }
      addCategory(Intent.CATEGORY_OPENABLE)
    }
  }


  private fun isLimitedStoragePermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val hasSelected = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
      val hasImages = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
      val hasVideo = ContextCompat.checkSelfPermission(reactContext, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
      return hasSelected && !hasImages && !hasVideo
    }
    return false
  }

  private fun launchLimitedAssetPicker(options: ReadableMap, promise: Promise) {
    val activity = currentActivity ?: return
    pendingPromise = promise
    pendingOptions = options

    val mediaType = if (options.hasKey("mediaType")) options.getString("mediaType") else "photo"
    val limit = if (options.hasKey("selectionLimit")) options.getInt("selectionLimit") else 1

    val intent = Intent(activity, SmartMediaPickerLimitedAssetPickerActivity::class.java).apply {
      putExtra("mediaType", mediaType)
      putExtra("selectionLimit", limit)
    }

    activity.startActivityForResult(intent, RC_LIMITED_GALLERY)
  }

  override fun openAudioPicker(options: ReadableMap, promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.reject("ACTIVITY_NOT_FOUND", "Current activity is null")
      return
    }

    pendingPromise = promise
    pendingOptions = options

    val limit = if (options.hasKey("selectionLimit")) options.getInt("selectionLimit") else 1
    val isMultiple = limit == 0 || limit > 1

    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
      type = "audio/*"
      if (isMultiple) {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      }
      addCategory(Intent.CATEGORY_OPENABLE)
    }

    activity.startActivityForResult(Intent.createChooser(intent, "Select Audio"), RC_AUDIO)
  }

  override fun openDocumentPicker(options: ReadableMap, promise: Promise) {
    val activity = currentActivity
    if (activity == null) {
      promise.reject("ACTIVITY_NOT_FOUND", "Current activity is null")
      return
    }

    pendingPromise = promise
    pendingOptions = options

    val limit = if (options.hasKey("selectionLimit")) options.getInt("selectionLimit") else 1
    val isMultiple = limit == 0 || limit > 1

    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
      type = "*/*"
      if (options.hasKey("mimeTypes")) {
        val typesList = options.getArray("mimeTypes")
        if (typesList != null && typesList.size() > 0) {
          val typesArray = Array(typesList.size()) { i -> typesList.getString(i)!! }
          putExtra(Intent.EXTRA_MIME_TYPES, typesArray)
        }
      }
      if (isMultiple) {
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      }
      addCategory(Intent.CATEGORY_OPENABLE)
    }

    activity.startActivityForResult(Intent.createChooser(intent, "Select Document"), RC_DOCUMENT)
  }

  // --- PROCESSING API ---

  override fun compressImage(uri: String, options: ReadableMap, promise: Promise) {
    Thread {
      try {
        val srcUri = Uri.parse(uri)
        val cacheFile = compressImageInternal(srcUri, options)
        val bitmap = loadBitmap(cacheFile) ?: throw Exception("Failed to load compressed image")

        val result = Arguments.createMap().apply {
          putString("uri", Uri.fromFile(cacheFile).toString())
          putDouble("size", cacheFile.length().toDouble())
          putInt("width", bitmap.width)
          putInt("height", bitmap.height)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("COMPRESSION_ERROR", e.message, e)
      }
    }.start()
  }

  override fun compressVideo(uri: String, options: ReadableMap, promise: Promise) {
    val srcUri = Uri.parse(uri)
    val quality = if (options.hasKey("quality")) options.getString("quality") ?: "medium" else "medium"
    val destFile = File(reactContext.cacheDir, "compressed_video_${UUID.randomUUID()}.mp4")

    SmartMediaPickerVideoCompressor.compressVideo(
      reactContext,
      srcUri,
      destFile,
      quality,
      onSuccess = { compressedFile, width, height, duration ->
        val result = Arguments.createMap().apply {
          putString("uri", Uri.fromFile(compressedFile).toString())
          putDouble("size", compressedFile.length().toDouble())
          putInt("width", width)
          putInt("height", height)
          putDouble("duration", duration.toDouble() / 1000.0)
        }
        promise.resolve(result)
      },
      onFailure = { e ->
        promise.reject("VIDEO_COMPRESSION_FAILED", e.message, e)
      }
    )
  }

  override fun getVideoThumbnail(uri: String, options: ReadableMap, promise: Promise) {
    Thread {
      try {
        val srcUri = Uri.parse(uri)
        val timeOffset = if (options.hasKey("timeOffset")) options.getDouble("timeOffset") else 1.0
        val quality = if (options.hasKey("quality")) options.getDouble("quality") else 0.8

        val retriever = MediaMetadataRetriever()
        if (srcUri.scheme == "content") {
          reactContext.contentResolver.openFileDescriptor(srcUri, "r")?.use { pfd ->
            retriever.setDataSource(pfd.fileDescriptor)
          }
        } else {
          retriever.setDataSource(srcUri.path)
        }

        val timeUs = (timeOffset * 1000000.0).toLong()
        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
          ?: throw Exception("Failed to extract frame at $timeOffset seconds")

        val outFile = File(reactContext.cacheDir, "thumb_${UUID.randomUUID()}.jpg")
        val outStream = FileOutputStream(outFile)
        frame.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), outStream)
        outStream.flush()
        outStream.close()
        retriever.release()

        val result = Arguments.createMap().apply {
          putString("uri", Uri.fromFile(outFile).toString())
          putInt("width", frame.width)
          putInt("height", frame.height)
        }
        promise.resolve(result)
      } catch (e: Exception) {
        promise.reject("THUMBNAIL_ERROR", e.message, e)
      }
    }.start()
  }

  // --- INTERNAL HELPER IMPLEMENTATIONS ---

  private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    val promise = pendingPromise ?: return
    val options = pendingOptions ?: return

    // Clean pending state immediately
    pendingPromise = null
    pendingOptions = null

    if (resultCode != Activity.RESULT_OK) {
      if (requestCode == RC_CAMERA_PHOTO || requestCode == RC_CAMERA_VIDEO) {
        cameraCaptureUri = null
      }
      promise.reject("USER_CANCELLED", "User cancelled operation")
      return
    }

    Thread {
      try {
        when (requestCode) {
          RC_CAMERA_PHOTO -> {
            val uri = cameraCaptureUri ?: throw Exception("Capture URI is missing")
            cameraCaptureUri = null
            processAndResolveImage(uri, options, promise)
          }
          RC_CAMERA_VIDEO -> {
            val uri = cameraCaptureUri ?: throw Exception("Capture URI is missing")
            cameraCaptureUri = null
            processAndResolveVideo(uri, options, promise)
          }
          RC_LIMITED_GALLERY -> {
            val uriStrings = data?.getStringArrayListExtra("selectedUris") ?: throw Exception("No media selected")
            val uris = uriStrings.map { Uri.parse(it) }
            if (uris.isEmpty()) throw Exception("No media selected")

            if (uris.size == 1) {
              val uri = uris[0]
              val mime = getMimeType(uri)
              if (mime.startsWith("video/")) {
                processAndResolveVideo(uri, options, promise)
              } else {
                processAndResolveImage(uri, options, promise)
              }
            } else {
              val assetsArray = Arguments.createArray()
              for (uri in uris) {
                assetsArray.pushMap(resolveMediaAsset(uri, options))
              }
              val result = Arguments.createMap().apply {
                putArray("assets", assetsArray)
              }
              promise.resolve(result)
            }
          }
          RC_GALLERY -> {
            if (data == null) throw Exception("No media selected")
            val uris = getSelectedUris(data)
            if (uris.isEmpty()) throw Exception("No media selected")

            if (uris.size == 1) {
              val uri = uris[0]
              val mime = getMimeType(uri)
              if (mime.startsWith("video/")) {
                processAndResolveVideo(uri, options, promise)
              } else {
                processAndResolveImage(uri, options, promise)
              }
            } else {
              // Multi-select image & video flow
              val assetsArray = Arguments.createArray()
              for (uri in uris) {
                assetsArray.pushMap(resolveMediaAsset(uri, options))
              }
              val result = Arguments.createMap().apply {
                putArray("assets", assetsArray)
              }
              promise.resolve(result)
            }
          }
          RC_AUDIO -> {
            if (data == null) throw Exception("No audio selected")
            val uris = getSelectedUris(data)
            val assetsArray = Arguments.createArray()
            for (uri in uris) {
              assetsArray.pushMap(resolveMediaAsset(uri, options))
            }
            val result = Arguments.createMap().apply {
              putArray("assets", assetsArray)
            }
            promise.resolve(result)
          }
          RC_DOCUMENT -> {
            if (data == null) throw Exception("No document selected")
            val uris = getSelectedUris(data)
            val assetsArray = Arguments.createArray()
            for (uri in uris) {
              assetsArray.pushMap(resolveMediaAsset(uri, options))
            }
            val result = Arguments.createMap().apply {
              putArray("assets", assetsArray)
            }
            promise.resolve(result)
          }
          RC_CROP -> {
            val croppedUriStr = data?.getStringExtra("croppedImageUri") ?: throw Exception("Cropping failed")
            val croppedUri = Uri.parse(croppedUriStr)
            // Carry out compression if requested
            val finalUri = if (options.hasKey("compressOptions")) {
              val compOpt = options.getMap("compressOptions")!!
              Uri.fromFile(compressImageInternal(croppedUri, compOpt))
            } else {
              croppedUri
            }
            val asset = resolveMediaAsset(finalUri, options)
            val result = Arguments.createMap().apply {
              putArray("assets", Arguments.createArray().apply { pushMap(asset) })
            }
            promise.resolve(result)
          }
        }
      } catch (e: Exception) {
        promise.reject("PICKER_ERROR", e.message, e)
      }
    }.start()
  }

  private fun getSelectedUris(data: Intent): List<Uri> {
    val uris = mutableListOf<Uri>()
    val clipData = data.clipData
    if (clipData != null) {
      for (i in 0 until clipData.itemCount) {
        uris.add(clipData.getItemAt(i).uri)
      }
    } else {
      val uri = data.data
      if (uri != null) {
        uris.add(uri)
      }
    }
    return uris
  }

  private fun processAndResolveImage(uri: Uri, options: ReadableMap, promise: Promise) {
    // 1. Check Cropping
    if (options.hasKey("cropOptions")) {
      val cropOpt = options.getMap("cropOptions")!!
      launchCropActivity(uri, cropOpt, options, promise)
      return
    }

    // 2. Check Compression
    val finalUri = if (options.hasKey("compressOptions")) {
      val compOpt = options.getMap("compressOptions")!!
      Uri.fromFile(compressImageInternal(uri, compOpt))
    } else {
      uri
    }

    val asset = resolveMediaAsset(finalUri, options)
    val result = Arguments.createMap().apply {
      putArray("assets", Arguments.createArray().apply { pushMap(asset) })
    }
    promise.resolve(result)
  }

  private fun processAndResolveVideo(uri: Uri, options: ReadableMap, promise: Promise) {
    val asset = resolveMediaAsset(uri, options)
    val result = Arguments.createMap().apply {
      putArray("assets", Arguments.createArray().apply { pushMap(asset) })
    }
    promise.resolve(result)
  }

  private fun launchCropActivity(
    uri: Uri,
    cropOptions: ReadableMap,
    parentOptions: ReadableMap,
    promise: Promise
  ) {
    val activity = currentActivity ?: return
    pendingPromise = promise
    pendingOptions = parentOptions

    // We must copy the image from ContentResolver to local cache file first,
    // so our CropActivity can load it directly via File scheme if needed
    val tempFile = File(reactContext.cacheDir, "crop_input_${UUID.randomUUID()}.jpg")
    copyStreamToFile(uri, tempFile)

    var aspectX = 0
    var aspectY = 0
    if (cropOptions.hasKey("aspectRatio")) {
      val ratioArray = cropOptions.getArray("aspectRatio")
      if (ratioArray != null && ratioArray.size() >= 2) {
        aspectX = ratioArray.getInt(0)
        aspectY = ratioArray.getInt(1)
      }
    }

    val intent = Intent(activity, SmartMediaPickerCropActivity::class.java).apply {
      putExtra("imageUri", Uri.fromFile(tempFile).toString())
      putExtra("aspectRatioX", aspectX)
      putExtra("aspectRatioY", aspectY)
    }

    activity.startActivityForResult(intent, RC_CROP)
  }

  private fun compressImageInternal(uri: Uri, compressOptions: ReadableMap): File {
    val maxW = if (compressOptions.hasKey("maxWidth")) compressOptions.getInt("maxWidth") else 0
    val maxH = if (compressOptions.hasKey("maxHeight")) compressOptions.getInt("maxHeight") else 0
    val quality = if (compressOptions.hasKey("quality")) (compressOptions.getDouble("quality") * 100).toInt() else 90

    // Load bitmap using ContentResolver
    val inputStream = reactContext.contentResolver.openInputStream(uri)
      ?: throw Exception("Could not open stream for compression")
    var bitmap = BitmapFactory.decodeStream(inputStream)
    inputStream.close()

    // Rotate bitmap based on EXIF before compressing
    val rotation = getOrientationRotation(uri)
    if (rotation != 0) {
      val rotateMatrix = Matrix().apply { postRotate(rotation.toFloat()) }
      val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, rotateMatrix, true)
      if (rotated != bitmap) {
        bitmap.recycle()
        bitmap = rotated
      }
    }

    // Scale maintaining aspect ratio if maxW or maxH is specified
    if ((maxW > 0 && bitmap.width > maxW) || (maxH > 0 && bitmap.height > maxH)) {
      val scale = min(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
      val scaleFactor = if (scale == 0f) 1f else scale
      val targetW = (bitmap.width * scaleFactor).toInt()
      val targetH = (bitmap.height * scaleFactor).toInt()
      val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
      if (scaled != bitmap) {
        bitmap.recycle()
        bitmap = scaled
      }
    }

    val compressedFile = File(reactContext.cacheDir, "comp_${UUID.randomUUID()}.jpg")
    val outStream = FileOutputStream(compressedFile)
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outStream)
    outStream.flush()
    outStream.close()
    return compressedFile
  }

  private fun resolveMediaAsset(uri: Uri, options: ReadableMap): WritableMap {
    val resolver = reactContext.contentResolver
    val assetMap = Arguments.createMap()

    val name = getFileName(uri)
    val size = getFileSize(uri)
    val mime = getMimeType(uri)

    // Save/Copy file to local cache so developer receives a local file path URI scheme that is always accessible
    val localFile = File(reactContext.cacheDir, "asset_${UUID.randomUUID()}_$name")
    copyStreamToFile(uri, localFile)
    val finalLocalUri = Uri.fromFile(localFile).toString()

    assetMap.putString("uri", finalLocalUri)
    assetMap.putString("name", name)
    assetMap.putString("type", mime)
    assetMap.putDouble("size", size.toDouble())

    if (mime.startsWith("image/")) {
      val dimensions = getMediaDimensions(Uri.fromFile(localFile))
      assetMap.putInt("width", dimensions.first)
      assetMap.putInt("height", dimensions.second)
      // Extract EXIF data
      try {
        assetMap.putMap("exif", getExifMetadata(Uri.fromFile(localFile)))
      } catch (e: Exception) {
        // EXIF optional
      }
    } else if (mime.startsWith("video/")) {
      val durationMs = getVideoDuration(Uri.fromFile(localFile))
      val dimensions = getMediaDimensions(Uri.fromFile(localFile))
      assetMap.putInt("width", dimensions.first)
      assetMap.putInt("height", dimensions.second)
      assetMap.putDouble("duration", durationMs.toDouble() / 1000.0)

      // Auto-generate video thumbnail if requested or default
      try {
        val thumbFile = File(reactContext.cacheDir, "thumb_${UUID.randomUUID()}.jpg")
        val retriever = MediaMetadataRetriever().apply { setDataSource(localFile.absolutePath) }
        val frame = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (frame != null) {
          val out = FileOutputStream(thumbFile)
          frame.compress(Bitmap.CompressFormat.JPEG, 80, out)
          out.flush()
          out.close()
          assetMap.putString("thumbnailUri", Uri.fromFile(thumbFile).toString())
        }
        retriever.release()
      } catch (e: Exception) {
        // Thumbnail generation optional
      }
    }

    return assetMap
  }

  // --- GENERAL SYSTEM FILE & METADATA UTILS ---

  private fun getFileName(uri: Uri): String {
    if (uri.scheme == "content") {
      val cursor = reactContext.contentResolver.query(uri, null, null, null, null)
      cursor?.use {
        if (it.moveToFirst()) {
          val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index != -1) return it.getString(index)
        }
      }
    }
    return uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
  }

  private fun getFileSize(uri: Uri): Long {
    if (uri.scheme == "content") {
      val cursor = reactContext.contentResolver.query(uri, null, null, null, null)
      cursor?.use {
        if (it.moveToFirst()) {
          val index = it.getColumnIndex(OpenableColumns.SIZE)
          if (index != -1) return it.getLong(index)
        }
      }
    } else if (uri.scheme == "file") {
      return File(uri.path ?: "").length()
    }
    return 0L
  }

  private fun getMimeType(uri: Uri): String {
    return if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
      reactContext.contentResolver.getType(uri) ?: "application/octet-stream"
    } else {
      val fileExtension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uri.toString())
      android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase())
        ?: "application/octet-stream"
    }
  }

  private fun getMediaDimensions(uri: Uri): Pair<Int, Int> {
    return try {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      val stream = reactContext.contentResolver.openInputStream(uri)
      BitmapFactory.decodeStream(stream, null, options)
      stream?.close()
      Pair(options.outWidth, options.outHeight)
    } catch (e: Exception) {
      Pair(0, 0)
    }
  }

  private fun getOrientationRotation(uri: Uri): Int {
    return try {
      val stream = reactContext.contentResolver.openInputStream(uri) ?: return 0
      val exif = ExifInterface(stream)
      val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      stream.close()
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
      }
    } catch (e: Exception) {
      0
    }
  }

  private fun getExifMetadata(uri: Uri): WritableMap {
    val exifMap = Arguments.createMap()
    val stream = reactContext.contentResolver.openInputStream(uri) ?: return exifMap
    val exif = ExifInterface(stream)
    val tags = arrayOf(
      ExifInterface.TAG_ORIENTATION,
      ExifInterface.TAG_DATETIME,
      ExifInterface.TAG_MAKE,
      ExifInterface.TAG_MODEL,
      ExifInterface.TAG_IMAGE_WIDTH,
      ExifInterface.TAG_IMAGE_LENGTH,
      ExifInterface.TAG_EXPOSURE_TIME,
      ExifInterface.TAG_F_NUMBER,
      ExifInterface.TAG_ISO_SPEED_RATINGS,
      ExifInterface.TAG_GPS_LATITUDE,
      ExifInterface.TAG_GPS_LONGITUDE
    )
    for (tag in tags) {
      val value = exif.getAttribute(tag)
      if (value != null) {
        exifMap.putString(tag, value)
      }
    }
    val latLong = FloatArray(2)
    if (exif.getLatLong(latLong)) {
      exifMap.putDouble("latitude", latLong[0].toDouble())
      exifMap.putDouble("longitude", latLong[1].toDouble())
    }
    stream.close()
    return exifMap
  }

  private fun getVideoDuration(uri: Uri): Long {
    return try {
      val retriever = MediaMetadataRetriever()
      if (uri.scheme == "content") {
        reactContext.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
          retriever.setDataSource(pfd.fileDescriptor)
        }
      } else {
        retriever.setDataSource(uri.path)
      }
      val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
      retriever.release()
      durationStr?.toLong() ?: 0L
    } catch (e: Exception) {
      0L
    }
  }

  private fun copyStreamToFile(uri: Uri, destFile: File) {
    val inputStream = reactContext.contentResolver.openInputStream(uri)
      ?: throw Exception("Could not open input stream")
    val outputStream = FileOutputStream(destFile)
    val buffer = ByteArray(4096)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
      outputStream.write(buffer, 0, bytesRead)
    }
    outputStream.flush()
    outputStream.close()
    inputStream.close()
  }

  private fun copyStreamToFile(inputStream: InputStream, destFile: File) {
    val outputStream = FileOutputStream(destFile)
    val buffer = ByteArray(4096)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
      outputStream.write(buffer, 0, bytesRead)
    }
    outputStream.flush()
    outputStream.close()
    inputStream.close()
  }

  private fun loadBitmap(file: File): Bitmap? {
    return BitmapFactory.decodeFile(file.absolutePath)
  }
}
