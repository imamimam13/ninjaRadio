FROM ubuntu:20.04

ENV DEBIAN_FRONTEND=noninteractive
ENV LD_LIBRARY_PATH=/app

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates procps net-tools && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY . /app

RUN chmod +x /app/ts3server \
    /app/AccountingServerEmulator-Linux \
    /app/entrypoint.sh && \
    mkdir -p /app/logs /app/files

EXPOSE 9987/udp 10011/tcp 30033/tcp

ENTRYPOINT ["/bin/bash", "/app/entrypoint.sh"]
