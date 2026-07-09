# react-native-smart-media-picker

[![npm version](https://img.shields.io/npm/v/react-native-smart-media-picker.svg?style=flat-square)](https://www.npmjs.com/package/react-native-smart-media-picker)
[![npm downloads](https://img.shields.io/npm/dm/react-native-smart-media-picker.svg?style=flat-square)](https://www.npmjs.com/package/react-native-smart-media-picker)
[![license](https://img.shields.io/npm/l/react-native-smart-media-picker.svg?style=flat-square)](https://github.com/jigarsolanki0212/react-native-smart-media-picker/blob/main/LICENSE)

A world-class, enterprise-grade, unified media selection and compression library for React Native Android and iOS. 

Built with the **React Native New Architecture (TurboModules)** and backward-compatible with legacy architectures, it abstracts all platform-specific complexity. It eliminates the need for developers to combine multiple third-party packages for camera, gallery, document picking, cropping, and compression.

---

## ✨ Features

- 📸 **Camera**: Captures high-quality photos and videos natively.
- 🖼️ **Photo & Video Gallery**: Modern, fast selection using out-of-process system pickers.
- 🔒 **Unified Permissions**: Abstracts iOS permission dialogs and Android's complex SDK storage permission changes into single state outputs.
- 📐 **Image Cropping**: Fully programmatic interactive cropping overlays (zoom, pan, rotate, aspect ratio locks) built natively in Swift & Kotlin.
- 🗜️ **Image & Video Compression**: High-performance, hardware-accelerated background compression.
- 📑 **Documents & Audio Pickers**: Direct support for file extensions, MIME-types, and SAF (Storage Access Framework).
- 🏷️ **EXIF Metadata**: Complete extraction of EXIF tags (GPS, camera details, formatting metrics).
- 🔍 **Video Thumbnails**: Blazing fast native thumbnail frames extraction.
- 👤 **Limited Media Support**: Fully respects and integrates with iOS Limited Photo Library access and Android 14+ Selected Photos Access (`READ_MEDIA_VISUAL_USER_SELECTED`), showing custom selection overlays restricted *only* to allowed items.

---

## 📦 Installation

```sh
npm install react-native-smart-media-picker
# or
yarn add react-native-smart-media-picker
```

---

## ⚙️ Native Configuration

### iOS Setup

#### 1. Add Info.plist Permissions
Open your app's `ios/YourAppName/Info.plist` file and declare the strings explaining why you require access:

```xml
<key>NSCameraUsageDescription</key>
  <string>This app requires camera access to take profile photos and record videos.</string>
<key>NSMicrophoneUsageDescription</key>
  <string>This app requires microphone access to record sound with video capture.</string>
<key>NSPhotoLibraryUsageDescription</key>
  <string>This app requires photo gallery access to select and upload media.</string>
```

#### 2. Install Pods
Install the autolinked native dependencies:
```bash
cd ios
pod install
cd ..
```

---

### Android Setup

#### 1. Add AndroidManifest Permissions
Open `android/app/src/main/AndroidManifest.xml` and add the required permissions based on your app needs:

```xml
<!-- General permissions -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<!-- Legacy storage compatibility (API <= 32) -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" />

<!-- Modern storage permissions (API >= 33) -->
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

<!-- Selected photos access (Android 14+ / API 34+) -->
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```

---

## 🚀 Usage Guide

### 1. Permission Check & Request

Manage permissions natively with uniform results (`FULL` | `LIMITED` | `DENIED` | `BLOCKED` | `UNAVAILABLE`):

```ts
import { SmartMediaPicker } from 'react-native-smart-media-picker';

const handlePermission = async () => {
  const current = await SmartMediaPicker.checkPermission('photoLibrary');
  
  if (current === 'DENIED') {
    const requested = await SmartMediaPicker.requestPermission('photoLibrary');
    if (requested === 'BLOCKED') {
      // User checked "Don't ask again" - redirect to settings
      SmartMediaPicker.openSettings();
    }
  }
};
```

---

### 2. Gallery Picking (with validation & cropping options)

Allows selecting photos, videos, or mixed media. When gallery permission is **`LIMITED`**, the library launches a restricted selection screen showing **only** the allowed assets chosen by the user.

```ts
import { SmartMediaPicker } from 'react-native-smart-media-picker';

const pickFromGallery = async () => {
  try {
    const result = await SmartMediaPicker.openGallery({
      mediaType: 'photo',
      selectionLimit: 1, // Single image mode
      maxSize: 10 * 1024 * 1024, // Client-side 10MB size limit validation
      cropOptions: {
        aspectRatio: [1, 1], // Lock cropping to square 1:1 format
        enableRotation: true,
      },
      compressOptions: {
        maxWidth: 1200,
        maxHeight: 1200,
        quality: 0.85,
      }
    });

    const selectedAsset = result.assets[0];
    console.log('Uri:', selectedAsset.uri);
    console.log('EXIF data:', selectedAsset.exif);
  } catch (e: any) {
    if (e.code === 'USER_CANCELLED') {
      console.log('User cancelled picker');
    }
  }
};
```

---

### 3. Native Camera Capture

```ts
const capturePhoto = async () => {
  const result = await SmartMediaPicker.openCamera({
    mediaType: 'photo',
    cameraType: 'back',
    cropOptions: {
      freeStyle: true // Allow custom cropping bounds
    }
  });
  console.log('Captured:', result.assets[0].uri);
};
```

---

### 4. Audio & Document Picker

Integrate unified file selection via MIME-types filters:

```ts
// Select PDFs only
const pickDocuments = async () => {
  const result = await SmartMediaPicker.openDocumentPicker({
    mimeTypes: ['application/pdf'],
    selectionLimit: 3
  });
  console.log('Documents selected:', result.assets);
};
```

---

### 5. Media Compressors

Transcode large files asynchronously on native background threads:

```ts
// Compress selected image
const compressedImg = await SmartMediaPicker.compressImage(imageUri, {
  maxWidth: 800,
  quality: 0.7
});

// Transcode large video file
const compressedVid = await SmartMediaPicker.compressVideo(videoUri, {
  quality: 'medium'
});
console.log('New Size:', compressedVid.size);
```

---

## 🗃️ Asset Return Format (`MediaAsset`)

```ts
interface MediaAsset {
  uri: string;           // Local file cache path (accessible by <Image> / <Video>)
  name: string;          // Original filename
  type: string;          // File MIME type (e.g. image/jpeg, video/mp4)
  size: number;          // File size in bytes
  width?: number;        // Image/video width in pixels
  height?: number;       // Image/video height in pixels
  duration?: number;     // Video/audio duration in seconds
  exif?: Record<string, any>; // EXIF metadata tags dictionary
  thumbnailUri?: string; // Auto-generated video thumbnail image URI
}
```

---

## 📄 License

MIT
