import { jest, describe, it, expect, beforeEach } from '@jest/globals';
import SmartMediaPicker from '../index';
import NativeSmartMediaPicker from '../NativeSmartMediaPicker';

// Mock NativeSmartMediaPicker
jest.mock('../NativeSmartMediaPicker', () => {
  return {
    checkPermission: jest.fn(),
    requestPermission: jest.fn(),
    openSettings: jest.fn(),
    presentLimitedLibraryPicker: jest.fn(),
    openCamera: jest.fn(),
    openGallery: jest.fn(),
    openAudioPicker: jest.fn(),
    openDocumentPicker: jest.fn(),
    compressImage: jest.fn(),
    compressVideo: jest.fn(),
    getVideoThumbnail: jest.fn(),
  };
});

describe('SmartMediaPicker Unit Tests', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe('Permissions API', () => {
    it('checks permissions correctly', async () => {
      const mockCheck = NativeSmartMediaPicker.checkPermission as any;
      mockCheck.mockResolvedValue('FULL');

      const result = await SmartMediaPicker.checkPermission('camera');
      expect(mockCheck).toHaveBeenCalledWith('camera');
      expect(result).toBe('FULL');
    });

    it('requests permissions correctly', async () => {
      const mockRequest = NativeSmartMediaPicker.requestPermission as any;
      mockRequest.mockResolvedValue('DENIED');

      const result = await SmartMediaPicker.requestPermission('photoLibrary');
      expect(mockRequest).toHaveBeenCalledWith('photoLibrary');
      expect(result).toBe('DENIED');
    });

    it('opens settings correctly', async () => {
      const mockSettings = NativeSmartMediaPicker.openSettings as any;
      mockSettings.mockResolvedValue(true);

      const result = await SmartMediaPicker.openSettings();
      expect(mockSettings).toHaveBeenCalled();
      expect(result).toBe(true);
    });
  });

  describe('Pickers & Camera API', () => {
    const mockAsset = {
      uri: 'file:///path/to/image.jpg',
      name: 'image.jpg',
      type: 'image/jpeg',
      size: 5000,
    };

    it('opens camera and returns assets', async () => {
      const mockCamera = NativeSmartMediaPicker.openCamera as any;
      mockCamera.mockResolvedValue({ assets: [mockAsset] });

      const result = await SmartMediaPicker.openCamera({ mediaType: 'photo' });
      expect(mockCamera).toHaveBeenCalledWith({ mediaType: 'photo' });
      expect(result.assets).toEqual([mockAsset]);
    });

    it('opens gallery and performs size validation', async () => {
      const mockGallery = NativeSmartMediaPicker.openGallery as any;
      mockGallery.mockResolvedValue({ assets: [mockAsset] });

      // Validates successfully because 5000 <= 10000
      const result = await SmartMediaPicker.openGallery({
        selectionLimit: 1,
        maxSize: 10000,
      });
      expect(result.assets).toEqual([mockAsset]);

      // Fails because 5000 > 1000
      await expect(
        SmartMediaPicker.openGallery({ selectionLimit: 1, maxSize: 1000 })
      ).rejects.toThrow('exceeds the maximum allowed size');
    });

    it('performs allowedMimeTypes validation', async () => {
      const mockGallery = NativeSmartMediaPicker.openGallery as any;
      mockGallery.mockResolvedValue({ assets: [mockAsset] });

      // Validates successfully
      const result = await SmartMediaPicker.openGallery({
        allowedMimeTypes: ['image/*'],
      });
      expect(result.assets).toEqual([mockAsset]);

      // Fails MIME validation
      await expect(
        SmartMediaPicker.openGallery({ allowedMimeTypes: ['video/*'] })
      ).rejects.toThrow('is not in the allowed list');
    });
  });

  describe('Processing API', () => {
    it('compresses images correctly', async () => {
      const mockCompress = NativeSmartMediaPicker.compressImage as any;
      const expectedResult = {
        uri: 'file:///compressed.jpg',
        size: 1200,
        width: 800,
        height: 600,
      };
      mockCompress.mockResolvedValue(expectedResult);

      const result = await SmartMediaPicker.compressImage(
        'file:///original.jpg',
        { quality: 0.8 }
      );
      expect(mockCompress).toHaveBeenCalledWith('file:///original.jpg', {
        quality: 0.8,
      });
      expect(result).toEqual(expectedResult);
    });

    it('compresses videos correctly', async () => {
      const mockCompressVideo = NativeSmartMediaPicker.compressVideo as any;
      const expectedResult = {
        uri: 'file:///compressed.mp4',
        size: 45000,
        duration: 15.0,
      };
      mockCompressVideo.mockResolvedValue(expectedResult);

      const result = await SmartMediaPicker.compressVideo(
        'file:///original.mp4',
        { quality: 'medium' }
      );
      expect(mockCompressVideo).toHaveBeenCalledWith('file:///original.mp4', {
        quality: 'medium',
      });
      expect(result).toEqual(expectedResult);
    });

    it('generates video thumbnails correctly', async () => {
      const mockThumb = NativeSmartMediaPicker.getVideoThumbnail as any;
      const expectedResult = {
        uri: 'file:///thumb.jpg',
        width: 320,
        height: 240,
      };
      mockThumb.mockResolvedValue(expectedResult);

      const result = await SmartMediaPicker.getVideoThumbnail(
        'file:///video.mp4',
        { timeOffset: 2.5 }
      );
      expect(mockThumb).toHaveBeenCalledWith('file:///video.mp4', {
        timeOffset: 2.5,
      });
      expect(result).toEqual(expectedResult);
    });
  });
});
