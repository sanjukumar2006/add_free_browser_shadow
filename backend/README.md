# VPN backend contract

The app expects a small control-plane API in front of WireGuard servers (wg0 on each).

## Auth
`Authorization: Bearer <account token>` on every call. Issue tokens per user/subscription (this is where premium tiers are enforced). Store only hashed tokens.

## `GET /v1/servers`
```json
[{"id":"in-mum-1","country":"India","city":"Mumbai","premium":false}]
```

## `POST /v1/connect`
Request: `{"serverId":"in-mum-1","publicKey":"<device WG public key, base64>"}`

The API should: validate token/plan, allocate a free tunnel IP (e.g. 10.8.0.x/32 and an IPv6 ULA), add the peer to that server (`wg set wg0 peer <pubkey> allowed-ips <ip>/32`), then respond:
```json
{"address":"10.8.0.7/32","dns":"10.8.0.1","endpoint":"203.0.113.10:51820","serverPublicKey":"<server WG public key>"}
```
(Add `fd00::7/128` handling if you want IPv6 inside the tunnel; the client already routes `::/0` through the tunnel so IPv6 can't leak around it.)

## Server checklist
- WireGuard on each node, NAT/forwarding enabled, DNS resolver bound to the tunnel address (e.g. Unbound, no query logging).
- Server private keys stay on the nodes (never in the API response or app).
- Remove idle peers; publish a no-logs policy only if it is actually true, and describe exactly what you collect.
- Rate-limit `/v1/connect`; rotate account tokens.

## Validation the client applies (your responses must satisfy these)
`/v1/connect` is rejected client-side unless: `address` is one or more comma-separated CIDRs (IPv4 /1–/32, IPv6 /1–/128, not 0.0.0.0/127.x); `dns` is one or more IP literals (not hostnames); `endpoint` is `host:port` (port 1–65535, bracketed IPv6 allowed, not loopback/0.0.0.0); `serverPublicKey` is base64 of exactly 32 non-zero bytes. Responses over 256 KiB are rejected. The API base URL must be `https://` and not an `example.com` placeholder, otherwise the VPN screen shows "not configured".
The client only reports "Connected" after a WireGuard handshake completes (15 s timeout), so your server must register the peer *before* returning the response.
Android cannot enforce a kill switch from an app; users must enable system "Always-on VPN" and "Block connections without VPN".
