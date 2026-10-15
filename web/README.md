# web

Site estático e verificador em JavaScript, que busca o round direto no drand. Testes: `node --test "web/**/*.test.mjs"` (o Node 22 não aceita diretório como argumento).

| arquivo | papel |
|---|---|
| `index.html`, `app.js` | tela de rolagem: escolha do dado, rolagem animada, mesa e histórico |
| `confianca.html`, `confianca.js` | "Por que confiar?": explicação e conferência de qualquer rolagem, com o passo a passo da conta |
| `roll.js` | página `/r/{rollId}` (o HTML vem do template Qute em `dice-app`) |
| `ui.js` | dados em SVG, animações, diálogos e checklist (sem regra de negócio, sem innerHTML) |
| `api.js` | chamadas à API e verificação no navegador (o drand-client só carrega quando precisa) |
| `page-logic.js` | lógica pura, testada no Node |
| `verifier/` | verificadores do dice-v1 e da chain-v1 (fonte da verdade no navegador) |
| `fonts/`, `vendor/` | fonte e drand-client vendorizados, com origem e SHA-256 |
