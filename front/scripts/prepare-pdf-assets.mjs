/** 准备本地 PDF 字体、CMap 与解码器资源；供开发服务和构建使用，不传送文档到外部站点。 */
import { cp, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const source = new URL('../node_modules/pdfjs-dist/', import.meta.url);
const destination = new URL('../public/pdfjs/', import.meta.url);
await mkdir(destination, { recursive: true });
for (const directory of ['cmaps', 'standard_fonts', 'wasm']) {
  await cp(fileURLToPath(new URL(directory, source)), fileURLToPath(new URL(directory, destination)), { recursive: true, force: true });
}
await cp(fileURLToPath(new URL('LICENSE', source)), fileURLToPath(new URL('LICENSE', destination)));
