using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using TS3AudioBot;
using TS3AudioBot.Plugins;
using TSLib;
using TSLib.Audio;
using TSLib.Audio.Opus;
using TSLib.Full;
using TSLib.Full.Book;

public class NinjaRadioBridge : IBotPlugin, IAudioPassiveConsumer
{
    private static readonly NLog.Logger Log = NLog.LogManager.GetCurrentClassLogger();

    // Port definitions
    private const int TeamspeakBridgePort = 12352;
    private const int CoreIngressPort = 32100;
    private static readonly string CoreHost = Environment.GetEnvironmentVariable("CORE_HOST") ?? "172.18.0.8";

    // Shared UDP Socket for incoming audio from Core Engine (DMR -> TS3)
    private static Socket rxSocket;
    private static Thread rxThread;
    private static volatile bool rxRunning = false;
    private static readonly ConcurrentDictionary<int, NinjaRadioBridge> instancesByTg = new ConcurrentDictionary<int, NinjaRadioBridge>();
    private static int instanceCount = 0;
    private static readonly object lockObj = new object();

    // Bot specific properties
    private readonly TsFullClient fullClient;
    private readonly Ts3Client ts3Client;
    private readonly Bot bot;
    private int targetTg = 1025;
    private int targetSlot = 2;
    private string botName = "";
    private string defaultBotName = "TS3-GW-BRIDISV";

    // Callsign Carry-Over & Nickname Morphing State
    private string currentMorphedCall = null;
    private DateTime lastRxAudioTime = DateTime.MinValue;
    private DateTime lastRxLogTime = DateTime.MinValue;

    // Opus Decoder & Encoder
    private OpusDecoder decoder;
    private OpusEncoder encoder;
    private readonly byte[] decodeBuffer = new byte[1920];

    // UDP Client for sending audio to Core Engine (TS3 -> DMR)
    private Socket txSocket;
    private IPEndPoint coreEndPoint;

    // VOX State & Hysteresis Guardrails (GEMINI.md Section 7.3)
    private volatile bool isVoiceActive = false;
    private DateTime lastVoiceTime = DateTime.MinValue;
    private DateTime txStartTime = DateTime.MinValue;
    private string currentTalkerCall = "";
    private uint currentTalkerDmrId = 1022057;
    private Timer hangoverTimer;
    private readonly byte[] pcmBuffer = new byte[320];
    private int pcmBufferOffset = 0;

    public bool Active => true;

    public NinjaRadioBridge(TsFullClient fullClient, Ts3Client ts3Client, Bot bot)
    {
        this.fullClient = fullClient;
        this.ts3Client = ts3Client;
        this.bot = bot;
    }

    public void Initialize()
    {
        botName = bot.Name ?? bot.Id.ToString();
        ResolveBotTarget(botName);

        if (targetTg == 1025) defaultBotName = "TS3-GW-BRIDISV";
        else if (targetTg == 510990) defaultBotName = "TS3-GW-NINJA";
        else if (targetTg == 510) defaultBotName = "TS3-GW-NASIONAL";
        else if (targetTg == 5109) defaultBotName = "TS3-GW-NUSANTARA";
        else defaultBotName = (bot.Name ?? bot.Id.ToString()).ToUpper();

        // Ensure bot is set to clean default name initially
        try { _ = ts3Client.ChangeName(defaultBotName); } catch { }

        decoder = OpusDecoder.Create(8000, 1);
        encoder = OpusEncoder.Create(8000, 1, Application.Voip);
        encoder.Bitrate = 24000;

        txSocket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        IPAddress coreIp;
        if (!IPAddress.TryParse(CoreHost, out coreIp))
        {
            try
            {
                var addresses = Dns.GetHostAddresses(CoreHost);
                coreIp = addresses.Length > 0 ? addresses[0] : IPAddress.Loopback;
            }
            catch
            {
                coreIp = IPAddress.Loopback;
            }
        }
        coreEndPoint = new IPEndPoint(coreIp, CoreIngressPort);

        hangoverTimer = new Timer(OnHangoverTick, null, 100, 100);

        fullClient.OutStream = this;
        instancesByTg[targetTg] = this;

        lock (lockObj)
        {
            instanceCount++;
            if (rxSocket == null)
            {
                StartSharedRxListener();
            }
        }

        Console.WriteLine($"[NinjaRadioBridge] ✓ Bot '{botName}' initialized for TG {targetTg} (TS{targetSlot}) -> Core {coreEndPoint}");
    }

    private void ResolveBotTarget(string name)
    {
        string lower = name.ToLower();
        if (lower.Contains("bridisv") || lower.Contains("1025"))
        {
            targetTg = 1025;
            targetSlot = 2;
        }
        else if (lower.Contains("ninja") || lower.Contains("510990"))
        {
            targetTg = 510990;
            targetSlot = 2;
        }
        else if (lower.Contains("nasional") || lower.Contains("510"))
        {
            targetTg = 510;
            targetSlot = 1;
        }
        else if (lower.Contains("nusantara") || lower.Contains("5109"))
        {
            targetTg = 5109;
            targetSlot = 2;
        }
    }

    private static void StartSharedRxListener()
    {
        try
        {
            rxSocket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            rxSocket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            rxSocket.Bind(new IPEndPoint(IPAddress.Any, TeamspeakBridgePort));
            rxRunning = true;
            rxThread = new Thread(RxListenerLoop) { IsBackground = true, Name = "TS3Bridge-RxListener" };
            rxThread.Start();
            Console.WriteLine($"[NinjaRadioBridge] ✓ Shared DMR RX Listener active on UDP port {TeamspeakBridgePort}");
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[NinjaRadioBridge] ❌ Failed to bind RX port {TeamspeakBridgePort}: {ex.Message}");
        }
    }

    private static void RxListenerLoop()
    {
        byte[] buffer = new byte[2048];
        while (rxRunning)
        {
            try
            {
                int len = rxSocket.Receive(buffer);
                if (len >= 32 && buffer[0] == (byte)'U' && buffer[1] == (byte)'S' && buffer[2] == (byte)'R' && buffer[3] == (byte)'P')
                {
                    // USRP Header: [0..3]=USRP, [4..7]=seq, [8..11]=0, [12..15]=key_flag, [16..19]=tg, [20..23]=dmr_id, [24..27]=slot, [28..31]=0
                    uint keyFlag = BinaryPrimitives.ReadUInt32BigEndian(new ReadOnlySpan<byte>(buffer, 12, 4));
                    uint tg = BinaryPrimitives.ReadUInt32BigEndian(new ReadOnlySpan<byte>(buffer, 16, 4));
                    uint dmrId = BinaryPrimitives.ReadUInt32BigEndian(new ReadOnlySpan<byte>(buffer, 20, 4));
                    uint slot = BinaryPrimitives.ReadUInt32BigEndian(new ReadOnlySpan<byte>(buffer, 24, 4));

                    string callerCall = "";
                    int pcmOffset = 32;
                    if (len >= 368)
                    {
                        callerCall = Encoding.ASCII.GetString(buffer, 32, 16).TrimEnd('\0').Trim();
                        pcmOffset = 48;
                    }
                    if (string.IsNullOrEmpty(callerCall) && dmrId > 0)
                    {
                        callerCall = $"ID-{dmrId}";
                    }

                    int payloadLen = len - pcmOffset;
                    if (payloadLen <= 0) continue;

                    byte[] pcmPayload = new byte[payloadLen];
                    Array.Copy(buffer, pcmOffset, pcmPayload, 0, payloadLen);

                    if (instancesByTg.TryGetValue((int)tg, out var botInstance))
                    {
                        botInstance.PlayRxAudio(pcmPayload, keyFlag == 1, (int)dmrId, callerCall);
                    }
                }
            }
            catch
            {
                if (!rxRunning) break;
                Thread.Sleep(10);
            }
        }
    }

    public void PlayRxAudio(byte[] pcm8k, bool isKeyed, int srcDmrId, string callsign)
    {
        if (fullClient == null) return;
        try
        {
            if (isKeyed)
            {
                lastRxAudioTime = DateTime.UtcNow;

                // Callsign Carry-Over: dynamically morph bot nickname to active caller
                if (!string.IsNullOrEmpty(callsign) && currentMorphedCall != callsign)
                {
                    currentMorphedCall = callsign;
                    string newName = $"[DMR] {callsign}";
                    if (newName.Length > 25) newName = newName.Substring(0, 25);
                    try
                    {
                        _ = ts3Client.ChangeName(newName).ContinueWith(t =>
                        {
                            if (t.IsFaulted)
                            {
                                Console.WriteLine($"[NinjaRadioBridge] ⚠️ ChangeName to '{newName}' failed: {t.Exception?.GetBaseException().Message}");
                            }
                        });
                        Console.WriteLine($"[NinjaRadioBridge] 🏷️ Bot '{defaultBotName}' morphed to '{newName}'");
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[NinjaRadioBridge] ⚠️ Error morphing name: {ex.Message}");
                    }
                }

                if (pcm8k.Length > 0)
                {
                    if ((DateTime.UtcNow - lastRxLogTime).TotalSeconds > 3.0)
                    {
                        lastRxLogTime = DateTime.UtcNow;
                        Console.WriteLine($"[NinjaRadioBridge] 🔊 Playing DMR RX on TG {targetTg} from {callsign} ({srcDmrId}) into TS3");
                    }
                    byte[] pcmToEncode = pcm8k;
                    if (pcmToEncode.Length < 320)
                    {
                        pcmToEncode = new byte[320];
                        Array.Copy(pcm8k, 0, pcmToEncode, 0, pcm8k.Length);
                    }
                    Span<byte> opusOut = stackalloc byte[500];
                    var encoded = encoder.Encode(pcmToEncode, 320, opusOut);
                    if (encoded.Length > 0)
                    {
                        fullClient.SendAudio(encoded, Codec.OpusVoice);
                    }
                }
            }
            else
            {
                // Unkey: Revert bot nickname back to standard gateway identity
                if (currentMorphedCall != null)
                {
                    currentMorphedCall = null;
                    try
                    {
                        _ = ts3Client.ChangeName(defaultBotName).ContinueWith(t =>
                        {
                            if (t.IsFaulted)
                            {
                                Console.WriteLine($"[NinjaRadioBridge] ⚠️ Revert to '{defaultBotName}' failed: {t.Exception?.GetBaseException().Message}");
                            }
                        });
                        Console.WriteLine($"[NinjaRadioBridge] 🏷️ Bot reverted to '{defaultBotName}'");
                    }
                    catch (Exception ex)
                    {
                        Console.WriteLine($"[NinjaRadioBridge] ⚠️ Error reverting name: {ex.Message}");
                    }
                }
            }
        }
        catch
        {
            // Ignore audio transient errors
        }
    }

    // Direction TS3 -> DMR: Received audio from TeamSpeak channel client
    public void Write(Span<byte> data, Meta meta)
    {
        if (data.Length < 6) return;

        var senderId = (ClientId)BinaryPrimitives.ReadUInt16BigEndian(data.Slice(2, 2));
        if (senderId == fullClient.ClientId) return; // Self echo suppression

        string senderName = "TS3-USER";
        if (fullClient.Book != null && fullClient.Book.Clients != null && fullClient.Book.Clients.TryGetValue(senderId, out var cl))
        {
            senderName = cl.Name;
        }

        var opusPayload = data.Slice(5);
        if (opusPayload.Length == 0) return;

        Span<byte> pcmChunk;
        try
        {
            pcmChunk = decoder.Decode(opusPayload, decodeBuffer);
        }
        catch
        {
            return;
        }

        if (pcmChunk.Length == 0) return;

        // Calculate RMS Energy for VOX Hysteresis
        long sumSq = 0;
        int sampleCount = pcmChunk.Length / 2;
        for (int i = 0; i < pcmChunk.Length; i += 2)
        {
            short sample = BinaryPrimitives.ReadInt16LittleEndian(pcmChunk.Slice(i, 2));
            sumSq += (long)sample * sample;
        }
        double rms = Math.Sqrt(sumSq / (double)sampleCount);

        DateTime now = DateTime.UtcNow;

        // VOX Schmitt Trigger: Attack >= 90.0, Release < 60.0
        if (!isVoiceActive && rms >= 90.0)
        {
            isVoiceActive = true;
            txStartTime = now;
            lastVoiceTime = now;
            ResolveSenderIdentity(senderName);
            Console.WriteLine($"[NinjaRadioBridge] 🎙️ TX Key-Up: {currentTalkerCall} ({currentTalkerDmrId}) -> TG {targetTg} Slot {targetSlot} (RMS: {rms:F1})");
        }
        else if (isVoiceActive)
        {
            if (rms >= 60.0)
            {
                lastVoiceTime = now;
            }
        }

        if (isVoiceActive)
        {
            // Hard Time-Out Timer (TOT): 180 seconds max
            if ((now - txStartTime).TotalSeconds > 180)
            {
                Console.WriteLine($"[NinjaRadioBridge] ⚠️ Hard TOT Expired for {currentTalkerCall} on TG {targetTg}");
                SendCorePacket(false, Array.Empty<byte>());
                isVoiceActive = false;
                return;
            }

            // Buffer into exact 320-byte (20ms) PCM chunks for Core Engine
            for (int i = 0; i < pcmChunk.Length; i++)
            {
                pcmBuffer[pcmBufferOffset++] = pcmChunk[i];
                if (pcmBufferOffset >= 320)
                {
                    SendCorePacket(true, pcmBuffer);
                    pcmBufferOffset = 0;
                }
            }
        }
    }

    private void OnHangoverTick(object state)
    {
        DateTime now = DateTime.UtcNow;

        // 1. Hangover Timer: 450ms silence threshold before unkeying
        if (isVoiceActive && (now - lastVoiceTime).TotalMilliseconds >= 450)
        {
            isVoiceActive = false;
            pcmBufferOffset = 0;
            Console.WriteLine($"[NinjaRadioBridge] ⏹️ TX Unkey: {currentTalkerCall} ({currentTalkerDmrId}) -> TG {targetTg}");
            SendCorePacket(false, Array.Empty<byte>());
        }

        // 2. RX Callsign Carry-Over Watchdog: Revert nickname if no RX packet received for 1200ms
        if (currentMorphedCall != null && (now - lastRxAudioTime).TotalMilliseconds >= 1200)
        {
            currentMorphedCall = null;
            try
            {
                _ = ts3Client.ChangeName(defaultBotName).ContinueWith(t =>
                {
                    if (t.IsFaulted)
                    {
                        Console.WriteLine($"[NinjaRadioBridge] ⚠️ Watchdog revert to '{defaultBotName}' failed: {t.Exception?.GetBaseException().Message}");
                    }
                });
            }
            catch { }
            Console.WriteLine($"[NinjaRadioBridge] ⏱️ RX timeout: reverted to '{defaultBotName}'");
        }
    }

    private void SendCorePacket(bool isTx, byte[] pcmChunk)
    {
        try
        {
            // Format: magic(4B) + dmr_id(4B) + tg(4B) + slot(4B) + pcm_len(4B) + is_tx(1B) + callsign(16B) + pcm_payload
            byte[] packet = new byte[37 + pcmChunk.Length];
            // Magic b"NTS3"
            packet[0] = (byte)'N'; packet[1] = (byte)'T'; packet[2] = (byte)'S'; packet[3] = (byte)'3';
            BinaryPrimitives.WriteUInt32BigEndian(new Span<byte>(packet, 4, 4), currentTalkerDmrId);
            BinaryPrimitives.WriteUInt32BigEndian(new Span<byte>(packet, 8, 4), (uint)targetTg);
            BinaryPrimitives.WriteUInt32BigEndian(new Span<byte>(packet, 12, 4), (uint)targetSlot);
            BinaryPrimitives.WriteUInt32BigEndian(new Span<byte>(packet, 16, 4), (uint)pcmChunk.Length);
            packet[20] = isTx ? (byte)1 : (byte)0;

            byte[] callBytes = Encoding.ASCII.GetBytes(currentTalkerCall.PadRight(16, '\0'));
            Array.Copy(callBytes, 0, packet, 21, 16);

            if (pcmChunk.Length > 0)
            {
                Array.Copy(pcmChunk, 0, packet, 37, pcmChunk.Length);
            }

            txSocket.SendTo(packet, coreEndPoint);
        }
        catch (Exception ex)
        {
            Log.Debug(ex, "Error sending packet to Core Engine");
        }
    }

    private void ResolveSenderIdentity(string rawName)
    {
        string clean = rawName.Trim();
        var idMatch = Regex.Match(clean, @"\b(\d{7})\b");
        if (idMatch.Success)
        {
            currentTalkerDmrId = uint.Parse(idMatch.Groups[1].Value);
        }
        else
        {
            currentTalkerDmrId = 1022057; // Default official operator profile DMR ID
        }

        var callMatch = Regex.Match(clean, @"[A-Za-z0-9]{3,10}");
        if (callMatch.Success)
        {
            currentTalkerCall = callMatch.Value.ToUpper();
        }
        else
        {
            currentTalkerCall = "BD62BOT";
        }
    }

    public void Dispose()
    {
        if (fullClient != null && fullClient.OutStream == this)
        {
            fullClient.OutStream = null;
        }

        hangoverTimer?.Dispose();
        decoder?.Dispose();
        encoder?.Dispose();
        txSocket?.Dispose();

        instancesByTg.TryRemove(targetTg, out _);

        if (currentMorphedCall != null)
        {
            try { _ = ts3Client.ChangeName(defaultBotName); } catch { }
        }

        lock (lockObj)
        {
            instanceCount--;
            if (instanceCount <= 0)
            {
                rxRunning = false;
                try { rxSocket?.Close(); } catch { }
                rxSocket = null;
            }
        }

        Console.WriteLine($"[NinjaRadioBridge] Bot '{botName}' disposed.");
    }
}
