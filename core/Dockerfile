FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# DMR Master UDP (62031), OpenBridge UDP (62030), USRP UDP (32345), Core Control API (5000)
EXPOSE 62031/udp 62030/udp 32345/udp 5000/tcp

CMD ["python", "core_engine.py"]
