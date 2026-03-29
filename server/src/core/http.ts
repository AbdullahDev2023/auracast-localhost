import { createReadStream } from 'node:fs';
import { basename, extname, resolve } from 'node:path';
import type { ServerResponse } from 'node:http';

const CONTENT_TYPES: Record<string, string> = {
    '.html': 'text/html; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.txt': 'text/plain; charset=utf-8',
};

function getContentType(filePath: string): string {
    return CONTENT_TYPES[extname(filePath)] ?? 'application/octet-stream';
}

export function applyCors(response: ServerResponse): void {
    response.setHeader('Access-Control-Allow-Origin', '*');
    response.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    response.setHeader('Access-Control-Allow-Headers', 'Content-Type,X-Request-Id');
}

export function sendJson(response: ServerResponse, statusCode: number, payload: unknown): void {
    response.writeHead(statusCode, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify(payload));
}

export function sendText(response: ServerResponse, statusCode: number, payload: string): void {
    response.writeHead(statusCode, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end(payload);
}

export function sendFile(response: ServerResponse, publicDir: string, fileName: string): void {
    const safePath = resolve(publicDir, basename(fileName));
    response.writeHead(200, { 'Content-Type': getContentType(safePath) });
    createReadStream(safePath)
        .on('error', () => {
            if (!response.writableEnded) {
                sendText(response, 404, 'not found');
            }
        })
        .pipe(response);
}
