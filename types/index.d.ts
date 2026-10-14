export type TapjackMode = 'block' | 'detect' | 'off';

export interface TapjackOptions {
    /** 'block' swallows obscured touches; 'detect' only reports them. Defaults to 'block'. */
    mode?: 'block' | 'detect';
    /** Also block touches when another part of the window is covered (Android 10+). */
    strict?: boolean;
    /** Hide third-party overlay windows while the app is visible (Android 12+). */
    hideOverlays?: boolean;
}

export interface TapjackStatus {
    /** false on platforms without overlay protection (iOS). Other fields may be absent then. */
    supported: boolean;
    platform: 'android' | 'ios';
    sdkInt?: number;
    mode?: TapjackMode;
    strict?: boolean;
    hideOverlays?: boolean;
    filterTouchesActive?: boolean;
    hideOverlaysActive?: boolean;
    hideOverlaysSupported?: boolean;
    guardActive?: boolean;
}

export interface ObscuredTouchEvent {
    /** true when only another part of the window was covered, not the touch point. */
    partial: boolean;
    /** true when the touch was swallowed. */
    blocked: boolean;
    /** Epoch milliseconds. */
    timestamp: number;
}

export interface TapjackProtection {
    enable(options?: TapjackOptions): Promise<TapjackStatus>;
    disable(): Promise<TapjackStatus>;
    getStatus(): Promise<TapjackStatus>;
    /** Returns an unsubscribe function. */
    onObscuredTouch(listener: (event: ObscuredTouchEvent) => void): () => void;
}

declare global {
    interface CordovaPlugins {
        tapjackProtection: TapjackProtection;
    }

    interface DocumentEventMap {
        tapjackdetected: Event & ObscuredTouchEvent;
    }
}
