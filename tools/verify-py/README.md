# verify-py

Verificador independente em Python. O dado e a chain-v1 usam só a stdlib. A assinatura Ed25519 da chain-v2 (quem rolou) usa a pyca/cryptography, fixada no `pyproject.toml`: `python3 -m pip install cryptography==50.0.2`. O pytest serve apenas para os testes. Testes: `python3 -m pytest tools/verify-py`.
