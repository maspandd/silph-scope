#import "CDVTapjackProtection.h"

@implementation CDVTapjackProtection

- (void)sendUnsupportedStatus:(CDVInvokedUrlCommand*)command
{
    NSDictionary* status = @{ @"supported": @NO, @"platform": @"ios" };
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:status];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

- (void)enable:(CDVInvokedUrlCommand*)command
{
    [self sendUnsupportedStatus:command];
}

- (void)disable:(CDVInvokedUrlCommand*)command
{
    [self sendUnsupportedStatus:command];
}

- (void)getStatus:(CDVInvokedUrlCommand*)command
{
    [self sendUnsupportedStatus:command];
}

- (void)subscribe:(CDVInvokedUrlCommand*)command
{
    // No events will ever be sent; keep the callback open so JS does not log an error.
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_NO_RESULT];
    [result setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:result callbackId:command.callbackId];
}

@end
