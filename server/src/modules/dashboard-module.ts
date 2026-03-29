import { sendFile } from '../core/http';
import type { ServerModule } from '../core/contracts';

export function createDashboardModule(): ServerModule {
    return {
        name: 'dashboard',
        register(serverContext) {
            return {
                httpRoutes: [
                    {
                        method: 'GET',
                        path: '/',
                        description: 'Serve the dashboard',
                        handler(_request, response) {
                            sendFile(response, serverContext.paths.publicDir, 'index.html');
                        },
                    },
                    {
                        method: 'GET',
                        path: '/index.html',
                        description: 'Serve the dashboard alias',
                        handler(_request, response) {
                            sendFile(response, serverContext.paths.publicDir, 'index.html');
                        },
                    },
                    {
                        method: 'GET',
                        path: '/player',
                        description: 'Serve the browser player',
                        handler(_request, response) {
                            sendFile(response, serverContext.paths.publicDir, 'player.html');
                        },
                    },
                ],
            };
        },
    };
}
