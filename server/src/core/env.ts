import { existsSync } from 'node:fs';
import { isAbsolute, resolve } from 'node:path';
import dotenv from 'dotenv';
import type { LogFormat, LogLevel } from './logger';

export interface EnvConfig {
    nodeEnv: string;
    port: number;
    firebaseDbUrl: string;
    firebaseSecret: string;
    firebaseServiceAccountPath: string | null;
    firebaseServiceAccountJson: string | null;
    wsPingIntervalMs: number;
    staleThresholdMs: number;
    staleCheckMs: number;
    statsIntervalMs: number;
    channelGraceMs: number;
    serverVolumeGain: number;
    logLevel: LogLevel;
    logFormat: LogFormat;
    rateLimitEnabled: boolean;
    rateWindowMs: number;
    maxRequestsPerWindow: number;
    shutdownTimeoutMs: number;
    udpPort: number;
    audioCastMaxSizeMb: number;
    audioCastTtlHours: number;
    audioCastStoreDir: string;
    /**
     * Explicit public host:port the Android device uses to download audio files.
     * Set to your PC's LAN IP when running locally, e.g. "192.168.1.42:7000".
     * Leave empty to auto-detect from OS network interfaces.
     */
    publicHost: string;
}

function parsePositiveInt(rawValue: string | undefined, fallbackValue: number, variableName: string): number {
    const parsed = Number.parseInt(rawValue ?? `${fallbackValue}`, 10);
    if (!Number.isFinite(parsed) || parsed <= 0) {
        throw new Error(`Invalid ${variableName}: expected a positive integer, received "${rawValue}"`);
    }
    return parsed;
}

function parsePositiveNumber(rawValue: string | undefined, fallbackValue: number, variableName: string): number {
    const parsed = Number.parseFloat(rawValue ?? `${fallbackValue}`);
    if (!Number.isFinite(parsed) || parsed <= 0) {
        throw new Error(`Invalid ${variableName}: expected a positive number, received "${rawValue}"`);
    }
    return parsed;
}

function parseBoolean(rawValue: string | undefined, fallbackValue: boolean): boolean {
    if (rawValue === undefined) {
        return fallbackValue;
    }
    return /^(1|true|yes|on)$/i.test(rawValue.trim());
}

function parseLogLevel(rawValue: string | undefined): LogLevel {
    const normalized = rawValue?.trim().toUpperCase() ?? 'INFO';
    if (normalized === 'DEBUG' || normalized === 'INFO' || normalized === 'WARN' || normalized === 'ERROR') {
        return normalized;
    }
    throw new Error(`Invalid LOG_LEVEL: "${rawValue}"`);
}

function parseLogFormat(rawValue: string | undefined): LogFormat {
    const normalized = rawValue?.trim().toLowerCase() ?? 'pretty';
    if (normalized === 'pretty' || normalized === 'json') {
        return normalized;
    }
    throw new Error(`Invalid LOG_FORMAT: "${rawValue}"`);
}

function resolveCandidatePath(projectRoot: string, candidate: string | undefined): string | null {
    if (!candidate) {
        return null;
    }
    return isAbsolute(candidate) ? candidate : resolve(projectRoot, candidate);
}

function resolveServiceAccountPath(projectRoot: string): string | null {
    const explicitPath = resolveCandidatePath(projectRoot, process.env.FIREBASE_SERVICE_ACCOUNT_PATH);
    const candidates = [
        explicitPath,
        resolve(projectRoot, '.local', 'serviceAccount.json'),
        resolve(projectRoot, 'config', 'serviceAccount.json'),
    ].filter((value): value is string => Boolean(value));

    for (const candidate of candidates) {
        if (existsSync(candidate)) {
            return candidate;
        }
    }

    return explicitPath;
}

export function loadEnvConfig(projectRoot: string): EnvConfig {
    dotenv.config({ path: resolve(projectRoot, '.env') });

    return {
        nodeEnv: process.env.NODE_ENV?.trim() || 'development',
        port: parsePositiveInt(process.env.PORT, 7000, 'PORT'),
        firebaseDbUrl: process.env.FIREBASE_DB_URL?.trim()
            || 'https://auracast-df815-default-rtdb.asia-southeast1.firebasedatabase.app',
        firebaseSecret: process.env.FIREBASE_DB_SECRET?.trim() || '',
        firebaseServiceAccountPath: resolveServiceAccountPath(projectRoot),
        firebaseServiceAccountJson: process.env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim() || null,
        wsPingIntervalMs: parsePositiveInt(process.env.WS_PING_INTERVAL_MS, 70000, 'WS_PING_INTERVAL_MS'),
        staleThresholdMs: parsePositiveInt(process.env.STALE_THRESHOLD_MS, 90000, 'STALE_THRESHOLD_MS'),
        staleCheckMs: parsePositiveInt(process.env.STALE_CHECK_MS, 15000, 'STALE_CHECK_MS'),
        statsIntervalMs: parsePositiveInt(process.env.STATS_INTERVAL_MS, 2000, 'STATS_INTERVAL_MS'),
        channelGraceMs: parsePositiveInt(process.env.CHANNEL_GRACE_MS, 700000, 'CHANNEL_GRACE_MS'),
        serverVolumeGain: parsePositiveNumber(process.env.SERVER_VOLUME_GAIN, 3.0, 'SERVER_VOLUME_GAIN'),
        logLevel: parseLogLevel(process.env.LOG_LEVEL),
        logFormat: parseLogFormat(process.env.LOG_FORMAT),
        rateLimitEnabled: parseBoolean(process.env.RATE_LIMIT_ENABLED, false),
        rateWindowMs: parsePositiveInt(process.env.RATE_WINDOW_MS, 10000, 'RATE_WINDOW_MS'),
        maxRequestsPerWindow: parsePositiveInt(process.env.MAX_REQUESTS_PER_WINDOW, 120, 'MAX_REQUESTS_PER_WINDOW'),
        shutdownTimeoutMs: parsePositiveInt(process.env.SHUTDOWN_TIMEOUT_MS, 10000, 'SHUTDOWN_TIMEOUT_MS'),
        udpPort: parsePositiveInt(process.env.UDP_PORT, 4001, 'UDP_PORT'),
        audioCastMaxSizeMb: parsePositiveInt(process.env.AUDIO_CAST_MAX_SIZE_MB, 50, 'AUDIO_CAST_MAX_SIZE_MB'),
        audioCastTtlHours: parsePositiveInt(process.env.AUDIO_CAST_TTL_HOURS, 2, 'AUDIO_CAST_TTL_HOURS'),
        audioCastStoreDir: process.env.AUDIO_CAST_STORE_DIR?.trim() || '.local/audio',
        publicHost: process.env.PUBLIC_HOST?.trim() || '',
    };
}
