#!/usr/bin/env bash
# Automated checks for record authorization (fixture-based; real BU
# federation and identity mapping NOT VERIFIED). Uses Testcontainers for the
# database tests, so Docker must be running. Does not need the demo running.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/api"
mvn -B -ntp -q test -Dtest='IdentityResolverTest,AccessScopeResolverTest,RecordAccessEvaluatorTest,RecordAuthorizationGateTest,AccessNotProvisionedProblemTest,SyntheticFederationTest,AccessTokenValidationTest,RecordScopeSqlBuilderTest,RecordAuthorizationInterceptorTest,JwtCurrentIdentityProviderTest,DemoClassesAbsentFromDefaultBuildTest,AuthorizationStoreIntegrationTest,RecordAuthorizationEnforcementIntegrationTest'
echo "API authorization tests passed."
cd "$ROOT/ui"
npm run -s test
npm run -s build >/dev/null
if grep -rIl -e 'Synthetic identity demo' -e 'demo-persona:' dist/; then
  echo "Demo code leaked into the production UI build" >&2; exit 1
fi
echo "UI tests passed; production build contains no demo code."
