import { createAuraCastServer } from './server/create-server';

async function main(): Promise<void> {
    const server = createAuraCastServer();
    const logger = server.context.loggerFactory.forModule('Bootstrap');
    let shuttingDown = false;

    const shutdown = async (signal: NodeJS.Signals) => {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        logger.info('Shutdown requested', { signal });

        const timeout = setTimeout(() => {
            logger.error('Graceful shutdown timed out', {
                timeoutMs: server.context.config.shutdownTimeoutMs,
            });
            process.exit(1);
        }, server.context.config.shutdownTimeoutMs);

        try {
            await server.stop();
            clearTimeout(timeout);
            logger.info('Server stopped cleanly');
            process.exit(0);
        } catch (error) {
            clearTimeout(timeout);
            logger.error('Graceful shutdown failed', {
                error: error instanceof Error ? error.message : String(error),
            });
            process.exit(1);
        }
    };

    process.on('SIGINT', () => void shutdown('SIGINT'));
    process.on('SIGTERM', () => void shutdown('SIGTERM'));

    const port = await server.start();
    logger.info('Listening for connections', { port });
}

main().catch(error => {
    console.error(error);
    process.exit(1);
});
