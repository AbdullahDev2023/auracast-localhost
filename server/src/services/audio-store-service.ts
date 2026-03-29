import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';

export interface AudioEntry {
    id: string;
    originalName: string;
    mimeType: string;
    storedPath: string;
    createdAt: number;
}

export class AudioStoreService {
    private readonly entries = new Map<string, AudioEntry>();
    private readonly storeDir: string;

    constructor(storeDir: string) {
        this.storeDir = storeDir;
        if (!existsSync(storeDir)) {
            mkdirSync(storeDir, { recursive: true });
        }
    }

    store(buf: Buffer, originalName: string, mimeType: string): AudioEntry {
        const id = randomUUID();
        const ext = originalName.includes('.') ? originalName.slice(originalName.lastIndexOf('.')) : '';
        const storedPath = resolve(this.storeDir, `${id}${ext}`);
        writeFileSync(storedPath, buf);
        const entry: AudioEntry = { id, originalName, mimeType, storedPath, createdAt: Date.now() };
        this.entries.set(id, entry);
        return entry;
    }

    get(id: string): AudioEntry | undefined {
        return this.entries.get(id);
    }

    delete(id: string): void {
        const entry = this.entries.get(id);
        if (!entry) return;
        this.entries.delete(id);
        try { rmSync(entry.storedPath, { force: true }); } catch { /* already gone */ }
    }

    purgeExpired(ttlMs: number): void {
        const cutoff = Date.now() - ttlMs;
        for (const [id, entry] of this.entries) {
            if (entry.createdAt < cutoff) this.delete(id);
        }
    }

    get size(): number { return this.entries.size; }
}
