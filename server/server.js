'use strict';

const { existsSync } = require('node:fs');
const { join } = require('node:path');

const distEntry = join(__dirname, 'dist', 'main.js');

if (!existsSync(distEntry)) {
    console.error('AuraCast server is not built yet.');
    console.error('Run `npm run build` for production or `npm run start:local` for local development.');
    process.exit(1);
}

require(distEntry);
