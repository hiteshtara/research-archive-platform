"""Writes the two SAML metadata files the lab's IdP and SP trust (local files, no federation)."""
import sys
from pathlib import Path

d = Path(sys.argv[1])

def cert(name):
    return "".join(l for l in (d / f"{name}.crt").read_text().splitlines() if "CERTIFICATE" not in l)

def key(use, name):
    return (f'<md:KeyDescriptor use="{use}"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>'
            f"{cert(name)}</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>")

NS = 'xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" xmlns:ds="http://www.w3.org/2000/09/xmldsig#"'
P = "urn:oasis:names:tc:SAML:2.0:protocol"
FMT = ("<md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:persistent</md:NameIDFormat>"
       "<md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:transient</md:NameIDFormat>")
RED = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"

(d / "idp-metadata.xml").write_text(f"""<?xml version="1.0"?>
<!-- Local SAML integration - Cognito simulated: lab IdP (TEST keys) -->
<md:EntityDescriptor {NS} entityID="https://idp.lab.invalid/idp/shibboleth">
<md:IDPSSODescriptor protocolSupportEnumeration="{P}">
{key("signing", "idp-signing")}
{key("encryption", "idp-encryption")}
<md:SingleLogoutService Binding="{RED}" Location="https://localhost:8443/idp/profile/SAML2/Redirect/SLO"/>
{FMT}
<md:SingleSignOnService Binding="{RED}" Location="https://localhost:8443/idp/profile/SAML2/Redirect/SSO"/>
</md:IDPSSODescriptor>
</md:EntityDescriptor>
""")

(d / "sp-metadata.xml").write_text(f"""<?xml version="1.0"?>
<!-- Local SAML integration - Cognito simulated: simulated Cognito SP (TEST keys) -->
<md:EntityDescriptor {NS} entityID="urn:lab:simulated-cognito:sp:us-east-1_LabSimSaml">
<md:SPSSODescriptor AuthnRequestsSigned="true" WantAssertionsSigned="true" protocolSupportEnumeration="{P}">
{key("signing", "sp-signing")}
{key("encryption", "sp-encryption")}
<md:SingleLogoutService Binding="{RED}" Location="https://localhost:9443/saml2/logout"/>
{FMT}
<md:AssertionConsumerService Binding="{POST}" Location="https://localhost:9443/saml2/idpresponse" index="1"/>
</md:SPSSODescriptor>
</md:EntityDescriptor>
""")
print("metadata written")
