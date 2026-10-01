#!/usr/bin/env bash
# Lab administration for the FICTIONAL identity lab (local containers only).
#   admin.sh suspend|unsuspend <institutional-id>
#   admin.sh revoke-grant|restore-grant <institutional-id> <CENTRAL|UNIT|IO|CONTACT_DERIVATION>
#   admin.sh add-grant <institutional-id> UNIT <unit>|IO <io>|CENTRAL   /  remove-grant <institutional-id> <type>
#   admin.sh revoke-link|restore-link <institutional-id>  (identity mapping, lab issuer only)
#   admin.sh rename-login <old-uid> <new-uid>          (same person, new login name)
#   admin.sh add-account <uid> <password> <inst-id|-> <display name>
#   admin.sh remove-account <uid>
#   admin.sh nameid persistent|transient               (what the simulated Cognito SP requests)
#   admin.sh status                                    (links, enrollment events, profiles)
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
load_secrets
archive() { docker exec -i lab-archive-db psql -q -v ON_ERROR_STOP=1 -U lab_archive -d identity_lab "$@"; }
pool() { docker exec -i lab-pool-db psql -q -v ON_ERROR_STOP=1 -U lab_pool -d lab_pool "$@"; }
ldap() { local tool="$1"; shift
  docker exec -i lab-ldap "$tool" -x -H ldap://localhost -D cn=admin,dc=lab,dc=invalid -w "$LAB_LDAP_ROOT_PASSWORD" "$@"; }
cmd="${1:-}"; shift || true
case "$cmd" in
  suspend|unsuspend)
    v=$([ "$cmd" = suspend ] && echo true || echo false)
    archive -c "INSERT INTO authz.person_status VALUES ('$1', $v, 'lab-admin', now(), 'lab $cmd')
      ON CONFLICT (institutional_identifier) DO UPDATE SET suspended = $v, changed_by = 'lab-admin', changed_at = now()" ;;
  revoke-grant)
    archive -c "UPDATE authz.access_grant SET revoked_by = 'lab-admin', revoked_at = now()
      WHERE institutional_identifier = '$1' AND grant_type = '$2' AND revoked_at IS NULL" ;;
  restore-grant)
    archive -c "UPDATE authz.access_grant SET revoked_by = NULL, revoked_at = NULL
      WHERE institutional_identifier = '$1' AND grant_type = '$2' AND revoked_by = 'lab-admin'" ;;
  revoke-link|restore-link)
    if [ "$cmd" = revoke-link ]; then
      archive -c "UPDATE authz.identity_link SET status = 'REVOKED', revoked_by = 'lab-admin', revoked_at = now()
        WHERE institutional_identifier = '$1' AND status = 'ACTIVE' AND cognito_issuer LIKE 'https://localhost:9443/%'"
    else
      archive -c "UPDATE authz.identity_link SET status = 'ACTIVE', revoked_by = NULL, revoked_at = NULL
        WHERE institutional_identifier = '$1' AND revoked_by = 'lab-admin' AND cognito_issuer LIKE 'https://localhost:9443/%'"
    fi ;;
  add-grant)   # add-grant <inst> UNIT <unit> | IO <io> | CENTRAL | CONTACT_DERIVATION
    case "$2" in
      UNIT) cols="'$3', NULL" ;; IO) cols="NULL, '$3'" ;; *) cols="NULL, NULL" ;;
    esac
    archive -c "INSERT INTO authz.access_grant (institutional_identifier, grant_type, unit_number, io_value, granted_by, reason)
      VALUES ('$1', '$2', $cols, 'lab-admin', 'lab add-grant')" ;;
  remove-grant)
    archive -c "UPDATE authz.access_grant SET revoked_by = 'lab-admin', revoked_at = now()
      WHERE institutional_identifier = '$1' AND grant_type = '$2' AND granted_by = 'lab-admin' AND revoked_at IS NULL" ;;
  rename-login)
    ldap ldapmodrdn -r "uid=$1,ou=people,dc=lab,dc=invalid" "uid=$2" ;;
  add-account)
    hash=$(docker exec lab-ldap slappasswd -s "$2")
    { printf 'dn: uid=%s,ou=people,dc=lab,dc=invalid\nobjectClass: inetOrgPerson\nuid: %s\ncn: %s\nsn: FICTIONAL\ndisplayName: %s\nmail: %s@lab.invalid\nuserPassword: %s\n' "$1" "$1" "$4" "$4" "$1" "$hash"
      [ "$3" = "-" ] || printf 'employeeNumber: %s\n' "$3"; } | ldap ldapadd >/dev/null ;;
  remove-account)
    ldap ldapdelete "uid=$1,ou=people,dc=lab,dc=invalid" ;;
  nameid)
    pool -c "INSERT INTO setting VALUES ('nameid_mode', '$1') ON CONFLICT (key) DO UPDATE SET value = '$1'" ;;
  status)
    archive -c "SELECT cognito_subject, institutional_identifier, kuali_person_id, login_name, method, status FROM authz.identity_link WHERE cognito_issuer LIKE 'https://localhost:9443/%' ORDER BY identity_link_id"
    archive -c "SELECT occurred_at::time(0), institutional_identifier, outcome FROM identity_lab.enrollment_event ORDER BY event_id DESC LIMIT 15"
    pool -c "SELECT username, sub, attributes->>'custom:login' AS login FROM user_profile ORDER BY created_at" ;;
  *) sed -n '2,11p' "$0"; exit 2 ;;
esac
