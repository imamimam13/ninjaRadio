FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    svxreflector \
    ca-certificates \
    curl \
    sqlite3 \
    python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY svxreflector.conf /etc/svxlink/svxreflector.conf
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 5300/tcp
EXPOSE 5300/udp
EXPOSE 8080/tcp

ENTRYPOINT ["/app/entrypoint.sh"]
