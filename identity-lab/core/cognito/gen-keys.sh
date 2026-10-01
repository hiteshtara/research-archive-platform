#!/bin/sh
# Generates every TEST key and certificate for one lab run into $1.
# Never committed (.identity-lab/ is git-ignored). Not for any other use.
set -eu
OUT="$1"; mkdir -p "$OUT"; cd "$OUT"
[ -f ca.crt ] && { echo "keys already present in $OUT"; exit 0; }
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout ca.key -out ca.crt \
  -subj "/CN=Identity Lab TEST CA - local only" 2>/dev/null
tls() {  # name
  openssl req -newkey rsa:2048 -nodes -keyout "$1.key" -out "$1.csr" -subj "/CN=localhost" 2>/dev/null
  printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n' > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 \
    -extfile "$1.ext" -out "$1.crt" 2>/dev/null
  rm -f "$1.csr" "$1.ext"
}
tls idp-tls; tls cognito-tls
selfsigned() {  # name cn
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout "$1.key" -out "$1.crt" -subj "/CN=$2" 2>/dev/null
}
selfsigned idp-signing "lab IdP signing (TEST)"
selfsigned idp-encryption "lab IdP encryption (TEST)"
selfsigned sp-signing "lab simulated Cognito SP signing (TEST)"
selfsigned sp-encryption "lab simulated Cognito SP encryption (TEST)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out token-signing.key 2>/dev/null
rnd() { openssl rand -base64 24 | tr -dc 'A-Za-z0-9'; }
{
  echo "LAB_LDAP_ROOT_PASSWORD=$(rnd)"
  echo "LAB_LDAP_READER_PASSWORD=$(rnd)"
  echo "LAB_PERSISTENT_ID_SALT=$(rnd)$(rnd)"
  echo "LAB_POOL_DB_PASSWORD=$(rnd)"
  echo "LAB_ARCHIVE_DB_PASSWORD=$(rnd)"
} > secrets.env
chmod 600 *.key secrets.env
chmod 644 *.crt
echo "test keys generated in $OUT"
