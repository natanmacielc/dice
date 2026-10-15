// Só o necessário para verificar um beacon já obtido: fetchBeacon com um ChainClient próprio
// chama o verifyBeacon interno do drand-client (ver web/verifier/drand-verify.js).
export { fetchBeacon } from 'drand-client';
