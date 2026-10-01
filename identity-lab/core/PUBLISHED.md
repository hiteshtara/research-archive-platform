# Identity lab core: published images (private Docker Hub)

**What these images are:** the reusable, application-neutral core of the local SAML lab.
They are labelled "Local SAML integration—Cognito simulated".

- Every account is fictional.
- **No key material is baked into any image.** The IdP's bundled keys are deleted at
  build time. Each lab generates its own test keys at first start, into a local directory
  you control.

| Image | Contents |
|---|---|
| `mukadder/identity-lab:idp-<v>` | Shibboleth IdP 5.2.3 (InCommon TAP base, pinned by digest), lab configuration |
| `mukadder/identity-lab:ldap-<v>` | OpenLDAP for fictional accounts (no accounts baked in) |
| `mukadder/identity-lab:cognito-<v>` | simulated Cognito user pool (SAML SP, OAuth2/PKCE, tokens) + key generator |
| `mukadder/identity-lab:proxy-<v>` | socat relay from 127.0.0.1 into the internal-only network |

The digests of each published version are recorded in HANDOFF.md.

## Pull and start (core only, with the example account)

```
docker login -u mukadder                     # you sign in; the repository is private
export LAB_VERSION=0.1.0
mkdir -p ~/identity-lab-creds
docker run --rm -v ~/identity-lab-creds:/out mukadder/identity-lab:cognito-$LAB_VERSION \
  sh -c '/srv/gen-keys.sh /out && python /srv/render-metadata.py /out'   # TEST keys, once
set -a; . ~/identity-lab-creds/secrets.env; set +a
export LAB_CREDS_DIR=~/identity-lab-creds
docker compose -p identity-lab -f compose.published.yml --project-directory .. pull
docker compose -p identity-lab -f compose.published.yml --project-directory .. up -d
open https://localhost:9443/lab/health          # simulated Cognito
open https://localhost:8443/idp/shibboleth      # IdP metadata
```

- **Stop:** `docker compose -p identity-lab -f compose.published.yml --project-directory .. down`
- **Full reset:** add `-v` to that command, and delete `~/identity-lab-creds`.

The archive integration (fictional archive accounts, enrollment, API and UI) uses
`scripts/identity-lab/start.sh` from the repository, which builds the same images locally.
