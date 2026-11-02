import { build } from 'esbuild';
import { copyFile } from 'node:fs/promises';
await build({
  entryPoints: ['src/app.js', 'src/qa.js', 'src/h3-app.js', 'src/android-helios.js'],
  outdir: 'dist', bundle: true, format: 'esm', platform: 'browser',
  target: ['chrome110', 'safari16'], minify: false, sourcemap: false,
});
await copyFile('dist/android-helios.js', '../android/app/src/main/assets/helios/android-helios.js');
