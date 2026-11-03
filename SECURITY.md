# Security

## Reporting

Please report vulnerabilities privately through GitHub's **"Report a vulnerability"** button (Security tab). Do not open a public issue.

## Threat model

| Threat | Mitigation |
|---|---|
| Someone on the same Wi-Fi / LAN | Sockets bind only to loopback and the Tailscale IP; nothing listens on LAN interfaces. |
| Another tailnet member (shared node, coworker on a shared tailnet) | Every connection's peer is looked up via `tailscale status`. Only the device owner's own login, or explicitly allowed logins/tags, are accepted. |
| Stolen phone with an open session | Sessions live only in `sessionStorage`, are bound to the client IP, and expire after 30 min idle or 12 h. `activedesk lock` on the device revokes all sessions; `activedesk pin` rotates the PIN. |
| PIN guessing | PBKDF2 (120k iterations) hash, constant-time compare. 5 failures per IP and 20 globally per 15 minutes. |
| Malicious web page in your browser (CSRF, DNS rebinding) | Bearer tokens (no cookies), Host header allow-list, Origin check on every API request. |
| XSS through chat messages or file names | All network text is inserted with `textContent`. Strict CSP: no inline script, no third-party origins. |
| Path traversal / overwrite through file transfer | Names are sanitised (including Windows reserved names). Writes stay inside `transfer.dir`; existing files are never overwritten. Size cap is `transfer.maxBytes`; SHA-256 is verified. |
| Spoofed chat author | A live message must arrive on its author's own connection. |
| Other local users on the same computer | Config is owner-only (POSIX). Privileged console commands require `local.token`. |
| Wake-on-LAN abuse | Packets go only to private broadcast addresses and are rate limited per device. |
| Elevated battery helper (Windows) | Installed into admin-only `Program Files`, so user-level code cannot swap the script. It only parses three integers from the config and never executes config content. It exits and restores normal charging when the node stops. |
| Battery control (Linux) | A udev rule grants the user's group write access to the charge-threshold files only. ActiveDesk never runs as root. |
| Unnoticed remote control | Red on-screen banner, chat notice and `audit.log` entry. |

## Known limits

- **The web UI is plain HTTP inside the WireGuard tunnel** (Tailscale already encrypts every packet end to end). For browser-level HTTPS, run `tailscale serve`.
- **Tailscale is part of the trust base.** Anyone who controls your Tailscale account controls which devices count as "yours".
- **Remote control runs with the user's own rights.** OS lock screens and UAC secure-desktop prompts cannot be controlled. That is deliberate: RDP covers the Windows lock screen.
