export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR';
export type LogFormat = 'pretty' | 'json';

const LEVEL_PRIORITY: Record<LogLevel, number> = {
    DEBUG: 10,
    INFO: 20,
    WARN: 30,
    ERROR: 40,
};

type LogFields = Record<string, unknown>;

function formatTimestamp(date: Date): string {
    return date.toISOString();
}

function serializePrettyValue(value: unknown): string {
    if (typeof value === 'string') {
        return value.includes(' ') ? `"${value}"` : value;
    }
    if (value instanceof Error) {
        return `${value.name}: ${value.message}`;
    }
    return JSON.stringify(value);
}

function writePrettyLine(level: LogLevel, moduleName: string, message: string, fields: LogFields): void {
    const base = `[${formatTimestamp(new Date())}] [${level.padEnd(5)}] [${moduleName}] ${message}`;
    const fieldText = Object.entries(fields)
        .filter(([, value]) => value !== undefined)
        .map(([key, value]) => `${key}=${serializePrettyValue(value)}`)
        .join(' ');
    const line = fieldText ? `${base} ${fieldText}` : base;
    if (level === 'ERROR') {
        console.error(line);
        return;
    }
    console.log(line);
}

function writeJsonLine(level: LogLevel, moduleName: string, message: string, fields: LogFields): void {
    const payload = {
        timestamp: formatTimestamp(new Date()),
        level,
        module: moduleName,
        message,
        ...fields,
    };
    const line = JSON.stringify(payload);
    if (level === 'ERROR') {
        console.error(line);
        return;
    }
    console.log(line);
}

export class ScopedLogger {
    constructor(
        private readonly moduleName: string,
        private readonly minLevel: LogLevel,
        private readonly format: LogFormat,
        private readonly baseFields: LogFields = {},
    ) {}

    withFields(fields: LogFields): ScopedLogger {
        return new ScopedLogger(
            this.moduleName,
            this.minLevel,
            this.format,
            { ...this.baseFields, ...fields },
        );
    }

    debug(message: string, fields: LogFields = {}): void {
        this.write('DEBUG', message, fields);
    }

    info(message: string, fields: LogFields = {}): void {
        this.write('INFO', message, fields);
    }

    warn(message: string, fields: LogFields = {}): void {
        this.write('WARN', message, fields);
    }

    error(message: string, fields: LogFields = {}): void {
        this.write('ERROR', message, fields);
    }

    private write(level: LogLevel, message: string, fields: LogFields): void {
        if (LEVEL_PRIORITY[level] < LEVEL_PRIORITY[this.minLevel]) {
            return;
        }
        const mergedFields = { ...this.baseFields, ...fields };
        if (this.format === 'json') {
            writeJsonLine(level, this.moduleName, message, mergedFields);
            return;
        }
        writePrettyLine(level, this.moduleName, message, mergedFields);
    }
}

export class LoggerFactory {
    constructor(
        private readonly minLevel: LogLevel,
        private readonly format: LogFormat,
    ) {}

    forModule(moduleName: string): ScopedLogger {
        return new ScopedLogger(moduleName, this.minLevel, this.format);
    }
}
