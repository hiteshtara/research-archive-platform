# Local SAML integration - Cognito simulated.
# Real Shibboleth IdP 5.2.3 (InCommon TAP image, pinned). Sessions are kept
# server-side in memory (single node), which SAML single logout needs to find
# a session by NameID. The lab replaces every
# key the image ships with per-run test keys at start-up (lab-entrypoint.sh).
# Pinned by digest (multi-arch index) - the tag alone is mutable.
FROM i2incommon/shib-idp:5.2.3_20260618_rocky10_multiarch@sha256:ff03ee2e9ded82b10fa4d6f4af8f27f03e9a6aab3b88157750ca00d3e2a5c35c

# Opt out of the TAP usage beacon (sendtierbeacon.sh would POST image name and
# version to collector.testbed.tier.internet2.edu:5001 daily). Sealer rotation
# (rotateSealerKey.sh) stays at the image default: it regenerates keys locally
# and only scp-syncs to idp.sealer._sync_hosts, which defaults to this host and
# is skipped. Network isolation is NOT assumed from these settings: the IdP sits
# on an internal-only Docker network (core/compose.yml) and the lab tests it.
ENV TIER_BEACON_OPT_OUT=true \
    JAVA_OPTS=-Xmx768m

COPY conf/ /opt/shibboleth-idp/conf/
COPY views/logout.vm /opt/shibboleth-idp/views/logout.vm
COPY lab-entrypoint.sh /usr/bin/lab-entrypoint.sh

RUN set -e; cd /opt/shibboleth-idp/conf; \
    sed -i -e 's|^idp.entityID=.*|idp.entityID=https://idp.lab.invalid/idp/shibboleth|' \
           -e 's|^idp.scope=.*|idp.scope=lab.invalid|' \
           -e 's|^idp.storage.htmlLocalStorage=.*|idp.storage.htmlLocalStorage=false|' \
           -e 's|^#idp.session.StorageService = .*|idp.session.StorageService = shibboleth.StorageService|' idp.properties; \
    grep -q '^idp.session.StorageService = shibboleth.StorageService' idp.properties; \
    sed -i -e 's|^#\?idp.authn.LDAP.authenticator .*|idp.authn.LDAP.authenticator = bindSearchAuthenticator|' \
           -e 's|^#\?idp.authn.LDAP.useStartTLS .*|idp.authn.LDAP.useStartTLS = false|' \
           -e 's|^idp.authn.LDAP.ldapURL=.*|idp.authn.LDAP.ldapURL=ldap://lab-ldap:389|' \
           -e 's|^idp.authn.LDAP.baseDN=.*|idp.authn.LDAP.baseDN=ou=people,dc=lab,dc=invalid|' \
           -e 's|^idp.authn.LDAP.bindDN=.*|idp.authn.LDAP.bindDN=cn=idp-reader,dc=lab,dc=invalid|' \
           -e 's|^idp.authn.LDAP.dnFormat=.*|idp.authn.LDAP.dnFormat=uid=%s,ou=people,dc=lab,dc=invalid|' \
           -e '/^idp.authn.LDAP.trustCertificates=/d' -e '/^idp.authn.LDAP.trustStore=/d' ldap.properties; \
    sed -i -e 's|^#idp.persistentId.sourceAttribute = .*|idp.persistentId.sourceAttribute = labInstitutionalId|' saml-nameid.properties; \
    sed -i '/Uncommenting this bean requires/{n;N;N;s|<!--\n\(.*SAML2PersistentGenerator.*\)\n *-->|\1|}' saml-nameid.xml; \
    ! grep -B1 'shibboleth.SAML2PersistentGenerator" />' saml-nameid.xml | grep -q '<!--$'; \
    chmod +x /usr/bin/lab-entrypoint.sh

CMD ["/usr/bin/lab-entrypoint.sh"]
