# Homelab CLI

`hl` is a small local Babashka application for waking a server and accessing it
through OpenSSH. It installs no software on the server.

## Prerequisites

- macOS with Babashka (`bb`) on `PATH`. Developed and tested with 1.12.218.
- OpenSSH (`ssh`) and macOS netcat (`nc`) on `PATH` (both ship with macOS).
- A server configured for Wake-on-LAN and SSH. Shutdown requires permission to
  run `sudo systemctl poweroff` on the AlmaLinux server.

## Installation

From any directory, substitute your absolute repository path:

```sh
mkdir -p ~/.local/bin
ln -s /absolute/path/to/homelab/cli/bin/hl ~/.local/bin/hl
```

Add `~/.local/bin` to your shell's `PATH`, for example in `~/.zshrc`:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

The launcher resolves its own symlink to locate source files. You can run it
from any directory without setting `BABASHKA_CLASSPATH` or copying sources.
Keep the repository at the symlink's target location.

## Configuration

Create the configuration outside the repository:

```sh
mkdir -p ~/.config/hl
cp /absolute/path/to/homelab/cli/resources/config.example.edn ~/.config/hl/config.edn
chmod 600 ~/.config/hl/config.edn
```

Edit the placeholders to match your network:

```clojure
{:hosts
 {:default
  {:ssh-host "my-server"
   :mac "AA:BB:CC:DD:EE:FF"
   :broadcast "192.168.1.255"
   :wol-port 9
   :boot-timeout-seconds 300}}}
```

These values are illustrative. `:ssh-host`, `:mac`, and `:broadcast` are
required strings. MAC addresses accept six hexadecimal octets with either
colons or hyphens. `:broadcast` must be an IPv4 literal; specify the broadcast
address appropriate to your subnet. The CLI cannot infer it without your
network's subnet mask. Optional `:wol-port` defaults to the protocol convention
of UDP port 9 and must be an integer from 1 to 65535. No host-specific defaults
are provided. Optional `:boot-timeout-seconds` defaults to 300 (five minutes)
and must be a positive integer. It controls only the wait after `hl on`; the
command returns as soon as the port is reachable. Each entry in `:hosts` is validated; commands currently select
`:default` only.

Configuration is always read from the current user's home directory at
`~/.config/hl/config.edn`. Help and version do not require configuration.

## SSH configuration

Configure your alias, hostname, username, and identity in `~/.ssh/config`.
For example, using placeholder values:

```sshconfig
Host my-server
    HostName server.example.invalid
    User example-user
    IdentityFile ~/.ssh/example-key
```

OpenSSH handles credentials, agent use, host key verification, and key
selection for `hl ssh` and `hl off`. Status and wake waiting do not authenticate
or require a trusted host key. `hl off` requests a terminal so that sudo can prompt for a password.
The CLI does not configure sudo permissions or enable passwordless sudo.

## Commands

| Command | Behavior |
| --- | --- |
| `hl on` | Send one UDP Wake-on-LAN Magic Packet and wait for the SSH TCP port (five-minute default) |
| `hl on --no-wait` | Return immediately after sending the packet |
| `hl off` | Run `ssh -t <host> sudo systemctl poweroff` |
| `hl ssh` | Open an interactive SSH session |
| `hl status` | Check whether the SSH TCP port accepts a connection; no authentication |
| `hl help`, `hl --help` | Show usage |
| `hl --version` | Print the version maintained in `src/hl/core.clj` |

`hl on` checks the SSH TCP port immediately, then retries after two seconds
between failed attempts, with a connection budget set by `:boot-timeout-seconds`
(default: 300 seconds). Each TCP connect
is limited to five seconds or the remaining budget. A timeout returns 1.
`--no-wait` skips all status checks. Ctrl+C cancels waiting.

Status and wake waiting use `ssh -G` to resolve the hostname and port from your
existing OpenSSH alias, then use native `nc` to open and close a direct TCP
connection. The native resolver handles macOS DNS and address fallback, rather
than selecting one address through Java. `AddressFamily` is respected. Neither
check authenticates, reads a key, or requires an entry in `known_hosts`.
An open port does not prove SSH protocol health, successful login, or application
readiness. An unreachable port does not prove the machine is powered off.

These checks require direct connectivity to the destination; aliases using
`ProxyJump` or `ProxyCommand` produce a descriptive error. Interactive SSH and
shutdown continue to support those settings through OpenSSH. Local SSH config
evaluation is limited to five seconds; each probe has an outer process timeout
that also bounds DNS resolution. Status prints the destination and connection
error; wake waiting prints the destination and the last error on timeout.

No arguments also prints help. Unsupported arguments and unknown commands return 2.
Configuration, UDP, and process startup errors return 1. Interactive SSH and
shutdown preserve OpenSSH's exit code (including 255 for connection/authentication
failures). Status returns 0 for an open TCP port and 1 when it is unreachable.
Interactive commands inherit terminal input/output; child processes are cleaned
up when the CLI terminates.

## Tests

```sh
cd /absolute/path/to/homelab/cli
bb test
```

Tests use Babashka's bundled `clojure.test` (there is no built-in
`babashka.test` namespace), with no additional dependencies. They isolate
process execution and UDP transmission, use temporary configuration files, and
never contact a real server, send power packets, or change SSH configuration.

## Troubleshooting

- **`bb` not found:** Install Babashka and ensure its executable is on `PATH`.
- **Missing/invalid configuration:** Check the reported path, EDN syntax, and
  required fields. Use exactly one EDN map without executable Clojure forms.
- **SSH unavailable:** Use `hl ssh` to inspect OpenSSH's diagnostics. Check VPN
  connectivity, hostname resolution, and the configured SSH port shown by
  `hl status`, along with its connection error. If status
  succeeds but login fails, check credentials, agent keys, and known-host entries.
- **Shutdown fails:** Inspect SSH/sudo output. The user must have permission to
  run `systemctl poweroff`. No server settings are changed by this CLI.
- **Packet sent but server stays asleep:** Wake-on-LAN generally requires the
  client to have network reachability to the target LAN/broadcast domain.
  Broadcasts usually do not traverse routers or VPNs. Check the chosen subnet
  broadcast address and existing hardware/firmware/network support. Successful
  UDP transmission does not confirm receipt or boot. The default wait also
  needs the SSH TCP port to accept connections; use `hl on --no-wait` when
  you only want to send the packet.
- **Broken symlink:** Update the symlink after moving the repository.

## Security

Keep actual host configuration, credentials, keys, and tokens outside Git.
The example contains no real infrastructure values. The existing repository
ignore rules cover common secret files; they are not a substitute for reviewing
changes before committing. Store private keys and SSH authentication settings
with appropriate permissions under your home directory.

Processes receive argument vectors without local shell interpolation. SSH
destinations cannot start with an option or contain whitespace or shell
metacharacters. The only remote command is `sudo systemctl poweroff`.
Your OpenSSH configuration remains trusted: `ssh -G` can evaluate local
`Match exec` hooks, and interactive SSH may use configured proxy commands.
Host key checking is never disabled. Wake-on-LAN is unauthenticated UDP, so use
it only on networks you trust. `hl off` immediately requests shutdown; invoke it
only when you intend to power off the configured server.

## LAN and Tailscale SSH transports

The default `:ssh-host` alias is used for `hl ssh`, `hl off`, and `hl status`.
Optionally configure `:tailscale-ssh-host` in `~/.config/hl/config.edn` to
use `hl ssh --tailscale`, `hl off --tailscale`, and `hl status --tailscale`.
Both fields are **OpenSSH aliases** defined in `~/.ssh/config`. The Tailscale
option is rejected for `hl on`: `hl on` and `hl on --no-wait` still send a
local LAN Wake-on-LAN packet, and default waiting still probes the LAN SSH port.

Example local aliases: `homelab-lan` for the local IPv4 address, and
`homelab-tailnet` for the Tailscale MagicDNS name. Neither example is
required; choose your own aliases.
