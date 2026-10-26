import path from 'node:path';
import fs from 'node:fs/promises';
import {bundle} from '@remotion/bundler';
import {openBrowser, renderStill, selectComposition} from '@remotion/renderer';

const serveUrl = await bundle({entryPoint: path.resolve('src/index.ts')});
const browser = await openBrowser('chrome');
try {
  const composition = await selectComposition({serveUrl, id: 'TroveVertical', puppeteerInstance: browser});
  await fs.mkdir('qa', {recursive:true});
  for (const frame of [90, 260, 495, 755, 990, 1260, 1337]) {
    await renderStill({serveUrl, composition, puppeteerInstance:browser, frame, output:path.resolve(`qa/frame-${frame}.png`), imageFormat:'png'});
    console.log(`Checked frame ${frame}`);
  }
} finally {
  await browser.close({silent:true});
}
