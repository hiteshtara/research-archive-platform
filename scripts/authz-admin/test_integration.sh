#!/usr/bin/env bash
# Integration test for authz_admin.py against a THROWAWAY local Postgres container.
#
# Starts `docker run postgres` on a random 127.0.0.1 port, applies V082 + V083 only,
# exercises import / grant / revoke / suspend / list with FICTIONAL data, checks the
# database state and the audit trail, then removes the container. Never touches any
# other database: AUTHZ_ADMIN_DATABASE_URL is set here, to the container, and nowhere else.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
IMAGE="${AUTHZ_ADMIN_TEST_IMAGE:-postgres:17}"
NAME="authz-admin-it-$$-$RANDOM"
PASSWORD="it-$RANDOM$RANDOM"
WORK="$(mktemp -d)"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD="$PASSWORD" -e POSTGRES_DB=authz_it \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
PORT="$(docker port "$NAME" 5432/tcp | head -1 | sed 's/.*://')"
for _ in $(seq 1 60); do
    docker exec "$NAME" pg_isready -U postgres -d authz_it >/dev/null 2>&1 && break
    sleep 1
done
# pg_isready can succeed during the init restart; wait for a real query.
for _ in $(seq 1 30); do
    docker exec "$NAME" psql -U postgres -d authz_it -tAc 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1
done

psql_c() { docker exec -i "$NAME" psql -v ON_ERROR_STOP=1 -U postgres -d authz_it -tA "$@"; }
for m in V082__create_authorization_identity_and_grants.sql V083__create_authz_kim_crosswalk.sql; do
    psql_c -q < "$ROOT/database/migrations/$m"
done
# Idempotent: applying V083 a second time is a no-op.
psql_c -q < "$ROOT/database/migrations/V083__create_authz_kim_crosswalk.sql"

export AUTHZ_ADMIN_DATABASE_URL="postgresql://postgres:${PASSWORD}@127.0.0.1:${PORT}/authz_it"
admin() { uv run --quiet --no-project --with 'psycopg[binary]' python "$HERE/authz_admin.py" "$@"; }

fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { # expect <description> <sql> <expected>
    local got
    got="$(psql_c -c "$2")"
    [[ "$got" == "$3" ]] || fail "$1: expected [$3], got [$got]"
    echo "ok  $1"
}
expect_refused() { # expect_refused <description> <command...>
    local desc="$1"; shift
    if admin "$@" >"$WORK/out" 2>&1; then fail "$desc: command succeeded"; fi
    grep -q "$PASSWORD" "$WORK/out" && fail "$desc: output contains the database password"
    echo "ok  $desc (refused)"
}

printf 'prncpl_id\tentity_id\tactv_ind\nSYNP-1\tSYNE-1\tY\nSYNP-2\tSYNE-2\tY\nSYNP-3\tSYNE-3\tN\n' > "$WORK/p.tsv"
printf 'attribute_name\tattribute_value\tprncpl_id\tevidence_ref\tverified_by\n' > "$WORK/x.tsv"
printf 'synAttr\tSYN-V-1\tSYNP-1\tFICTIONAL-1\tsynthetic\nsynAttr\tSYN-V-2\tSYNP-2\tFICTIONAL-2\tsynthetic\nsynAttr\tSYN-V-3\tSYNP-3\tFICTIONAL-3\tsynthetic\n' >> "$WORK/x.tsv"
cp "$WORK/x.tsv" "$WORK/bad.tsv"
printf 'synAttr\tpat@example.invalid\tSYNP-2\tFICTIONAL-4\tsynthetic\n' >> "$WORK/bad.tsv"

# --- crosswalk ----------------------------------------------------------------------------
admin crosswalk-validate "$WORK/p.tsv" "$WORK/x.tsv" --attribute synAttr >/dev/null
expect_refused "invalid crosswalk is rejected" crosswalk-import "$WORK/p.tsv" "$WORK/bad.tsv" \
    --attribute synAttr --load-ref SYN-LOAD-0 --actor it-admin
expect "nothing imported after a rejected file" "SELECT count(*) FROM authz.kim_principal" "0"

admin crosswalk-import "$WORK/p.tsv" "$WORK/x.tsv" --attribute synAttr --load-ref SYN-LOAD-1 --actor it-admin --dry-run >/dev/null
expect "dry run rolls back" "SELECT count(*) FROM authz.kim_principal" "0"

admin crosswalk-import "$WORK/p.tsv" "$WORK/x.tsv" --attribute synAttr --load-ref SYN-LOAD-1 --actor it-admin > "$WORK/out"
grep -q "SYN-V-" "$WORK/out" && fail "import output printed attribute values"
expect "principals imported (inactive kept as inactive)" \
    "SELECT string_agg(prncpl_id || ':' || actv_ind, ',' ORDER BY prncpl_id) FROM authz.kim_principal" "SYNP-1:Y,SYNP-2:Y,SYNP-3:N"
expect "only active-principal rows imported" \
    "SELECT string_agg(attribute_value, ',' ORDER BY attribute_value) FROM authz.principal_crosswalk WHERE status = 'ACTIVE'" "SYN-V-1,SYN-V-2"
admin crosswalk-import "$WORK/p.tsv" "$WORK/x.tsv" --attribute synAttr --load-ref SYN-LOAD-2 --actor it-admin >/dev/null
expect "re-import is idempotent" "SELECT count(*) FROM authz.principal_crosswalk" "2"

printf 'attribute_name\tattribute_value\tprncpl_id\tevidence_ref\tverified_by\nsynAttr\tSYN-V-1\tSYNP-2\tFICTIONAL-5\tsynthetic\n' > "$WORK/conflict.tsv"
expect_refused "a conflicting mapping is never silently replaced" crosswalk-import "$WORK/p.tsv" "$WORK/conflict.tsv" \
    --attribute synAttr --load-ref SYN-LOAD-3 --actor it-admin
expect "conflict import changed nothing" "SELECT max(load_ref) FROM authz.kim_principal" "SYN-LOAD-2"

# --- grants -------------------------------------------------------------------------------
expect_refused "self-grant" grant-add --type UNIT --grantee SYN-V-1 --unit SYN-U-1 --granted-by SYN-V-1 --approved-by SYN-ADM-2 --reason it
expect_refused "CENTRAL without a second person" grant-add --type CENTRAL --grantee SYN-V-1 --granted-by SYN-ADM-1 --approved-by SYN-ADM-1 --reason it
admin grant-add --type CENTRAL --grantee SYN-V-1 --granted-by SYN-ADM-1 --approved-by SYN-ADM-2 --reason it >/dev/null
admin grant-add --type UNIT --grantee SYN-V-2 --unit SYN-U-100 --include-descendants --granted-by SYN-ADM-1 --approved-by SYN-ADM-2 --reason it >/dev/null
admin grant-add --type IO --grantee SYN-V-2 --io SYN-IO-1 --granted-by SYN-ADM-1 --approved-by SYN-ADM-2 --reason it --dry-run >/dev/null
expect "grants written (dry run excluded)" \
    "SELECT string_agg(grant_type, ',' ORDER BY grant_id) FROM authz.access_grant" "CENTRAL,UNIT"
GRANT_ID="$(psql_c -c "SELECT grant_id FROM authz.access_grant WHERE grant_type = 'CENTRAL'")"
admin grant-revoke --grant-id "$GRANT_ID" --revoked-by SYN-ADM-2 --reason it >/dev/null
expect "grant revoked" "SELECT revoked_by FROM authz.access_grant WHERE grant_id = $GRANT_ID" "SYN-ADM-2"
expect_refused "revoking twice" grant-revoke --grant-id "$GRANT_ID" --revoked-by SYN-ADM-2 --reason it

# --- links, crosswalk revocation, suspension ------------------------------------------------
psql_c -q -c "INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier, kuali_person_id,
    method, status, verified_by, verified_at) VALUES ('https://idp.invalid/pool', 'sub-1', 'SYN-V-1', 'SYNP-1',
    'AUTO_VERIFIED', 'ACTIVE', 'api-enrollment', now())"
admin link-revoke --institutional-id SYN-V-1 --revoked-by SYN-ADM-2 --reason it >/dev/null
expect "link revoked" "SELECT status || ':' || revoked_by FROM authz.identity_link" "REVOKED:SYN-ADM-2"
psql_c -q -c "INSERT INTO authz.identity_link (cognito_issuer, cognito_subject, institutional_identifier, kuali_person_id,
    method, status, verified_by, verified_at) VALUES ('https://idp.invalid/pool', 'sub-2', 'SYN-V-2', 'SYNP-2',
    'AUTO_VERIFIED', 'ACTIVE', 'api-enrollment', now())"
admin crosswalk-revoke --prncpl-id SYNP-2 --attribute synAttr --revoked-by SYN-ADM-2 --reason it >/dev/null
expect "crosswalk row revoked" "SELECT status FROM authz.principal_crosswalk WHERE prncpl_id = 'SYNP-2'" "REVOKED"
expect "its sign-in link revoked in the same transaction" \
    "SELECT status || ':' || revoked_by FROM authz.identity_link WHERE cognito_subject = 'sub-2'" "REVOKED:SYN-ADM-2"
admin suspend --institutional-id SYN-V-2 --changed-by SYN-ADM-2 --reason it >/dev/null
expect "suspended" "SELECT suspended FROM authz.person_status WHERE institutional_identifier = 'SYN-V-2'" "t"
admin unsuspend --institutional-id SYN-V-2 --changed-by SYN-ADM-2 --reason it >/dev/null
expect "unsuspended" "SELECT suspended FROM authz.person_status WHERE institutional_identifier = 'SYN-V-2'" "f"

# --- audit ----------------------------------------------------------------------------------
expect "every write audited in its own transaction (no dry-run or refused rows)" \
    "SELECT string_agg(action, ',' ORDER BY audit_id) FROM authz.access_audit" \
    "CROSSWALK_IMPORTED,CROSSWALK_IMPORTED,GRANT_ADDED,GRANT_ADDED,GRANT_REVOKED,LINK_REVOKED,CROSSWALK_REVOKED,LINK_REVOKED,PERSON_SUSPENDED,PERSON_UNSUSPENDED"
expect "every audit row records the database role actually used" \
    "SELECT count(*) FROM authz.access_audit WHERE detail ? 'db_current_user'" \
    "$(psql_c -c "SELECT count(*) FROM authz.access_audit")"
if psql_c -c "DELETE FROM authz.access_audit" >/dev/null 2>&1; then fail "audit rows could be deleted"; fi
echo "ok  audit is append-only"

admin list > "$WORK/out"
grep -q "GRANT_ADDED: 2" "$WORK/out" || fail "list counts"
admin list --institutional-id SYN-V-2 > "$WORK/out"
grep -q "^grants: 1" "$WORK/out" || fail "list rows"
echo "ok  list"
echo "PASS: authz-admin integration test"
