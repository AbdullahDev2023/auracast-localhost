import type { ChannelRegistry } from './channel-registry';
import type { FirebaseCommandService } from './firebase-command-service';
import type { MetricsService } from './metrics-service';
import type { RateLimitService } from './rate-limit-service';
import type { AudioStoreService } from './audio-store-service';

/** Public interface exposed by UdpRelayModule so other modules (relay-module) can register sessions. */
export interface UdpRelayService {
    /**
     * Register a UDP session token → channel mapping.
     * Called when the Android phone sends `{"type":"udpReady","udpToken":"<token>"}` over WS.
     * @param token    32-char hex session token from the phone
     * @param channelId stream channel ID (the `?id=` query param)
     */
    registerSession(token: string, channelId: string): void;
}

export interface AuraCastServices {
    channels: ChannelRegistry;
    firebase: FirebaseCommandService;
    metrics: MetricsService;
    rateLimit: RateLimitService;
    udpRelay: UdpRelayService;
    audioStore: AudioStoreService;
}

export interface AuraCastServiceOverrides {
    firebase?: FirebaseCommandService;
}
