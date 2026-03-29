import { existsSync, readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { cert, deleteApp, initializeApp, type App, type ServiceAccount } from 'firebase-admin/app';
import { getDatabase, type Database } from 'firebase-admin/database';
import type { EnvConfig } from '../core/env';
import type { ScopedLogger } from '../core/logger';

interface ControlPayload {
    commandId: string;
    command: string;
    url: string;
    ts: number;
    source: 'firebase' | 'wake';
    processed: boolean;
    processedAt: null | number;
}

export interface FirebaseHealthSnapshot {
    adminConfigured: boolean;
    adminReady: boolean;
    restFallbackConfigured: boolean;
    serviceAccountPath: string | null;
}

export class FirebaseCommandService {
    private adminApp: App | null = null;
    private adminDatabase: Database | null = null;

    constructor(
        private readonly config: EnvConfig,
        private readonly logger: ScopedLogger,
    ) {
        this.bootstrapAdminSdk();
    }

    get adminReady(): boolean {
        return this.adminDatabase !== null;
    }

    getHealthSnapshot(): FirebaseHealthSnapshot {
        return {
            adminConfigured: Boolean(this.config.firebaseServiceAccountJson || this.config.firebaseServiceAccountPath),
            adminReady: this.adminReady,
            restFallbackConfigured: Boolean(this.config.firebaseSecret),
            serviceAccountPath: this.config.firebaseServiceAccountPath,
        };
    }

    async queueFallbackCommand(streamId: string, commandId: string, action: string, url = ''): Promise<boolean> {
        if (!this.config.firebaseSecret) {
            return false;
        }

        const endpoint = `${this.config.firebaseDbUrl}/users/${streamId}/control.json?auth=${this.config.firebaseSecret}`;
        const payload: ControlPayload = {
            commandId,
            command: action,
            url,
            ts: Date.now(),
            source: 'firebase',
            processed: false,
            processedAt: null,
        };

        try {
            const response = await fetch(endpoint, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });

            if (!response.ok) {
                this.logger.error('Firebase REST write failed', {
                    status: response.status,
                    streamId: streamId.slice(0, 8),
                    action,
                });
                return false;
            }

            this.logger.info('Firebase fallback queued', {
                streamId: streamId.slice(0, 8),
                commandId: commandId.slice(0, 8),
                action,
            });
            return true;
        } catch (error) {
            this.logger.error('Firebase REST write error', {
                action,
                streamId: streamId.slice(0, 8),
                error: error instanceof Error ? error.message : String(error),
            });
            return false;
        }
    }

    async sendWake(streamId: string): Promise<{ commandId: string }> {
        if (!this.adminDatabase) {
            throw new Error('Firebase Admin not configured');
        }

        const commandId = randomUUID();
        const payload: ControlPayload = {
            commandId,
            command: 'reconnect',
            url: '',
            ts: Date.now(),
            source: 'wake',
            processed: false,
            processedAt: null,
        };

        await this.adminDatabase.ref(`/users/${streamId}/control`).set(payload);
        return { commandId };
    }

    async dispose(): Promise<void> {
        if (!this.adminApp) {
            return;
        }
        await deleteApp(this.adminApp);
        this.adminApp = null;
        this.adminDatabase = null;
    }

    private bootstrapAdminSdk(): void {
        try {
            const serviceAccount = this.loadServiceAccount();
            if (!serviceAccount) {
                this.logger.warn('Firebase Admin not configured; /wake will return 503');
                return;
            }

            this.adminApp = initializeApp({
                credential: cert(serviceAccount),
                databaseURL: this.config.firebaseDbUrl,
            }, `auracast-${process.pid}-${Date.now()}`);
            this.adminDatabase = getDatabase(this.adminApp);
            this.logger.info('Firebase Admin initialized');
        } catch (error) {
            this.logger.warn('Firebase Admin unavailable', {
                error: error instanceof Error ? error.message : String(error),
            });
            this.adminApp = null;
            this.adminDatabase = null;
        }
    }

    private loadServiceAccount(): ServiceAccount | null {
        if (this.config.firebaseServiceAccountJson) {
            return JSON.parse(this.config.firebaseServiceAccountJson) as ServiceAccount;
        }

        const serviceAccountPath = this.config.firebaseServiceAccountPath;
        if (!serviceAccountPath || !existsSync(serviceAccountPath)) {
            return null;
        }

        return JSON.parse(readFileSync(serviceAccountPath, 'utf-8')) as ServiceAccount;
    }
}
