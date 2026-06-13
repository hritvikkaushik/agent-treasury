# x402.rs facilitator — Avalanche Fuji

Self-hosted [x402.rs](https://github.com/x402-rs/x402-rs) facilitator that verifies and settles x402
payments on Fuji. The `config.json` here is pre-set for `eip155:43113` (see `SPIKE-FINDINGS.md` §2).

## Run (Docker, Linux)
The facilitator's signer wallet (`FACILITATOR_PRIVATE_KEY`) **must hold test AVAX** — it submits the
settlement tx and pays gas. The `config.json` interpolates `$FACILITATOR_PRIVATE_KEY` from the env.

Run detached, passing the repo-root `.env` via `--env-file` (do NOT `source` it — see `.env.example`).
From the repo root:
```bash
docker run -d --name x402-facilitator --restart unless-stopped \
  -v "$(pwd)/infra/facilitator/config.json:/app/config.json:ro" \
  --env-file .env \
  -p 8080:8080 \
  ghcr.io/x402-rs/x402-facilitator

docker logs -f x402-facilitator     # follow logs
docker rm -f x402-facilitator       # stop + remove
```
(`docker compose` plugin isn't installed on the runtime box, so we use plain `docker run`. The
`docker-compose.yml` here is kept as an optional convenience if you install the plugin.)

## Verify it's up and on Fuji
```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/supported    # expect a scheme entry for eip155:43113
```

## API (used by the smoke test and later the treasury)
- `POST /verify` — validate a payment payload (no chain write).
- `POST /settle` — submit `transferWithAuthorization` on-chain; returns `{success, transaction, ...}`.
- `GET /supported` — list scheme × chain pairs.

Request envelope for `/verify` and `/settle`:
```json
{ "x402Version": 1, "paymentPayload": { ... }, "paymentRequirements": { ... } }
```
Full payload/requirements schemas: `SPIKE-FINDINGS.md` §3.

## Notes
- A hosted instance exists at `https://facilitator.x402.rs/` — if `GET /supported` there lists
  `eip155:43113`, you can point `FACILITATOR_URL` at it and skip self-hosting for early testing.
- If `/verify` rejects a valid-looking payload, check the `network` string (`avalanche-fuji` vs
  `eip155:43113`) and the envelope field names against `GET /verify` (which returns its schema).
- No Docker locally on macOS in this repo's origin environment — run this on the Linux box.
