import { TurboModuleRegistry, type TurboModule } from 'react-native';

export interface Spec extends TurboModule {
  checkPermission(type: string): Promise<string>;
  requestPermission(type: string): Promise<string>;
  openSettings(): Promise<boolean>;
  presentLimitedLibraryPicker(): Promise<void>;
  openCamera(options: Object): Promise<Object>;
  openGallery(options: Object): Promise<Object>;
  openAudioPicker(options: Object): Promise<Object>;
  openDocumentPicker(options: Object): Promise<Object>;
  compressImage(uri: string, options: Object): Promise<Object>;
  compressVideo(uri: string, options: Object): Promise<Object>;
  getVideoThumbnail(uri: string, options: Object): Promise<Object>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('SmartMediaPicker');
