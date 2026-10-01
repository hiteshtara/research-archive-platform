# Local SAML integration - Cognito simulated: a directory for FICTIONAL test accounts only.
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache openldap openldap-back-mdb openldap-clients
COPY slapd.conf /etc/openldap/slapd.conf
# Accounts are NOT baked in: an application mounts its fixture at /lab/users.tsv.
COPY entrypoint.sh /lab/
RUN chmod +x /lab/entrypoint.sh
EXPOSE 389
ENTRYPOINT ["/lab/entrypoint.sh"]
