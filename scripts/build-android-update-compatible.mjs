import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const server=process.env.MASCOT3_SERVER_URL;
if (!server || new URL(server).protocol!=='https:' || new URL(server).username || new URL(server).password) {
    throw Error('Set MASCOT3_SERVER_URL to the existing HTTPS team server.');
}
if (!existsSync(join(homedir(),'.android/debug.keystore'))) throw Error('Existing team signing identity is missing.');
const env={...process.env,ORG_GRADLE_PROJECT_MASCOT3_SERVER_URL:server};
if (process.env.MASCOT3_CLIENT_TOKEN) env.ORG_GRADLE_PROJECT_MASCOT3_CLIENT_TOKEN=process.env.MASCOT3_CLIENT_TOKEN;
if (process.env.MASCOT3_TEST_JAVA_HOME) env.ORG_GRADLE_PROJECT_MASCOT3_TEST_JAVA_HOME=process.env.MASCOT3_TEST_JAVA_HOME;
console.log('Building update-compatible APK, lint and offline unit tests with the existing team certificate.');
if (!process.env.MASCOT3_CLIENT_TOKEN) console.log('Remote preparation is unavailable without the server client token; bundled physics and direct generation remain available.');
const child=spawn(join(root,'android/gradlew'),[':app:assembleUpdateCompatible',':app:lintUpdateCompatible',':app:testUpdateCompatibleUnitTest','-Pkotlin.compiler.execution.strategy=in-process','--max-workers=2','--console=plain'],{cwd:join(root,'android'),env,stdio:'inherit'});
child.on('error',error=>{console.error(error.message);process.exitCode=1;});
child.on('exit',code=>{process.exitCode=code??1;});
