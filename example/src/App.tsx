import { useState, useEffect } from 'react';
import {
  SafeAreaView,
  StyleSheet,
  Text,
  View,
  TouchableOpacity,
  ScrollView,
  Image,
  Alert,
  ActivityIndicator,
  Platform,
} from 'react-native';
import {
  SmartMediaPicker,
  type MediaAsset,
  type PermissionStatus,
} from 'react-native-smart-media-picker';

export default function App() {
  const [cameraPerm, setCameraPerm] = useState<PermissionStatus>('DENIED');
  const [galleryPerm, setGalleryPerm] = useState<PermissionStatus>('DENIED');
  const [selectedAssets, setSelectedAssets] = useState<MediaAsset[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    checkPermissions();
  }, []);

  const checkPermissions = async () => {
    try {
      const cameraStatus = await SmartMediaPicker.checkPermission('camera');
      const galleryStatus =
        await SmartMediaPicker.checkPermission('photoLibrary');
      setCameraPerm(cameraStatus);
      setGalleryPerm(galleryStatus);
    } catch (e: any) {
      console.log('Error checking permissions:', e.message);
    }
  };

  const requestPermission = async (type: 'camera' | 'photoLibrary') => {
    try {
      const status = await SmartMediaPicker.requestPermission(type);
      if (type === 'camera') setCameraPerm(status);
      else setGalleryPerm(status);

      if (status === 'BLOCKED') {
        Alert.alert(
          'Permission Blocked',
          `Please enable ${type} permission in settings.`,
          [
            { text: 'Cancel', style: 'cancel' },
            {
              text: 'Open Settings',
              onPress: () => SmartMediaPicker.openSettings(),
            },
          ]
        );
      }
    } catch (e: any) {
      Alert.alert('Permission Error', e.message);
    }
  };

  const handleLaunchCamera = async () => {
    if (cameraPerm !== 'FULL') {
      await requestPermission('camera');
      return;
    }

    setLoading(true);
    try {
      const result = await SmartMediaPicker.openCamera({
        mediaType: 'photo',
        cropOptions: {
          aspectRatio: [1, 1],
          enableRotation: true,
        },
        compressOptions: {
          maxWidth: 1080,
          maxHeight: 1080,
          quality: 0.85,
        },
      });
      setSelectedAssets(result.assets);
    } catch (e: any) {
      if (e.code !== 'USER_CANCELLED') {
        Alert.alert('Camera Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleLaunchGallery = async () => {
    setLoading(true);
    try {
      const result = await SmartMediaPicker.openGallery({
        mediaType: 'mixed',
        selectionLimit: 3,
      });
      setSelectedAssets(result.assets);
    } catch (e: any) {
      if (e.code !== 'USER_CANCELLED') {
        Alert.alert('Gallery Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleLaunchDocumentPicker = async () => {
    setLoading(true);
    try {
      const result = await SmartMediaPicker.openDocumentPicker({
        selectionLimit: 2,
        mimeTypes: ['application/pdf'],
      });
      setSelectedAssets(result.assets);
    } catch (e: any) {
      if (e.code !== 'USER_CANCELLED') {
        Alert.alert('Document Picker Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleCompressFirstAsset = async () => {
    if (selectedAssets.length === 0) return;
    const asset = selectedAssets[0];
    if (!asset) return;

    setLoading(true);
    try {
      if (asset.type.startsWith('image/')) {
        const compressed = await SmartMediaPicker.compressImage(asset.uri, {
          maxWidth: 600,
          maxHeight: 600,
          quality: 0.6,
        });
        Alert.alert(
          'Image Compressed',
          `Before: ${(asset.size / 1024).toFixed(1)} KB\nAfter: ${(compressed.size / 1024).toFixed(1)} KB\nPath: ${compressed.uri}`
        );
      } else if (asset.type.startsWith('video/')) {
        const compressed = await SmartMediaPicker.compressVideo(asset.uri, {
          quality: 'low',
        });
        Alert.alert(
          'Video Compressed',
          `Before: ${(asset.size / 1024 / 1024).toFixed(1)} MB\nAfter: ${(compressed.size / 1024 / 1024).toFixed(1)} MB\nPath: ${compressed.uri}`
        );
      } else {
        Alert.alert(
          'Unsupported',
          'Compression is supported for images and videos.'
        );
      }
    } catch (e: any) {
      Alert.alert('Compression Error', e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.header}>
        <Text style={styles.title}>Smart Media Picker</Text>
        <Text style={styles.subtitle}>Enterprise Media Core Sandbox</Text>
      </View>

      <ScrollView contentContainerStyle={styles.scrollContent}>
        {/* Permission Indicators */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Platform Permission Sync</Text>
          <View style={styles.row}>
            <Text style={styles.label}>Camera:</Text>
            <TouchableOpacity onPress={() => requestPermission('camera')}>
              <Text
                style={[
                  styles.statusText,
                  styles[cameraPerm.toLowerCase() as keyof typeof styles] ||
                    styles.denied,
                ]}
              >
                {cameraPerm}
              </Text>
            </TouchableOpacity>
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Photo Gallery:</Text>
            <TouchableOpacity onPress={() => requestPermission('photoLibrary')}>
              <Text
                style={[
                  styles.statusText,
                  styles[galleryPerm.toLowerCase() as keyof typeof styles] ||
                    styles.denied,
                ]}
              >
                {galleryPerm}
              </Text>
            </TouchableOpacity>
          </View>
          {Platform.OS === 'ios' && galleryPerm === 'LIMITED' && (
            <TouchableOpacity
              style={styles.settingsButton}
              onPress={() => SmartMediaPicker.presentLimitedLibraryPicker()}
            >
              <Text style={styles.settingsButtonText}>
                Manage Selected Photos
              </Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Action Panel */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>Action Panel</Text>
          <View style={styles.buttonRow}>
            <TouchableOpacity
              style={styles.button}
              onPress={handleLaunchCamera}
            >
              <Text style={styles.buttonText}>Camera</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.button}
              onPress={handleLaunchGallery}
            >
              <Text style={styles.buttonText}>Gallery</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.button}
              onPress={handleLaunchDocumentPicker}
            >
              <Text style={styles.buttonText}>Docs</Text>
            </TouchableOpacity>
          </View>
          {selectedAssets.length > 0 && (
            <TouchableOpacity
              style={styles.compressButton}
              onPress={handleCompressFirstAsset}
            >
              <Text style={styles.compressButtonText}>Compress Selected</Text>
            </TouchableOpacity>
          )}
        </View>

        {/* Loading Indicator */}
        {loading && (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color="#007AFF" />
            <Text style={styles.loadingText}>Processing natively...</Text>
          </View>
        )}

        {/* Previews */}
        <View style={styles.card}>
          <Text style={styles.cardTitle}>
            Selection Queue ({selectedAssets.length})
          </Text>
          {selectedAssets.map((asset, index) => (
            <View key={index} style={styles.assetContainer}>
              {asset.type.startsWith('image/') ? (
                <Image
                  source={{ uri: asset.uri }}
                  style={styles.previewImage}
                />
              ) : asset.thumbnailUri ? (
                <Image
                  source={{ uri: asset.thumbnailUri }}
                  style={styles.previewImage}
                />
              ) : (
                <View style={[styles.previewImage, styles.fallbackPreview]}>
                  <Text style={styles.fallbackText}>FILE</Text>
                </View>
              )}
              <View style={styles.assetInfo}>
                <Text style={styles.assetName} numberOfLines={1}>
                  {asset.name}
                </Text>
                <Text style={styles.assetDetail}>
                  Type: {asset.type} | Size: {(asset.size / 1024).toFixed(1)} KB
                </Text>
                {asset.width && asset.height ? (
                  <Text style={styles.assetDetail}>
                    Resolution: {asset.width}x{asset.height}
                  </Text>
                ) : null}
                {asset.duration ? (
                  <Text style={styles.assetDetail}>
                    Duration: {asset.duration.toFixed(1)}s
                  </Text>
                ) : null}
                {asset.exif ? (
                  <Text style={styles.exifText} numberOfLines={2}>
                    EXIF: {JSON.stringify(asset.exif)}
                  </Text>
                ) : null}
              </View>
            </View>
          ))}
          {selectedAssets.length === 0 && (
            <Text style={styles.emptyText}>No assets selected yet.</Text>
          )}
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#0F0F11',
  },
  header: {
    padding: 20,
    borderBottomWidth: 1,
    borderBottomColor: '#222',
  },
  title: {
    fontSize: 26,
    fontWeight: 'bold',
    color: '#FFF',
    letterSpacing: 0.5,
  },
  subtitle: {
    fontSize: 13,
    color: '#8E8E93',
    marginTop: 4,
  },
  scrollContent: {
    padding: 16,
  },
  card: {
    backgroundColor: '#1C1C1E',
    borderRadius: 16,
    padding: 16,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 5,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#FFF',
    marginBottom: 12,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#2C2C2E',
  },
  label: {
    color: '#E5E5EA',
    fontSize: 15,
  },
  statusText: {
    fontSize: 14,
    fontWeight: 'bold',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
    overflow: 'hidden',
  },
  full: {
    color: '#30D158',
    backgroundColor: 'rgba(48, 209, 88, 0.15)',
  },
  limited: {
    color: '#BF5AF2',
    backgroundColor: 'rgba(191, 90, 242, 0.15)',
  },
  denied: {
    color: '#FF453A',
    backgroundColor: 'rgba(255, 69, 58, 0.15)',
  },
  blocked: {
    color: '#FF9F0A',
    backgroundColor: 'rgba(255, 159, 10, 0.15)',
  },
  settingsButton: {
    marginTop: 12,
    backgroundColor: '#2C2C2E',
    paddingVertical: 10,
    borderRadius: 10,
    alignItems: 'center',
  },
  settingsButtonText: {
    color: '#FFF',
    fontSize: 14,
    fontWeight: '600',
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  button: {
    backgroundColor: '#007AFF',
    flex: 1,
    marginHorizontal: 4,
    paddingVertical: 12,
    borderRadius: 12,
    alignItems: 'center',
  },
  buttonText: {
    color: '#FFF',
    fontSize: 15,
    fontWeight: 'bold',
  },
  compressButton: {
    marginTop: 12,
    backgroundColor: '#34C759',
    paddingVertical: 12,
    borderRadius: 12,
    alignItems: 'center',
  },
  compressButtonText: {
    color: '#FFF',
    fontSize: 15,
    fontWeight: 'bold',
  },
  loadingContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 12,
    backgroundColor: 'rgba(0, 122, 255, 0.05)',
    borderRadius: 12,
    marginBottom: 16,
  },
  loadingText: {
    color: '#007AFF',
    marginLeft: 8,
    fontWeight: '600',
  },
  assetContainer: {
    flexDirection: 'row',
    marginBottom: 16,
    backgroundColor: '#2C2C2E',
    borderRadius: 12,
    padding: 10,
  },
  previewImage: {
    width: 70,
    height: 70,
    borderRadius: 8,
    backgroundColor: '#3A3A3C',
  },
  fallbackPreview: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  fallbackText: {
    color: '#8E8E93',
    fontWeight: 'bold',
    fontSize: 12,
  },
  assetInfo: {
    flex: 1,
    marginLeft: 12,
    justifyContent: 'center',
  },
  assetName: {
    color: '#FFF',
    fontWeight: '600',
    fontSize: 14,
    marginBottom: 4,
  },
  assetDetail: {
    color: '#AEAEB2',
    fontSize: 12,
    marginTop: 2,
  },
  exifText: {
    color: '#BF5AF2',
    fontSize: 11,
    marginTop: 4,
  },
  emptyText: {
    color: '#8E8E93',
    textAlign: 'center',
    paddingVertical: 20,
    fontSize: 14,
  },
});
