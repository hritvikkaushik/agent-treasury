# Deploying Agent Treasury (GCP Compute Engine VM)

The whole stack runs as three Docker containers on one small VM — the same setup as local, just
always-on with a public IP. `deploy/docker-compose.yml` brings up the treasury app + Postgres + the
x402.rs facilitator on a private network; only the app's port (8090) is exposed.

> The "deployed on Avalanche C-Chain" hackathon requirement is already met by the on-chain ERC-8004
> registries + live settlement. This is just to give judges a reachable URL.

## 0. Prerequisites
- Funded **Fuji** wallets (reuse the ones you already have): treasury key (USDC + a little AVAX),
  facilitator key (AVAX for gas).
- ERC-8004 registry addresses (from your deploy): Identity `0x313b59f6…8598`, Reputation `0x0455293B…5A3a`.
- A strong `ADMIN_TOKEN` you generate (e.g. `openssl rand -hex 24`).

## 1. Create the VM + firewall (gcloud; or use the Console)
```bash
gcloud compute instances create agent-treasury \
  --machine-type=e2-small --image-family=ubuntu-2204-lts --image-project=ubuntu-os-cloud \
  --zone=asia-south1-a --tags=treasury
gcloud compute firewall-rules create allow-treasury \
  --allow=tcp:8090 --target-tags=treasury --source-ranges=0.0.0.0/0
```
Note the VM's external IP: `gcloud compute instances describe agent-treasury --zone=asia-south1-a --format='get(networkInterfaces[0].accessConfigs[0].natIP)'`

## 2. Install Docker on the VM
```bash
gcloud compute ssh agent-treasury --zone=asia-south1-a
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
sudo usermod -aG docker "$USER" && exit   # re-SSH so the group applies
```

## 3. Get the code
```bash
gcloud compute ssh agent-treasury --zone=asia-south1-a
git clone https://github.com/hritvikkaushik/agent-treasury.git   # private repo: use a PAT or deploy key
cd agent-treasury
```

## 4. Configure secrets — `deploy/.env` (gitignored)
```bash
cat > deploy/.env <<'EOF'
TREASURY_PRIVATE_KEY=0x<your funded treasury key>
FACILITATOR_PRIVATE_KEY=0x<your funded facilitator key>
ADMIN_TOKEN=<the strong token you generated>
IDENTITY_REGISTRY_ADDRESS=0x313b59f63323C62DeAF19e1Ad41717b5DbaA8598
REPUTATION_REGISTRY_ADDRESS=0x0455293B283CBBb26157e814a184eE977e1a5A3a
DB_PASSWORD=<random>
EOF
```

## 5. Launch
```bash
cd deploy
docker compose up -d --build          # first build compiles the jar (~1-2 min)
docker compose logs -f treasury       # watch it boot; ctrl-C to detach
```

## 6. Verify
```bash
curl -s localhost:8090/health                       # {"status":"UP"}
curl -s localhost:8090/supported 2>/dev/null || true # (facilitator is internal-only)
```
From your laptop/browser:
- Monitoring dashboard: `http://<EXTERNAL_IP>:8090/`
- Admin: `http://<EXTERNAL_IP>:8090/admin.html` → "set admin token" → enter `ADMIN_TOKEN`.
- Point an agent at it: `TREASURY_URL=http://<EXTERNAL_IP>:8090 AGENT_KEY=<key> ./scripts/run-agent.sh`
  (create the agent + key in the admin dashboard first; the committed `demo-key-agent-1` is public).

## 7. (Optional) HTTPS
For a clean `https://` URL, put **Caddy** in front (auto-TLS with a domain) or front it with a GCP
HTTPS load balancer. For a live demo, `http://<IP>:8090` is usually fine.

## Cost / lifecycle
`e2-small` is a few ₹/hour and easily covered by credits. Stop it when idle:
`gcloud compute instances stop agent-treasury` (and `start` before the demo).

## Updating after a code change
```bash
cd ~/agent-treasury && git pull && cd deploy && docker compose up -d --build
```

## Alternative: Cloud Run + Cloud SQL
Doable but more moving parts: containerize the app (this Dockerfile), deploy to Cloud Run, use Cloud
SQL (Postgres) via the connector, and run the facilitator as a second Cloud Run service reachable
privately. The VM above is simpler and matches the verified local setup — prefer it unless you
specifically want serverless.

## Security notes (important for a public URL)
- **Admin is protected** by `ADMIN_TOKEN` (set it!). Without it, `/api/admin/**` is open.
- The **facilitator port is not published** — only the app reaches it on the private network.
- The committed **`demo-key-agent-1`** is public; for anything you care about, create agents via the
  admin dashboard and use those keys. Spending is still bounded by each agent's policy + testnet funds.
- Dashboards (`/`, `/api/dashboard/**`) are **public read** by design (so judges can watch).
