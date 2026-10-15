# web/fonts

Fontes servidas pelo próprio site (o CSP só libera `'self'`).

## bricolage-grotesque.woff2

- Origem: [Bricolage Grotesque](https://github.com/ateliertriay/bricolage), via [google/fonts](https://github.com/google/fonts/tree/6ce172f74aa355ea43eb964fa4a91570a4d3064d/ofl/bricolagegrotesque), commit `6ce172f74aa355ea43eb964fa4a91570a4d3064d`. Licença SIL OFL 1.1 (`OFL.txt`).
- TTF de origem: `BricolageGrotesque[opsz,wdth,wght].ttf`, SHA-256 `413e7357809ddd12fd80a96a8a396de0e401638d4acd3cb3e37532f0472ac682`.
- Recorte: largura fixa em 100, peso 400 a 800 (eixo `opsz` mantido), só Latin.
- SHA-256 do woff2: `b36214e2d9ca37fb64ccc10d4df84ea6fbbe246ab284310430571be10e331831`.

Reproduzir (fonttools 4.60.1 e brotli 1.1.0; o `SOURCE_DATE_EPOCH` deixa o arquivo idêntico):

```bash
SOURCE_DATE_EPOCH=0 fonttools varLib.instancer 'BricolageGrotesque[opsz,wdth,wght].ttf' wdth=100 wght=400:800 -o inst.ttf
SOURCE_DATE_EPOCH=0 pyftsubset inst.ttf \
  --unicodes="U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+2000-206F,U+2074,U+20AC,U+2122,U+2190-2199,U+2212,U+2215,U+2713,U+2717" \
  --layout-features='*' --flavor=woff2 --output-file=bricolage-grotesque.woff2
```
