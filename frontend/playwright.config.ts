import {defineConfig} from '@playwright/test';

export default defineConfig({
    testDir: './browser-tests', timeout: 180000, expect: {timeout: 12000}, workers: 1,
    reporter: [['list'], ['html', {open: 'never'}]], use: {
        actionTimeout: 12000,
        baseURL: process.env.FRONTEND_URL || 'http://127.0.0.1:3000',
        viewport: {width: 1440, height: 1000}, trace: 'retain-on-failure', screenshot: 'only-on-failure',
        launchOptions: process.env.PLAYWRIGHT_EXECUTABLE_PATH ? {executablePath: process.env.PLAYWRIGHT_EXECUTABLE_PATH} : undefined
    }
});
