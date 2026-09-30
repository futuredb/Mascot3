import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { spawn } from 'node:child_process';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const android = join(root, 'android');
const signingDirectory = join(homedir(), 'Library/Application Support/Mascots/signing');
const store = process.env.MASCOT3_RELEASE_STORE_FILE ?? join(signingDirectory, 'mascots-release.p12');
const passwordFile = join(signingDirectory, 'release-password.bin');
const password = process.env.MASCOT3_RELEASE_STORE_PASSWORD
    ?? (existsSync(passwordFile) ? readFileSync(passwordFile).toString('base64') : null);
if (!existsSync(store) || !password) throw new Error('Provide a production keystore and signing environment variables.');

// Reuse the team's existing local connection settings without copying secrets into source files.
// Clean build hosts must supply these values explicitly through the environment.
const previousFile = join(android, 'app/build/generated/source/buildConfig/debug/com/generativemascot/app/BuildConfig.java');
const previous = existsSync(previousFile) ? readFileSync(previousFile, 'utf8') : '';
function previousValue(field) {
    const match = previous.match(new RegExp(`${field} = (".*");`));
    return match ? JSON.parse(match[1]) : undefined;
}
const server = process.env.MASCOT3_SERVER_URL ?? previousValue('API_BASE_URL');
const clientToken = process.env.MASCOT3_CLIENT_TOKEN ?? previousValue('MASCOT3_CLIENT_TOKEN');
const key = process.env.MASCOT3_OPENROUTER_API_KEY ?? process.env.OPENROUTER_API_KEY
    ?? previousValue('OPENROUTER_API_KEY');
if (!server || !clientToken) throw new Error('Provide the production server URL and client token.');
const url = new URL(server);
if (url.protocol !== 'https:' || ['localhost', '127.0.0.1', '10.0.2.2'].includes(url.hostname)
    || url.username || url.password) throw new Error('Production server must use HTTPS without URL credentials.');

const args = [':app:assembleRelease', ':app:lintRelease', ':app:testReleaseUnitTest', '--console=plain',
    `-PMASCOT3_SERVER_URL=${server}`, `-PMASCOT3_CLIENT_TOKEN=${clientToken}`];
if (key !== undefined) args.push(`-PMASCOT3_OPENROUTER_API_KEY=${key}`);
if (process.env.MASCOT3_TEST_JAVA_HOME) args.push(`-PMASCOT3_TEST_JAVA_HOME=${process.env.MASCOT3_TEST_JAVA_HOME}`);
console.log('Building signed production APK with lint and offline unit tests. Credentials are not printed.');
const child = spawn(join(android, 'gradlew'), args, {
    cwd: android,
    stdio: 'inherit',
    env: { ...process.env, MASCOT3_RELEASE_STORE_FILE: store,
        MASCOT3_RELEASE_STORE_PASSWORD: password,
        MASCOT3_RELEASE_KEY_ALIAS: process.env.MASCOT3_RELEASE_KEY_ALIAS ?? 'mascots-release',
        MASCOT3_RELEASE_KEY_PASSWORD: process.env.MASCOT3_RELEASE_KEY_PASSWORD ?? password },
});
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
