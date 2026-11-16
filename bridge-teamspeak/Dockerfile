FROM ancieque/ts3audiobot:latest

USER root
RUN apk add --no-cache python3 bash su-exec

WORKDIR /app
COPY sync_bots.py /app/sync_bots.py
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/bin/bash", "/app/entrypoint.sh"]
