export type PermissionType = 'camera' | 'photoLibrary' | 'audio' | 'documents';

export type PermissionStatus =
  'FULL' | 'LIMITED' | 'DENIED' | 'BLOCKED' | 'UNAVAILABLE';

export interface CropOptions {
  enableRotation?: boolean;
  aspectRatio?: [number, number]; // [width, height], e.g. [1, 1], [16, 9]. If omitted, free crop.
  freeStyle?: boolean;
}

export interface ImageCompressionOptions {
  maxWidth?: number;
  maxHeight?: number;
  quality?: number; // 0.0 to 1.0
}

export interface VideoCompressionOptions {
  quality?: 'low' | 'medium' | 'high';
  maxDuration?: number;
}

export interface CameraOptions {
  mediaType?: 'photo' | 'video' | 'mixed';
  quality?: number; // 0.0 to 1.0 for image/video quality
  saveToPhotoLibrary?: boolean;
  durationLimit?: number; // in seconds
  cameraType?: 'front' | 'back';
  cropOptions?: CropOptions;
  compressOptions?: ImageCompressionOptions;
}

export interface GalleryOptions {
  mediaType?: 'photo' | 'video' | 'mixed';
  selectionLimit?: number; // 0 for unlimited. Default is 1.
  cropOptions?: CropOptions;
  compressOptions?: ImageCompressionOptions;
  videoCompressOptions?: VideoCompressionOptions;
}

export interface AudioPickerOptions {
  selectionLimit?: number;
}

export interface DocumentPickerOptions {
  mimeTypes?: string[]; // e.g. ['application/pdf', 'application/msword']
  selectionLimit?: number;
}

export interface MediaAsset {
  uri: string; // Absolute local file path or cache URI
  name: string; // File name (e.g. image.jpg)
  type: string; // MIME type (e.g. image/jpeg)
  size: number; // File size in bytes
  width?: number; // Image/video width in pixels
  height?: number; // Image/video height in pixels
  duration?: number; // Duration in seconds (video/audio only)
  exif?: Record<string, any>; // Parsed EXIF metadata
  thumbnailUri?: string; // Cache URI of generated video thumbnail
}

export interface PickerResult {
  assets: MediaAsset[];
}
