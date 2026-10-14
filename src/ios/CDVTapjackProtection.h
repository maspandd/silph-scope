#import <Cordova/CDVPlugin.h>

// iOS has no third-party overlay windows, so every call reports supported: false.
@interface CDVTapjackProtection : CDVPlugin

- (void)enable:(CDVInvokedUrlCommand*)command;
- (void)disable:(CDVInvokedUrlCommand*)command;
- (void)getStatus:(CDVInvokedUrlCommand*)command;
- (void)subscribe:(CDVInvokedUrlCommand*)command;

@end
