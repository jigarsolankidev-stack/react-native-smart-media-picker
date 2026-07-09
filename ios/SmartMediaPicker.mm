#import "SmartMediaPicker.h"
#import <SmartMediaPickerSpec/SmartMediaPickerSpec.h>
#if __has_include(<SmartMediaPicker/SmartMediaPicker-Swift.h>)
#import <SmartMediaPicker/SmartMediaPicker-Swift.h>
#else
#import "SmartMediaPicker-Swift.h"
#endif

@interface SmartMediaPicker () <NativeSmartMediaPickerSpec>
@end

@implementation SmartMediaPicker {
    SmartMediaPickerImpl *_impl;
}

- (instancetype)init
{
    if (self = [super init]) {
        _impl = [[SmartMediaPickerImpl alloc] init];
    }
    return self;
}

- (void)checkPermission:(NSString *)type
                resolve:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject
{
    [_impl checkPermission:type resolve:resolve reject:reject];
}

- (void)requestPermission:(NSString *)type
                  resolve:(RCTPromiseResolveBlock)resolve
                   reject:(RCTPromiseRejectBlock)reject
{
    [_impl requestPermission:type resolve:resolve reject:reject];
}

- (void)openSettings:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject
{
    [_impl openSettings:resolve reject:reject];
}

- (void)presentLimitedLibraryPicker:(RCTPromiseResolveBlock)resolve
                             reject:(RCTPromiseRejectBlock)reject
{
    [_impl presentLimitedLibraryPicker:resolve reject:reject];
}

- (void)openCamera:(NSDictionary *)options
           resolve:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject
{
    [_impl openCamera:options resolve:resolve reject:reject];
}

- (void)openGallery:(NSDictionary *)options
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject
{
    [_impl openGallery:options resolve:resolve reject:reject];
}

- (void)openAudioPicker:(NSDictionary *)options
                resolve:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject
{
    [_impl openAudioPicker:options resolve:resolve reject:reject];
}

- (void)openDocumentPicker:(NSDictionary *)options
                   resolve:(RCTPromiseResolveBlock)resolve
                    reject:(RCTPromiseRejectBlock)reject
{
    [_impl openDocumentPicker:options resolve:resolve reject:reject];
}

- (void)compressImage:(NSString *)uri
              options:(NSDictionary *)options
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject
{
    [_impl compressImage:uri options:options resolve:resolve reject:reject];
}

- (void)compressVideo:(NSString *)uri
              options:(NSDictionary *)options
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject
{
    [_impl compressVideo:uri options:options resolve:resolve reject:reject];
}

- (void)getVideoThumbnail:(NSString *)uri
                  options:(NSDictionary *)options
                  resolve:(RCTPromiseResolveBlock)resolve
                   reject:(RCTPromiseRejectBlock)reject
{
    [_impl getVideoThumbnail:uri options:options resolve:resolve reject:reject];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeSmartMediaPickerSpecJSI>(params);
}

+ (NSString *)moduleName
{
    return @"SmartMediaPicker";
}

@end
