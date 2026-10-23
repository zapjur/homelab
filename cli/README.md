# Homelab CLI

`hl` is a small local Babashka application for waking a server and accessing it
through OpenSSH. It installs no software on the server.

## Prerequisites

- macOS with Babashka (`bb`) on `PATH`. Developed and tested with 1.12.218.
- OpenSSH (`ssh`) on `PATH`.
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
   :wol-port 9}}}
```

These values are illustrative. `:ssh-host`, `:mac`, and `:broadcast` are
required strings. MAC addresses accept six hexadecimal octets with either
colons or hyphens. `:broadcast` must be an IPv4 literal; specify the broadcast
address appropriate to your subnet. The CLI cannot infer it without your
network's subnet mask. Optional `:wol-port` defaults to the protocol convention
of UDP port 9 and must be an integer from 1 to 65535. No host-specific defaults
are provided. Each entry in `:hosts` is validated; commands currently select
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

Use `hl ssh` to authenticate and verify the server's host key before checking
status. OpenSSH handles credentials, agent use, host key verification, and key
selection. `hl off` requests a terminal so that sudo can prompt for a password.
The CLI does not configure sudo permissions or enable passwordless sudo.

## Commands

| Command | Behavior |
| --- | --- |
| `hl on` | Send one UDP Wake-on-LAN Magic Packet; do not wait for boot |
| `hl off` | Run `ssh -t <host> sudo systemctl poweroff` |
| `hl ssh` | Open an interactive SSH session |
| `hl status` | Test SSH using a remote `true` command without authentication prompts |
| `hl help`, `hl --help` | Show usage |
| `hl --version` | Print the version maintained in `src/hl/core.clj` |

No arguments also prints help. Extra arguments and unknown commands return 2.
Configuration, UDP, and process startup errors return 1. SSH commands preserve
OpenSSH's exit code (including 255 for connection/authentication failures).
Status returns 0 only if the SSH command succeeds. Interactive commands inherit
terminal input/output; child processes are cleaned up when the CLI terminates.

Status uses a five-second connection timeout, one connection attempt, batch
authentication, strict known-host checking, and keepalives to detect a stalled
connection. This is not an absolute wall-clock deadline for all SSH configuration
hooks. An unavailable SSH service does not establish whether the machine is
powered off or whether its network is reachable.

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
  connectivity, hostname resolution, credentials, agent keys, and known-host
  entries. Status cannot prompt for passwords or accept an unknown host key.
- **Shutdown fails:** Inspect SSH/sudo output. The user must have permission to
  run `systemctl poweroff`. No server settings are changed by this CLI.
- **Packet sent but server stays asleep:** Wake-on-LAN generally requires the
  client to have network reachability to the target LAN/broadcast domain.
  Broadcasts usually do not traverse routers or VPNs. Check the chosen subnet
  broadcast address and existing hardware/firmware/network support. Successful
  UDP transmission does not confirm receipt or boot.
- **Broken symlink:** Update the symlink after moving the repository.

## Security

Keep actual host configuration, credentials, keys, and tokens outside Git.
The example contains no real infrastructure values. The existing repository
ignore rules cover common secret files; they are not a substitute for reviewing
changes before committing. Store private keys and SSH authentication settings
with appropriate permissions under your home directory.

Processes receive argument vectors without local shell interpolation. SSH
destinations cannot start with an option or contain whitespace or shell
metacharacters. Remote commands are fixed to `true` and `sudo systemctl poweroff`.
Your OpenSSH configuration remains trusted and may itself define proxy commands.
Host key checking is never disabled. Wake-on-LAN is unauthenticated UDP, so use
it only on networks you trust. `hl off` immediately requests shutdown; invoke it
only when you intend to power off the configured server.
