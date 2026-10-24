# Homelab Ansible

Ansible provides infrastructure automation independently of the Babashka `hl`
CLI. Currently it only inspects the existing AlmaLinux 10 system. It does not
reproduce or manage its configuration, install packages, or install an agent.

## Prerequisites and installation

The macOS controller needs OpenSSH and a supported Python 3 version. The project
uses only `ansible.builtin` modules; `ansible-core` is sufficient. With Homebrew
available, install through an isolated pipx environment:

```sh
brew install pipx python@3.12
pipx ensurepath
# Open a new terminal if pipx changed PATH.
pipx install --python "$(brew --prefix python@3.12)/bin/python3.12" ansible-core
ansible --version
```

Python 3.12 is an explicit supported controller interpreter; using whichever
newest Python pipx finds can fail with an unsupported Python release.
See the official [Ansible installation guide](https://docs.ansible.com/projects/ansible/latest/intro_installation.html)
and [pipx installation guide](https://pipx.pypa.io/latest/how-to/install-pipx.html).

The remote server needs working SSH key authentication and a Python 3 interpreter
supported by the installed ansible-core version. AlmaLinux 10's Python 3.12 is
suitable for current ansible-core releases. Existing system tools provide the
diagnostics: systemd, util-linux, coreutils, iproute, NetworkManager's `nmcli`,
and LVM tools when installed. The playbook never installs missing tools.
Only LVM inspection requests sudo; use an account with existing appropriate
permissions, or skip that section. No global privilege escalation is enabled.

## Layout

```text
infra/ansible/
├── ansible.cfg
├── README.md
├── inventories/
│   └── example.yml
├── playbooks/
│   └── inspect.yml
└── roles/                 # reserved for future roles; currently empty

~/.config/hl/
├── config.edn             # existing CLI configuration; leave unchanged
└── ansible/
    └── inventory.yml      # real inventory, outside Git
```

Git does not retain empty directories. After a fresh checkout, `roles/` will
appear when the first real role is added. No placeholder role is needed.
Future roles can cover base configuration, security, services, storage, Podman,
monitoring, and backups; none are implemented now. `roles_path` is already set
relative to `ansible.cfg`.

## Local inventory and OpenSSH

Run these setup commands from the repository root. Copy only if the destination
does not already exist; then edit the external file:

```sh
mkdir -p ~/.config/hl/ansible
cp -n infra/ansible/inventories/example.yml ~/.config/hl/ansible/inventory.yml
chmod 600 ~/.config/hl/ansible/inventory.yml
```

Keep `~/.config/hl/ansible/inventory.yml` outside Git. Do not change the CLI's
`~/.config/hl/config.edn`. In the inventory, replace `my-server` with an existing
OpenSSH host alias. `server` is just an inventory label; `homelab` selects the
group. Do not add usernames, private keys, or credentials to the inventory.

OpenSSH owns the actual hostname, user, and identity in `~/.ssh/config`. If an
alias is already configured, reuse it. This example contains placeholders only:

```sshconfig
Host my-server
    HostName server.example.invalid
    User example-user
    IdentityFile ~/.ssh/example-key
```

Verify the server's host key through a trusted channel before accepting it into
`known_hosts`. Keep host key verification enabled. Test the alias with
`ssh my-server true`; use `ssh -v my-server true` for diagnostics.

## Running commands

Execute from `infra/ansible/` so Ansible finds this project's configuration:

```sh
cd infra/ansible
ansible --version
ansible-config dump --only-changed
ansible-inventory --graph
ansible homelab -m ansible.builtin.ping
ansible-playbook playbooks/inspect.yml --syntax-check
ansible-playbook playbooks/inspect.yml
```

Check that `ansible --version` reports this directory's `ansible.cfg` and that
`DEFAULT_HOST_LIST` in `ansible-config dump --only-changed` contains the expanded
absolute path to `~/.config/hl/ansible/inventory.yml`. Ansible's inventory path
configuration expands `~`; it does not depend on shell expansion of the INI
value. Environment variables such as `ANSIBLE_INVENTORY` can override it.
If your version does not resolve it as expected, explicitly pass
`-i "$HOME/.config/hl/ansible/inventory.yml"` to inventory, ad-hoc, and playbook
commands. This passes a shell-expanded absolute path.

Ansible does not search arbitrary parent directories for `ansible.cfg`. From
elsewhere, explicitly select the configuration and playbook, for example:

```sh
ANSIBLE_CONFIG=/absolute/path/to/homelab/infra/ansible/ansible.cfg \
  ansible-playbook /absolute/path/to/homelab/infra/ansible/playbooks/inspect.yml
```

`ansible.builtin.ping` checks SSH access and execution of a Python Ansible module;
it is not an ICMP reachability test. `--syntax-check` and inventory graph output
do not connect to the server. Ping and inspection do connect.

If sudo requires a password, request an interactive prompt:

```sh
ansible-playbook playbooks/inspect.yml --ask-become-pass
```

To inspect without any LVM queries or sudo requests:

```sh
ansible-playbook playbooks/inspect.yml -e inspect_lvm=false
```

Check mode is supported, but still connects and executes the read-only queries:

```sh
ansible-playbook playbooks/inspect.yml --check --ask-become-pass
```

The command tasks explicitly use `check_mode: false` because Ansible otherwise
skips commands without filesystem guards. All queries use `changed_when: false`.
Check mode is not an offline test or an additional safety boundary here.

## Inspection behavior and safety

Output summarizes system facts, SELinux, firewalld/sshd/smartd service status,
failed systemd units, mounts, disk usage, block devices, interfaces, IPv4
addresses/default route, active NetworkManager connections, and LVM metadata.
Missing optional facts and absent services are reported explicitly. An absent
diagnostic executable is reported and tolerated; permission errors, unsupported
options, and other command failures remain failures. An installed but inactive
NetworkManager that cannot answer queries will therefore produce a visible error.
No failed units or an empty default route is valid empty output.

Only the LVM query task uses `become: true`. Its `--readonly` mode reads on-disk
metadata without metadata locks or activation and cannot report live LV use;
use `lsblk` and `findmnt` to understand mounted devices. Process-local `--config`
overrides disable hints (including invalidation), devices-file updates/locks,
and LVM file/syslog logging. Disabling the devices file means these queries scan
devices using the existing LVM filters rather than the devices-file allowlist.
No LVM configuration file is edited. See the upstream
[LVM command implementation](https://github.com/lvmteam/lvm2/blob/main/tools/lvmcmdline.c)
for hints handling and the [pvs manual](https://man7.org/linux/man-pages/man8/pvs.8.html)
for read-only reporting semantics.

The playbook has no package, file-writing, service-management, account,
firewall, SELinux-management, network-management, power, or Wake-on-LAN tasks.
SSH pipelining sends the Python modules through stdin to avoid remote module
file transfer. Keep pipelining enabled and do not set `ANSIBLE_KEEP_REMOTE_FILES`.
Normal SSH/sudo/system audit logging can still occur; read-only inspection does
not mean an unaudited session. No persistent fact cache or output log is enabled.
Treat diagnostic output as private: it can contain addresses, device identifiers,
filesystem paths, and connection names. Do not commit inventory, output, or secrets.

## Troubleshooting

- **Configuration or inventory missing:** Run from `infra/ansible/`, inspect
  `ansible --version` and `ansible-config dump --only-changed`, and confirm the
  external inventory exists. Use the absolute `-i` override above if necessary.
- **SSH failure:** First test the alias directly with OpenSSH. Check network/VPN
  access, agent keys, and alias settings. Resolve host key mismatches using a
  trusted source; never bypass checking with insecure SSH options.
- **Python discovery failure:** Confirm a supported Python exists with
  `ssh my-server 'python3 --version'`. Automatic discovery is enabled. If needed,
  set `ansible_python_interpreter` to the verified remote interpreter path in
  the external inventory only. The playbook does not bootstrap Python.
- **Local Ansible startup failure:** If Ansible crashes before connecting, check
  its controller Python version. For a pipx installation, recreate its environment
  with a supported Python, such as `pipx reinstall --python python3.12 ansible-core`
  (use the package name `ansible` if that is what you installed). If a full Ansible
  pipx installation exposes only `ansible-community`, its dependency executables
  also need exposing; use pipx's `--include-deps` installation option.
- **Sudo failure:** Use `--ask-become-pass` if required, or skip LVM with
  `-e inspect_lvm=false`. The existing account must already have permission to
  run the LVM reporting commands. Do not grant broad sudo permissions to make
  inspection work. A sudo policy requiring a TTY conflicts with pipelining;
  skip LVM and review that policy separately rather than changing it here.
- **Missing tools or unsupported options:** Missing executables are shown in
  output. Review other errors explicitly; do not suppress them globally or
  install server software as part of this inspection.

## Local validation

Without contacting a server, use the generic example inventory:

```sh
cd infra/ansible
ansible-config dump --only-changed
ansible-inventory -i inventories/example.yml --graph
ansible-playbook -i inventories/example.yml playbooks/inspect.yml --syntax-check
# If already installed:
ansible-lint playbooks/inspect.yml
```
