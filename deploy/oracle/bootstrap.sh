#!/usr/bin/env bash
set -euo pipefail

step() {
  printf '\n==> %s\n' "$1"
}

if [[ ${EUID} -ne 0 ]]; then
  printf 'Run this script with sudo: sudo %s\n' "$0" >&2
  exit 1
fi

. /etc/os-release
if [[ ${ID:-} != ubuntu || ${VERSION_ID:-} != 24.04 || $(dpkg --print-architecture) != arm64 ]]; then
  printf 'This bootstrap supports Ubuntu 24.04 on ARM64 only.\n' >&2
  exit 1
fi

step "Update Ubuntu and install prerequisites"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get upgrade -y
apt-get install -y ca-certificates curl gnupg unattended-upgrades fail2ban openssh-server git

step "Install Docker Engine and Compose from Docker's official repository"
install -m 0755 -d /etc/apt/keyrings
key_tmp=$(mktemp)
trap 'rm -f "$key_tmp"' EXIT
curl --fail --silent --show-error --location https://download.docker.com/linux/ubuntu/gpg --output "$key_tmp"
install -m 0644 "$key_tmp" /etc/apt/keyrings/docker.asc
rm -f "$key_tmp"
trap - EXIT
architecture=$(dpkg --print-architecture)
codename=${UBUNTU_CODENAME:-$VERSION_CODENAME}
printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
  "$architecture" "$codename" > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker

step "Create the deploy account and data group"
if ! id deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash deploy
fi
if getent group 10001 >/dev/null; then
  data_group=$(getent group 10001 | cut -d: -f1)
else
  groupadd --system --gid 10001 listing-guard-data
  data_group=listing-guard-data
fi
usermod --append --groups "docker,$data_group" deploy

sudo_home=
if [[ -n ${SUDO_USER:-} ]]; then
  sudo_home=$(getent passwd "$SUDO_USER" | cut -d: -f6)
fi
if [[ -n $sudo_home && -f $sudo_home/.ssh/authorized_keys ]]; then
  install -d -m 0700 -o deploy -g deploy /home/deploy/.ssh
  if [[ ! -e /home/deploy/.ssh/authorized_keys ]]; then
    install -m 0600 -o deploy -g deploy "$sudo_home/.ssh/authorized_keys" /home/deploy/.ssh/authorized_keys
  fi
fi

step "Require SSH public-key authentication"
install -d -m 0755 /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/00-listing-guard-key-only.conf <<'EOF'
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
ChallengeResponseAuthentication no
EOF
sshd -t
if ! sshd -T | grep -qi '^passwordauthentication no$'; then
  printf 'SSH is not configured to disable password authentication; refusing to reload sshd.\n' >&2
  exit 1
fi
if ! systemctl reload ssh; then
  systemctl restart ssh
fi

step "Enable automatic security updates"
if [[ ! -e /etc/apt/apt.conf.d/20auto-upgrades ]]; then
  cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
fi
systemctl enable --now apt-daily.timer apt-daily-upgrade.timer

step "Enable fail2ban for SSH"
install -d -m 0755 /etc/fail2ban/jail.d
cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

step "Create 4 GiB swap if the machine has none"
if [[ -z $(swapon --show --noheadings) ]]; then
  if [[ ! -e /swapfile ]]; then
    fallocate -l 4G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=4096 status=progress
    chmod 0600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
  if ! grep -Fqx '/swapfile none swap sw 0 0' /etc/fstab; then
    printf '/swapfile none swap sw 0 0\n' >> /etc/fstab
  fi
fi

step "Prepare the Listing Guard deployment and persistent-data folders"
install -d -m 0755 -o deploy -g deploy /opt/listing-guard
for directory in catalogs/raw batch/out health-report/out backups; do
  install -d -m 2770 -o 10001 -g "$data_group" "/opt/listing-guard/$directory"
done

printf '\nBootstrap complete. Log in as deploy with SSH keys; no application ports were opened.\n'
