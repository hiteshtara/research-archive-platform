# Local SAML integration - Cognito simulated: TCP relay from 127.0.0.1 on the
# host into the internal-only lab network. The relays are the only lab
# containers attached to a network with a route out; they run socat only.
FROM alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
RUN apk add --no-cache socat
ENTRYPOINT ["sh", "-c", "exec socat TCP-LISTEN:${LISTEN_PORT},fork,reuseaddr TCP:${TARGET}"]
