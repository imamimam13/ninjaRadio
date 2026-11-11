import socket
import struct
import threading
import time
import math
import random
import sys

# RTL_TCP Protocol Specifications
# Header: 4 bytes magic "RTL0" + 4 bytes tuner_type (5 = R820T) + 4 bytes gain_count (29)
RTL_HEADER = b"RTL0" + struct.pack(">II", 5, 29)

SAMPLE_RATE = 1024000  # 1.024 MS/s
BLOCK_SIZE = 16384     # 8192 IQ pairs per network packet
TABLE_SIZE = 65536     # Lookup table size

# Precompute sine/cosine lookup tables
SIN_TABLE = [math.sin(2.0 * math.pi * i / TABLE_SIZE) for i in range(TABLE_SIZE)]
COS_TABLE = [math.cos(2.0 * math.pi * i / TABLE_SIZE) for i in range(TABLE_SIZE)]

# Morse code map for CW Beacon
MORSE_MAP = {
    'A': '.-', 'B': '-...', 'C': '-.-.', 'D': '-..', 'E': '.', 'F': '..-.',
    'G': '--.', 'H': '....', 'I': '..', 'J': '.---', 'K': '-.-', 'L': '.-..',
    'M': '--', 'N': '-.', 'O': '---', 'P': '.--.', 'Q': '--.-', 'R': '.-.',
    'S': '...', 'T': '-', 'U': '..-', 'V': '...-', 'W': '.--', 'X': '-..-',
    'Y': '-.--', 'Z': '--..', '0': '-----', '1': '.----', '2': '..---',
    '3': '...--', '4': '....-', '5': '.....', '6': '-....', '7': '--...',
    '8': '---..', '9': '----.', ' ': ' '
}

def generate_morse_pattern(text: str, wpm: int = 15, sample_rate: int = SAMPLE_RATE):
    """Generate on/off boolean mask for CW beacon at given WPM"""
    dot_duration = 1.2 / wpm  # standard PARIS formula
    samples_per_dot = int(dot_duration * sample_rate)
    pattern = []
    
    for char in text.upper():
        if char == ' ':
            pattern.extend([False] * (samples_per_dot * 4))
            continue
        code = MORSE_MAP.get(char, '')
        for elem in code:
            if elem == '.':
                pattern.extend([True] * samples_per_dot)
            elif elem == '-':
                pattern.extend([True] * (samples_per_dot * 3))
            pattern.extend([False] * samples_per_dot)  # inter-element space
        pattern.extend([False] * (samples_per_dot * 2))  # inter-letter space
    
    pattern.extend([False] * (samples_per_dot * 7))  # inter-word space
    return pattern

print("[SDR-SIM] Pre-generating multi-carrier IQ buffer...")
BEACON_TEXT = "CQ CQ CQ DE BD62BOT NINJARADIO HF SIMULATOR K "
morse_pattern = generate_morse_pattern(BEACON_TEXT, wpm=16, sample_rate=SAMPLE_RATE)
morse_len = len(morse_pattern)

# We synthesize a 2-second looping IQ buffer (2 * 1024000 = 2,048,000 IQ pairs = 4,096,000 bytes)
# To keep memory light and startup fast, let's use 1 second buffer: 1,024,000 samples = 2,048,000 bytes.
NUM_SAMPLES = SAMPLE_RATE
iq_buffer = bytearray(NUM_SAMPLES * 2)

# Carriers relative to center frequency:
# Carrier 1: CW Morse Beacon at +30 kHz
F_CW = 30000.0
# Carrier 2: Two-Tone USB test signal at -35 kHz (tones at +700Hz and +1900Hz)
F_USB_CARRIER = -35000.0
F_TONE1 = F_USB_CARRIER + 700.0
F_TONE2 = F_USB_CARRIER + 1900.0
# Carrier 3: AM modulated tone at +80 kHz (audio tone 1000 Hz)
F_AM = 80000.0
# Carrier 4: Continuous Pilot Carrier at -80 kHz
F_PILOT = -80000.0

dt = 1.0 / SAMPLE_RATE

# Phase accumulators
cw_phase = 0.0
cw_step = F_CW * TABLE_SIZE / SAMPLE_RATE

tone1_phase = 0.0
tone1_step = F_TONE1 * TABLE_SIZE / SAMPLE_RATE
tone2_phase = 0.0
tone2_step = F_TONE2 * TABLE_SIZE / SAMPLE_RATE

am_phase = 0.0
am_step = F_AM * TABLE_SIZE / SAMPLE_RATE

pilot_phase = 0.0
pilot_step = F_PILOT * TABLE_SIZE / SAMPLE_RATE

t_cur = 0.0
for n in range(NUM_SAMPLES):
    # Base noise floor: Gaussian-like jitter around 127.5
    noise_i = random.uniform(-4.0, 4.0)
    noise_q = random.uniform(-4.0, 4.0)
    
    # 1. CW Beacon (+30 kHz) with Morse keying
    morse_on = morse_pattern[n % morse_len]
    cw_amp = 35.0 if morse_on else 0.0
    cw_idx = int(cw_phase) % TABLE_SIZE
    sig_i = cw_amp * COS_TABLE[cw_idx]
    sig_q = cw_amp * SIN_TABLE[cw_idx]
    
    # 2. Two-Tone USB Signal (-35 kHz)
    t1_idx = int(tone1_phase) % TABLE_SIZE
    t2_idx = int(tone2_phase) % TABLE_SIZE
    sig_i += 18.0 * (COS_TABLE[t1_idx] + COS_TABLE[t2_idx])
    sig_q += 18.0 * (SIN_TABLE[t1_idx] + SIN_TABLE[t2_idx])
    
    # 3. AM Signal (+80 kHz) with 1kHz modulation
    mod_1k = 0.6 + 0.4 * math.sin(2.0 * math.pi * 1000.0 * t_cur)
    am_idx = int(am_phase) % TABLE_SIZE
    sig_i += 25.0 * mod_1k * COS_TABLE[am_idx]
    sig_q += 25.0 * mod_1k * SIN_TABLE[am_idx]
    
    # 4. Continuous Pilot (-80 kHz)
    p_idx = int(pilot_phase) % TABLE_SIZE
    sig_i += 20.0 * COS_TABLE[p_idx]
    sig_q += 20.0 * SIN_TABLE[p_idx]
    
    # Final 8-bit unsigned sample (0..255, center 128)
    val_i = int(127.5 + noise_i + sig_i)
    val_q = int(127.5 + noise_q + sig_q)
    
    # Clamp to [0..255]
    if val_i < 0: val_i = 0
    elif val_i > 255: val_i = 255
    if val_q < 0: val_q = 0
    elif val_q > 255: val_q = 255
    
    iq_buffer[2 * n] = val_i
    iq_buffer[2 * n + 1] = val_q
    
    cw_phase += cw_step
    tone1_phase += tone1_step
    tone2_phase += tone2_step
    am_phase += am_step
    pilot_phase += pilot_step
    t_cur += dt

print(f"[SDR-SIM] IQ buffer initialized ({len(iq_buffer)} bytes, 1.0s loop). Ready!")

class RtlTcpClientHandler(threading.Thread):
    def __init__(self, conn, addr):
        super().__init__(daemon=True)
        self.conn = conn
        self.addr = addr
        self.running = True
        self.freq = 7100000
        self.sample_rate = SAMPLE_RATE

    def run(self):
        print(f"[SDR-SIM] Client connected from {self.addr}")
        try:
            # 1. Send RTL0 Header
            self.conn.sendall(RTL_HEADER)
            
            # Start reader thread for commands
            cmd_thread = threading.Thread(target=self._command_reader, daemon=True)
            cmd_thread.start()
            
            # 2. Stream IQ blocks at realistic rate
            buf_len = len(iq_buffer)
            offset = 0
            chunk_size = BLOCK_SIZE
            samples_per_chunk = chunk_size // 2
            interval = samples_per_chunk / self.sample_rate
            
            next_time = time.time()
            while self.running:
                now = time.time()
                if now < next_time:
                    sleep_time = next_time - now
                    if sleep_time > 0.0005:
                        time.sleep(sleep_time)
                
                # Slice chunk
                if offset + chunk_size <= buf_len:
                    chunk = iq_buffer[offset:offset+chunk_size]
                    offset += chunk_size
                else:
                    part1 = iq_buffer[offset:]
                    rem = chunk_size - len(part1)
                    part2 = iq_buffer[:rem]
                    chunk = part1 + part2
                    offset = rem
                
                self.conn.sendall(chunk)
                next_time += interval
                
        except (BrokenPipeError, ConnectionResetError, OSError) as e:
            print(f"[SDR-SIM] Client {self.addr} disconnected: {e}")
        finally:
            self.running = False
            try:
                self.conn.close()
            except Exception:
                pass
            print(f"[SDR-SIM] Session closed for {self.addr}")

    def _command_reader(self):
        while self.running:
            try:
                data = self.conn.recv(5)
                if not data or len(data) < 5:
                    break
                cmd, val = struct.unpack(">BI", data)
                if cmd == 0x01:
                    self.freq = val
                    print(f"[SDR-SIM] Freq tuned to {val / 1e6:.3f} MHz")
                elif cmd == 0x02:
                    self.sample_rate = val
                    print(f"[SDR-SIM] Sample rate set to {val} S/s")
                elif cmd == 0x04:
                    print(f"[SDR-SIM] Gain set to {val}")
            except Exception:
                break
        self.running = False

def start_server(host="0.0.0.0", port=1234):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(5)
    print(f"[SDR-SIM] RTL-TCP Simulator listening on {host}:{port}")
    
    while True:
        try:
            conn, addr = server.accept()
            handler = RtlTcpClientHandler(conn, addr)
            handler.start()
        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"[SDR-SIM] Accept error: {e}")
            time.sleep(1)

if __name__ == "__main__":
    start_server()
