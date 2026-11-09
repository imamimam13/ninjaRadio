FROM python:3.11-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    svxlink-server \
    gcc \
    libopus-dev \
    libasound2-dev \
    build-essential \
    curl \
    procps \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ambe_dsp.py .
COPY bridge_svxlink.py .

EXPOSE 12348/udp
EXPOSE 12349/tcp

CMD ["python", "-u", "bridge_svxlink.py"]
