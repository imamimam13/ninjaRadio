export interface SvxlinkConfigParams {
  nodeType: "simplex" | "repeater";
  callsign: string;
  defaultTg: string;
  monitoredTgs: string;
  authKey: string;
  host: string;
  echoLink: boolean;
  echoCall: string;
  echoPass: string;
  timeout: string;
}

export function generateSvxlinkConf(params: SvxlinkConfigParams): string {
  const host = params.host || "43.173.13.117";
  const logicName = params.nodeType === "repeater" ? "RepeaterLogic" : "SimplexLogic";
  const logicType = params.nodeType === "repeater" ? "Repeater" : "Simplex";
  const cleanCall = params.callsign.trim().toUpperCase() || "YB0NINJA";
  const elModule = params.echoLink ? "MODULES=ModuleEchoLink\n" : "";
  const elConfig = params.echoLink && params.echoCall.trim() ? `
[ModuleEchoLink]
NAME=EchoLink
ID=2
TIMEOUT=300
SERVERS=servers.echolink.org
CALLSIGN=${params.echoCall.trim().toUpperCase()}
PASSWORD=${params.echoPass.trim()}
SYSOPNAME=${cleanCall} Gateway
LOCATION=NinjaRadio Reflector TG ${params.defaultTg}
MAX_QSOS=10
MAX_CONNECTIONS=11
LINK_IDLE_TIMEOUT=300
DESCRIPTION="Connected to NinjaRadio Reflector"
` : "";

  return `###############################################################################
# SvxLink Node Configuration for NinjaRadio Multi-TG Reflector
# Logic: ${logicName} <--> ReflectorLogic (Jembatan ReflectorLink)
# Generated automatically by NinjaRadio NOC System
###############################################################################

[GLOBAL]
LOGICS=${logicName},ReflectorLogic
LINKS=ReflectorLink
TIMESTAMP_FORMAT="%Y-%m-%d %H:%M:%S"
CARD_SAMPLE_RATE=16000
CARD_CHANNELS=1

[${logicName}]
TYPE=${logicType}
RX=Rx1
TX=Tx1
${elModule}CALLSIGN=${cleanCall}
SHORT_IDENT_INTERVAL=10
LONG_IDENT_INTERVAL=60
IDENT_ONLY_AFTER_TX=4
DEFAULT_LANG=en_US
RGR_SOUND_DELAY=250
REPORT_CTCSS=0
TX_TIMEOUT=${params.timeout}

[ReflectorLogic]
TYPE=Reflector
HOSTS=${host}
HOST_PORT=5300
AUTH_KEY="${params.authKey.trim() || "ninjaradio_svx"}"
CALLSIGN="${cleanCall}"
DEFAULT_TG=${params.defaultTg.trim() || "510"}
MONITOR_TGS=${params.monitoredTgs.trim() || "510"}
TG_SELECT_TIMEOUT=60
ANNOUNCE_REMOTE_TALKERS=1

[ReflectorLink]
CONNECT_LOGICS=${logicName}:999:Reflector,ReflectorLogic
DEFAULT_ACTIVE=1

[Rx1]
TYPE=Local
AUDIO_DEV=alsa:plughw:0
AUDIO_CHANNEL=0
SQL_DET=VOX
SQL_START_DELAY=0
SQL_DELAY=0
SQL_HANGTIME=400
VOX_FILTER_DEPTH=20
VOX_THRESH=1000
DEEMPHASIS=0

[Tx1]
TYPE=Local
AUDIO_DEV=alsa:plughw:0
AUDIO_CHANNEL=0
PTT_TYPE=NONE
TIMEOUT=${params.timeout}
PREEMPHASIS=0
${elConfig}`;
}
