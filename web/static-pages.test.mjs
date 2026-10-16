// Páginas estáticas: preview de link (Open Graph) e ícones. O crawler do Facebook não roda JS,
// então tudo isto tem de estar no HTML.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const web = new URL('./', import.meta.url);
const read = (name) => readFileSync(new URL(name, web), 'utf8');
const meta = (html, prop) => html.match(new RegExp(`<meta (?:property|name)="${prop}" content="([^"]*)">`))?.[1];

for (const page of ['index.html', 'confianca.html']) {
  test(`${page}: Open Graph com imagem absoluta`, () => {
    const html = read(page);
    assert.ok(meta(html, 'og:title'));
    assert.ok(meta(html, 'og:description'));
    assert.equal(meta(html, 'og:image'), 'https://dice.nimbos.solutions/static/og-default.png');
    assert.equal(meta(html, 'og:image:width'), '1200');
    assert.equal(meta(html, 'og:image:height'), '630');
    assert.equal(meta(html, 'twitter:card'), 'summary_large_image');
  });

  test(`${page}: favicon e ícone do iOS`, () => {
    const html = read(page);
    assert.match(html, /<link rel="icon" href="\/favicon\.ico" sizes="32x32">/);
    assert.match(html, /<link rel="icon" href="\/favicon\.svg" type="image\/svg\+xml">/);
    assert.match(html, /<link rel="apple-touch-icon" href="\/apple-touch-icon\.png">/);
    assert.doesNotMatch(html, /href="data:/);
  });
}

test('index.html sem og:url nem canonical: o convite /?mesa= não pode virar link da home', () => {
  const html = read('index.html');
  assert.equal(meta(html, 'og:url'), undefined);
  assert.doesNotMatch(html, /rel="canonical"/);
});

test('favicon.svg tem o namespace do SVG', () => {
  assert.match(read('favicon.svg'), /^<svg xmlns="http:\/\/www\.w3\.org\/2000\/svg"/);
});

test('favicon.ico é um ICO com PNGs de 16, 32 e 48', () => {
  const ico = readFileSync(new URL('favicon.ico', web));
  assert.deepEqual([...ico.subarray(0, 4)], [0, 0, 1, 0]);
  const count = ico.readUInt16LE(4);
  const sizes = [];
  for (let i = 0; i < count; i++) {
    const entry = 6 + 16 * i;
    sizes.push(ico[entry]);
    const offset = ico.readUInt32LE(entry + 12);
    assert.deepEqual([...ico.subarray(offset, offset + 4)], [0x89, 0x50, 0x4e, 0x47]);
  }
  assert.deepEqual(sizes, [16, 32, 48]);
});
