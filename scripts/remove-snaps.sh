#!/usr/bin/env bash
set -euo pipefail

echo "=== Removing snaps and reinstalling natively ==="

# ---------- apt packages ----------
remove_snap_install_apt() {
  local snap_name=$1 pkg=$2
  sudo snap remove "$snap_name" 2>/dev/null || true
  sudo apt install -y "$pkg"
}

# VLC
remove_snap_install_apt vlc vlc

# VS Code
sudo snap remove code 2>/dev/null || true
wget -qO- https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor | sudo tee /etc/apt/trusted.gpg.d/microsoft.gpg >/dev/null
echo "deb [arch=amd64] https://packages.microsoft.com/repos/code stable main" | sudo tee /etc/apt/sources.list.d/vscode.list
sudo apt update && sudo apt install -y code

# Firefox (Mozilla PPA) — skipping, keep snap to preserve profile
# sudo snap remove firefox 2>/dev/null || true
# sudo add-apt-repository -y ppa:mozillateam/ppa
# sudo apt install -y firefox

# Brave — skipping, keep snap to preserve profile
# sudo snap remove brave 2>/dev/null || true
# sudo curl -fsSLo /usr/share/keyrings/brave-browser-archive-keyring.gpg https://brave-browser-apt-release.s3.brave.com/brave-browser-archive-keyring.gpg
# echo "deb [signed-by=/usr/share/keyrings/brave-browser-archive-keyring.gpg] https://brave-browser-apt-release.s3.brave.com/ stable main" | sudo tee /etc/apt/sources.list.d/brave.list
# sudo apt update && sudo apt install -y brave-browser

# DBeaver
sudo snap remove dbeaver-ce 2>/dev/null || true
sudo wget -qO /usr/share/keyrings/dbeaver.gpg.key https://dbeaver.io/debs/dbeaver.gpg.key
echo "deb https://dbeaver.io/debs/dbeaver-ce /" | sudo tee /etc/apt/sources.list.d/dbeaver.list
sudo apt update && sudo apt install -y dbeaver-ce

# ---------- .deb packages ----------
# Discord
sudo snap remove discord 2>/dev/null || true
wget -qO /tmp/discord.deb "https://discord.com/api/download?platform=linux&format=deb"
sudo apt install -y /tmp/discord.deb
rm -f /tmp/discord.deb

# Insomnia
sudo snap remove insomnia 2>/dev/null || true
wget -qO /tmp/insomnia.deb "https://updates.insomnia.rest/downloads/ubuntu/latest?&app=com.insomnia.app&source=website"
sudo apt install -y /tmp/insomnia.deb
rm -f /tmp/insomnia.deb

# ---------- tar.gz (JetBrains) ----------
# IntelliJ IDEA Community
sudo snap remove intellij-idea-community 2>/dev/null || true
cd /opt
sudo wget -q "https://download.jetbrains.com/idea/ideaIC-2026.1.3.tar.gz"
sudo tar xzf ideaIC-2026.1.3.tar.gz
sudo rm ideaIC-2026.1.3.tar.gz
sudo ln -sf /opt/idea-IC-*/bin/idea.sh /usr/local/bin/idea
cd -

# PyCharm Community
sudo snap remove pycharm-community 2>/dev/null || true
cd /opt
sudo wget -q "https://download.jetbrains.com/python/pycharm-community-2026.1.2.tar.gz"
sudo tar xzf pycharm-community-2026.1.2.tar.gz
sudo rm pycharm-community-2026.1.2.tar.gz
sudo ln -sf /opt/pycharm-community-*/bin/pycharm.sh /usr/local/bin/pycharm
cd -

# ---------- cleanup ----------
# Remove snap seed cache (already-installed snaps won't be affected)
echo "=== Cleaning snap seed cache ==="
sudo rm -rf /var/lib/snapd/seed/snaps/*

echo "=== Done. You should reboot to let GNOME use native packages. ==="
