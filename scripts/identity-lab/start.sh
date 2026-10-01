#!/usr/bin/env bash
# Local SAML integration - Cognito simulated.
# Starts the identity lab (real Shibboleth IdP 5.2.3, fictional directory,
# simulated Cognito) and the archive API + UI against it. Everything binds to
# 127.0.0.1. Test keys and the persistent-ID salt are generated ONCE into
# .identity-lab/ and reused on every start; only reset.sh regenerates them.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"
mkdir -p "$STATE"
docker info >/dev/null 2>&1 || { echo "Docker is not running." >&2; exit 1; }

if [ ! -f "$CREDS/secrets.env" ]; then
  docker build -q -t identity-lab-cognito "$LAB/core/cognito" >/dev/null
  echo "Generating TEST keys, certificates and salt (first start) ..."
  mkdir -p "$CREDS"
  docker run --rm -v "$CREDS:/out" identity-lab-cognito sh -c \
    '/srv/gen-keys.sh /out && python /srv/render-metadata.py /out'
fi
if [ ! -f "$STATE/truststore.p12" ]; then
  keytool -importcert -noprompt -alias identity-lab-test-ca -file "$CREDS/ca.crt" \
    -keystore "$STATE/truststore.p12" -storetype PKCS12 -storepass changeit >/dev/null
fi
load_secrets
compose build -q
compose up -d

echo "Waiting for the archive database ..."
until docker exec lab-archive-db pg_isready -U lab_archive -d identity_lab >/dev/null 2>&1; do sleep 1; done
if [ "$(docker exec lab-archive-db psql -U lab_archive -d identity_lab -Atc "select count(*) from information_schema.schemata where schema_name='identity_lab'")" != "1" ]; then
  echo "Applying migrations, the synthetic seed and the lab tables ..."
  for f in $(ls "$ROOT"/database/migrations/V*.sql | sort -t V -k2 -n); do
    docker exec -i lab-archive-db psql -q -v ON_ERROR_STOP=1 -U lab_archive -d identity_lab < "$f" >/dev/null
  done
  docker exec -i lab-archive-db psql -q -v ON_ERROR_STOP=1 -U lab_archive -d identity_lab \
    < "$ROOT/api/src/test/resources/authz/synthetic-seed.sql"
  docker exec -i lab-archive-db psql -q -v ON_ERROR_STOP=1 -U lab_archive -d identity_lab \
    < "$LAB/archive/db/archive-lab.sql"
  docker exec -i lab-archive-db psql -q -v ON_ERROR_STOP=1 -U lab_archive -d identity_lab \
    < "$LAB/archive/db/award-acceptance-fixtures.sql"
  echo "Importing the KIM principal crosswalk with the production admin CLI (validated, audited) ..."
  F="$LAB/archive/fixtures"
  AUTHZ_ADMIN_DATABASE_URL="postgresql://lab_archive:$LAB_ARCHIVE_DB_PASSWORD@127.0.0.1:55433/identity_lab" \
    uv run -q --no-project --with 'psycopg[binary]' "$ROOT/scripts/authz-admin/authz_admin.py" crosswalk-import \
    "$F/kim_principals.tsv" "$F/principal_crosswalk.tsv" --attribute labInstitutionalId \
    --rolodex-ids "$F/rolodex_ids.txt" --load-ref lab-fixtures --actor lab-start
  # Simulate a later KIM refresh in which this (FICTIONAL) principal has departed.
  docker exec lab-archive-db psql -q -U lab_archive -d identity_lab \
    -c "UPDATE authz.kim_principal SET actv_ind = 'N' WHERE prncpl_id = 'SYNP-KIM-13'"
fi
python3 "$LAB/archive/fixtures/make_attachment_pdfs.py" "$STATE/attachments" >/dev/null

echo "Waiting for the Shibboleth IdP (first start can take a minute) ..."
for i in $(seq 1 180); do
  curl -sf -o /dev/null --cacert "$CREDS/ca.crt" https://localhost:8443/idp/shibboleth && break
  [ "$i" = 180 ] && { echo "IdP did not start; see: docker logs lab-idp" >&2; exit 1; }
  sleep 2
done
until curl -sf --cacert "$CREDS/ca.crt" https://localhost:9443/lab/health >/dev/null 2>&1; do sleep 1; done

if ! curl -sf "http://127.0.0.1:$API_PORT/actuator/health" >/dev/null 2>&1; then
  echo "Starting API (profile identity-lab, real JWT validation) on :$API_PORT ..."
  (cd "$ROOT/api" && exec env -u AWS_PROFILE -u AWS_DEFAULT_PROFILE -u AWS_SESSION_TOKEN LAB_ARCHIVE_DB_PASSWORD="$LAB_ARCHIVE_DB_PASSWORD" LAB_API_PORT="$API_PORT" LAB_UI_PORT="$UI_PORT" \
    LAB_ATTACHMENT_DIR="$STATE/attachments" ${LAB_POLICY_ENV:-} \
    AWS_ACCESS_KEY_ID=lab-not-a-real-key AWS_SECRET_ACCESS_KEY=lab-not-a-real-secret \
    mvn -B -ntp -q -Pauthz-demo spring-boot:run -Dspring-boot.run.profiles=identity-lab \
    "-Dspring-boot.run.jvmArguments=-Djavax.net.ssl.trustStore=$STATE/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12") \
    < /dev/null > "$STATE/api.log" 2>&1 &
  echo $! > "$STATE/api.pid"
  until curl -sf "http://127.0.0.1:$API_PORT/actuator/health" >/dev/null 2>&1; do
    kill -0 "$(cat "$STATE/api.pid")" 2>/dev/null || { echo "API failed; see $STATE/api.log" >&2; exit 1; }
    sleep 2
  done
fi

if ! curl -sf "http://localhost:$UI_PORT/" >/dev/null 2>&1; then
  echo "Starting UI (mode identity-lab) on :$UI_PORT ..."
  [ -d "$ROOT/ui/node_modules" ] || (cd "$ROOT/ui" && npm ci --silent)
  (cd "$ROOT/ui" && exec env VITE_API_BASE_URL="http://localhost:$API_PORT" VITE_AI_ENABLED=false \
    VITE_AWS_REGION=us-east-1 VITE_COGNITO_USER_POOL_ID=us-east-1_LabSimSaml \
    VITE_COGNITO_CLIENT_ID=labsimulatedclient VITE_COGNITO_DOMAIN=localhost:9443 \
    VITE_COGNITO_REDIRECT_URL="http://localhost:$UI_PORT/" VITE_COGNITO_LOGOUT_URL="http://localhost:$UI_PORT/" \
    VITE_LAB_USER_POOL_ENDPOINT=https://localhost:9443/ \
    npx vite --mode identity-lab --port "$UI_PORT" --strictPort) < /dev/null > "$STATE/ui.log" 2>&1 &
  echo $! > "$STATE/ui.pid"
  until curl -sf "http://localhost:$UI_PORT/" >/dev/null 2>&1; do sleep 1; done
fi

echo
echo "Local SAML integration - Cognito simulated"
echo "  UI:  http://localhost:$UI_PORT/awards/search?q=SYNTHETIC"
echo "  Sign in with a FICTIONAL account from identity-lab/archive/fixtures/users.tsv"
echo "  The browser will warn about the lab's TEST certificates (localhost:9443 and :8443)."
echo "  Stop: scripts/identity-lab/stop.sh   Reset everything: scripts/identity-lab/reset.sh"
