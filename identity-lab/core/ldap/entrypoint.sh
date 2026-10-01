#!/bin/sh
# Builds the fictional directory at start-up. Passwords are hashed here; the
# root and reader passwords come from the environment (generated per run).
set -eu
: "${LAB_LDAP_ROOT_PASSWORD:?}" "${LAB_LDAP_READER_PASSWORD:?}"
mkdir -p /run/openldap /var/lib/openldap/lab
sed -i "s|^rootpw .*|rootpw   $(slappasswd -s "$LAB_LDAP_ROOT_PASSWORD")|" /etc/openldap/slapd.conf
if [ ! -f /var/lib/openldap/lab/data.mdb ]; then
  {
    printf 'dn: dc=lab,dc=invalid\nobjectClass: dcObject\nobjectClass: organization\no: Lab (FICTIONAL)\ndc: lab\n\n'
    printf 'dn: ou=people,dc=lab,dc=invalid\nobjectClass: organizationalUnit\nou: people\n\n'
    printf 'dn: cn=idp-reader,dc=lab,dc=invalid\nobjectClass: organizationalRole\nobjectClass: simpleSecurityObject\ncn: idp-reader\nuserPassword: %s\n\n' "$(slappasswd -s "$LAB_LDAP_READER_PASSWORD")"
    grep -v '^#' /lab/users.tsv | while IFS="$(printf '\t')" read -r uid pw inst name note; do
      [ -n "$uid" ] || continue
      printf 'dn: uid=%s,ou=people,dc=lab,dc=invalid\nobjectClass: inetOrgPerson\nuid: %s\ncn: %s\nsn: FICTIONAL\ndisplayName: %s\nmail: %s@lab.invalid\ndescription: %s\nuserPassword: %s\n' \
        "$uid" "$uid" "$name" "$name" "$uid" "$note" "$(slappasswd -s "$pw")"
      [ "$inst" = "-" ] || printf 'employeeNumber: %s\n' "$inst"
      printf '\n'
    done
  } > /tmp/seed.ldif
  slapadd -f /etc/openldap/slapd.conf -l /tmp/seed.ldif
  rm -f /tmp/seed.ldif
fi
exec slapd -d 0 -f /etc/openldap/slapd.conf -h "ldap:///"
