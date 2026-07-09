import { Platform } from 'react-native';
import NativeSmartMediaPicker from './NativeSmartMediaPicker';
import type {
  PermissionType,
  PermissionStatus,
  CameraOptions,
  GalleryOptions,
  AudioPickerOptions,
  DocumentPickerOptions,
  ImageCompressionOptions,
  VideoCompressionOptions,
  PickerResult,
  MediaAsset,
} from './types';

export * from './types';

/**
 * Validate asset against developer constraints (e.g. maxSize, extension, MIME type).
 */
function validateAssets(
  assets: MediaAsset[],
  options: { maxSize?: number; allowedMimeTypes?: string[] } = {}
): MediaAsset[] {
  const { maxSize, allowedMimeTypes } = options;
  return assets.filter((asset) => {
    // 1. Max size validation
    if (maxSize && asset.size > maxSize) {
      throw new Error(
        `File "${asset.name}" exceeds the maximum allowed size of ${maxSize} bytes.`
      );
    }

    // 2. MIME type validation
    if (allowedMimeTypes && allowedMimeTypes.length > 0) {
      const mimeMatch = allowedMimeTypes.some((mime) => {
        if (mime.endsWith('/*')) {
          const prefix = mime.split('/')[0];
          return asset.type.startsWith(prefix + '/');
        }
        return asset.type.toLowerCase() === mime.toLowerCase();
      });
      if (!mimeMatch) {
        throw new Error(
          `File "${asset.name}" has type "${asset.type}" which is not in the allowed list: ${allowedMimeTypes.join(', ')}.`
        );
      }
    }

    return true;
  });
}

export const SmartMediaPicker = {
  /**
   * Check permission status for a given service.
   */
  async checkPermission(type: PermissionType): Promise<PermissionStatus> {
    const status = await NativeSmartMediaPicker.checkPermission(type);
    return status as PermissionStatus;
  },

  /**
   * Request permission for a given service.
   */
  async requestPermission(type: PermissionType): Promise<PermissionStatus> {
    const status = await NativeSmartMediaPicker.requestPermission(type);
    return status as PermissionStatus;
  },

  /**
   * Open the app system settings.
   */
  async openSettings(): Promise<boolean> {
    return await NativeSmartMediaPicker.openSettings();
  },

  /**
   * Present the limited library selection picker (iOS only).
   */
  async presentLimitedLibraryPicker(): Promise<void> {
    if (Platform.OS === 'ios') {
      await NativeSmartMediaPicker.presentLimitedLibraryPicker();
    }
  },

  /**
   * Launch native Camera to capture a photo or video.
   */
  async openCamera(
    options: CameraOptions & {
      maxSize?: number;
      allowedMimeTypes?: string[];
    } = {}
  ): Promise<PickerResult> {
    const result = (await NativeSmartMediaPicker.openCamera(
      options
    )) as PickerResult;
    if (result && result.assets) {
      result.assets = validateAssets(result.assets, options);
    }
    return result;
  },

  /**
   * Launch native Gallery picker (uses Android Photo Picker or iOS PHPickerViewController).
   */
  async openGallery(
    options: GalleryOptions & {
      maxSize?: number;
      allowedMimeTypes?: string[];
    } = {}
  ): Promise<PickerResult> {
    const result = (await NativeSmartMediaPicker.openGallery(
      options
    )) as PickerResult;
    if (result && result.assets) {
      result.assets = validateAssets(result.assets, options);
    }
    return result;
  },

  /**
   * Open native Audio picker.
   */
  async openAudioPicker(
    options: AudioPickerOptions & {
      maxSize?: number;
      allowedMimeTypes?: string[];
    } = {}
  ): Promise<PickerResult> {
    const result = (await NativeSmartMediaPicker.openAudioPicker(
      options
    )) as PickerResult;
    if (result && result.assets) {
      result.assets = validateAssets(result.assets, options);
    }
    return result;
  },

  /**
   * Open native Document picker.
   */
  async openDocumentPicker(
    options: DocumentPickerOptions & {
      maxSize?: number;
      allowedMimeTypes?: string[];
    } = {}
  ): Promise<PickerResult> {
    const result = (await NativeSmartMediaPicker.openDocumentPicker(
      options
    )) as PickerResult;
    if (result && result.assets) {
      result.assets = validateAssets(result.assets, options);
    }
    return result;
  },

  /**
   * Compress an image asynchronously.
   */
  async compressImage(
    uri: string,
    options: ImageCompressionOptions = {}
  ): Promise<{ uri: string; size: number; width: number; height: number }> {
    return (await NativeSmartMediaPicker.compressImage(uri, options)) as {
      uri: string;
      size: number;
      width: number;
      height: number;
    };
  },

  /**
   * Compress a video file asynchronously.
   */
  async compressVideo(
    uri: string,
    options: VideoCompressionOptions = {}
  ): Promise<{ uri: string; size: number; duration: number }> {
    return (await NativeSmartMediaPicker.compressVideo(uri, options)) as {
      uri: string;
      size: number;
      duration: number;
    };
  },

  /**
   * Generate video thumbnail frame.
   */
  async getVideoThumbnail(
    uri: string,
    options: { timeOffset?: number; quality?: number } = {}
  ): Promise<{ uri: string; width: number; height: number }> {
    return (await NativeSmartMediaPicker.getVideoThumbnail(uri, options)) as {
      uri: string;
      width: number;
      height: number;
    };
  },
};

export default SmartMediaPicker;
