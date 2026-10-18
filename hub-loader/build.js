const fs = require('node:fs');
const path = require('node:path');

const outputDirectory = path.join(__dirname, 'dist');
fs.mkdirSync(outputDirectory, { recursive: true });
fs.copyFileSync(path.join(__dirname, 'index.html'), path.join(outputDirectory, 'index.html'));
