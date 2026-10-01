# Local SAML integration - Cognito simulated. A SIMULATION of the Cognito user
# pool's SAML federation and hosted-UI endpoints, for the local lab only.
FROM python:3.12-slim-bookworm@sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e
RUN apt-get update && apt-get install -y --no-install-recommends xmlsec1 openssl \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt /srv/requirements.txt
RUN pip install --no-cache-dir -r /srv/requirements.txt
COPY app/ /srv/app/
COPY gen-keys.sh render-metadata.py schema.sql /srv/
WORKDIR /srv
EXPOSE 9443
ENV PYTHONPATH=/srv/app:/lab/hooks
CMD ["gunicorn", "--chdir", "/srv/app", "--workers", "1", "--threads", "8", \
     "--bind", "0.0.0.0:9443", "--certfile", "/lab/creds/cognito-tls.crt", \
     "--keyfile", "/lab/creds/cognito-tls.key", "--access-logfile", "-", "server:app"]
