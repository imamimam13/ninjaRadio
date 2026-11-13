#!/usr/bin/env python3
"""
Surgical patch for SvxReflector (Reflector.h / Reflector.cpp) to support
Gateway Talker Delegation (/set_talker) so real DMR/Web/Zello/DVSwitch
callsigns carry over natively into SvxReflector & QSOLink via MsgTalkerStart.
"""
import sys
from pathlib import Path

REFLECTOR_DIR = Path("/src/svxlink/src/svxlink/reflector")
H_PATH = REFLECTOR_DIR / "Reflector.h"
CPP_PATH = REFLECTOR_DIR / "Reflector.cpp"


def patch_header():
    text = H_PATH.read_text(encoding="utf-8")
    if "m_talker_override" in text:
        print("Reflector.h already patched.")
        return

    inc_target = "#include <string>"
    if inc_target not in text:
        raise RuntimeError("Could not find '#include <string>' in Reflector.h")
    text = text.replace(inc_target, "#include <string>\n#include <map>", 1)

    status_target = "    Json::Value                 m_status;"
    if status_target not in text:
        raise RuntimeError("Could not find 'Json::Value m_status;' in Reflector.h")
    text = text.replace(
        status_target,
        status_target
        + "\n    std::map<std::string, std::string> m_talker_override;"
        + "\n    std::map<std::string, std::string> m_active_talker_call;",
        1,
    )
    H_PATH.write_text(text, encoding="utf-8")
    print("✓ Patched Reflector.h")


def patch_cpp():
    text = CPP_PATH.read_text(encoding="utf-8")
    if "/set_talker" in text:
        print("Reflector.cpp already patched.")
        return

    # 1. Patch onTalkerUpdated (old_talker stop)
    old_stop_block = """  if (old_talker != 0)
  {
    cout << old_talker->callsign() << ": Talker stop on TG #" << tg << endl;
    old_talker->updateIsTalker();
    broadcastMsg(MsgTalkerStop(tg, old_talker->callsign()),
        ReflectorClient::mkAndFilter(
          ge_v2_client_filter,
          ReflectorClient::mkOrFilter(
            ReflectorClient::TgFilter(tg),
            ReflectorClient::TgMonitorFilter(tg))));
    if (tg == tgForV1Clients())
    {
      broadcastMsg(MsgTalkerStopV1(old_talker->callsign()), v1_client_filter);
    }"""

    new_stop_block = """  if (old_talker != 0)
  {
    std::string stop_call = old_talker->callsign();
    auto it_stop = m_active_talker_call.find(old_talker->callsign());
    if (it_stop != m_active_talker_call.end() && !it_stop->second.empty())
    {
      stop_call = it_stop->second;
    }
    m_active_talker_call.erase(old_talker->callsign());
    cout << stop_call << ": Talker stop on TG #" << tg << endl;
    old_talker->updateIsTalker();
    broadcastMsg(MsgTalkerStop(tg, stop_call),
        ReflectorClient::mkAndFilter(
          ge_v2_client_filter,
          ReflectorClient::mkOrFilter(
            ReflectorClient::TgFilter(tg),
            ReflectorClient::TgMonitorFilter(tg))));
    if (tg == tgForV1Clients())
    {
      broadcastMsg(MsgTalkerStopV1(stop_call), v1_client_filter);
    }"""

    if old_stop_block not in text:
        raise RuntimeError("Could not find old_stop_block in Reflector.cpp")
    text = text.replace(old_stop_block, new_stop_block, 1)

    # 2. Patch onTalkerUpdated (new_talker start)
    old_start_block = """  if (new_talker != 0)
  {
    cout << new_talker->callsign() << ": Talker start on TG #" << tg << endl;
    new_talker->updateIsTalker();
    broadcastMsg(MsgTalkerStart(tg, new_talker->callsign()),
        ReflectorClient::mkAndFilter(
          ge_v2_client_filter,
          ReflectorClient::mkOrFilter(
            ReflectorClient::TgFilter(tg),
            ReflectorClient::TgMonitorFilter(tg))));
    if (tg == tgForV1Clients())
    {
      broadcastMsg(MsgTalkerStartV1(new_talker->callsign()), v1_client_filter);
    }
  }"""

    new_start_block = """  if (new_talker != 0)
  {
    std::string start_call = new_talker->callsign();
    auto it_ovr = m_talker_override.find(new_talker->callsign());
    if (it_ovr != m_talker_override.end() && !it_ovr->second.empty())
    {
      start_call = it_ovr->second;
    }
    m_active_talker_call[new_talker->callsign()] = start_call;
    cout << start_call << ": Talker start on TG #" << tg << endl;
    new_talker->updateIsTalker();
    broadcastMsg(MsgTalkerStart(tg, start_call),
        ReflectorClient::mkAndFilter(
          ge_v2_client_filter,
          ReflectorClient::mkOrFilter(
            ReflectorClient::TgFilter(tg),
            ReflectorClient::TgMonitorFilter(tg))));
    if (tg == tgForV1Clients())
    {
      broadcastMsg(MsgTalkerStartV1(start_call), v1_client_filter);
    }
  }"""

    if old_start_block not in text:
        raise RuntimeError("Could not find old_start_block in Reflector.cpp")
    text = text.replace(old_start_block, new_start_block, 1)

    # 3. Patch httpRequestReceived to accept /set_talker
    http_target = """  if (req.target != "/status")
  {"""

    http_replacement = r"""  if (req.target.rfind("/set_talker", 0) == 0)
  {
    auto get_param = [&](const std::string& key) -> std::string {
      std::string q = req.target;
      size_t qpos = q.find('?');
      if (qpos == std::string::npos) return "";
      std::string query = q.substr(qpos + 1);
      std::istringstream qss(query);
      std::string pair;
      while (std::getline(qss, pair, '&'))
      {
        size_t eq = pair.find('=');
        if (eq != std::string::npos && pair.substr(0, eq) == key)
        {
          return pair.substr(eq + 1);
        }
      }
      return "";
    };
    auto sanitize_call = [&](const std::string& raw, size_t max_len) -> std::string {
      std::string out;
      for (char c : raw)
      {
        if (std::isalnum(static_cast<unsigned char>(c)) || c == '-' || c == '/')
        {
          out += static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
          if (out.size() >= max_len) break;
        }
      }
      return out;
    };

    std::string node = sanitize_call(get_param("node"), 32);
    if (node.empty()) node = "SVX-GATEWAY";
    std::string call = sanitize_call(get_param("call"), 16);
    uint32_t tg = 0;
    std::string tg_str = get_param("tg");
    if (!tg_str.empty())
    {
      try { tg = static_cast<uint32_t>(std::stoul(tg_str)); } catch (...) { tg = 0; }
    }

    if (call.empty())
    {
      m_talker_override.erase(node);
    }
    else
    {
      m_talker_override[node] = call;
      auto it_act = m_active_talker_call.find(node);
      if (it_act != m_active_talker_call.end() && it_act->second != call && tg > 0)
      {
        std::string prev_call = it_act->second;
        it_act->second = call;
        broadcastMsg(MsgTalkerStop(tg, prev_call),
            ReflectorClient::mkAndFilter(
              ge_v2_client_filter,
              ReflectorClient::mkOrFilter(
                ReflectorClient::TgFilter(tg),
                ReflectorClient::TgMonitorFilter(tg))));
        if (tg == tgForV1Clients())
        {
          broadcastMsg(MsgTalkerStopV1(prev_call), v1_client_filter);
        }
        broadcastMsg(MsgTalkerStart(tg, call),
            ReflectorClient::mkAndFilter(
              ge_v2_client_filter,
              ReflectorClient::mkOrFilter(
                ReflectorClient::TgFilter(tg),
                ReflectorClient::TgMonitorFilter(tg))));
        if (tg == tgForV1Clients())
        {
          broadcastMsg(MsgTalkerStartV1(call), v1_client_filter);
        }
      }
    }

    res.setContent("application/json", "{\"status\":\"ok\"}");
    res.setSendContent(req.method == "GET");
    res.setCode(200);
    con->write(res);
    return;
  }

  if (req.target != "/status")
  {"""

    if http_target not in text:
        raise RuntimeError("Could not find http_target in Reflector.cpp")
    text = text.replace(http_target, http_replacement, 1)

    # 4. Allow CGNAT / mobile roaming UDP source IP & port changes for authenticated clients
    old_udp_ip_check = """    if (addr != client->remoteHost())
    {
      cerr << "*** WARNING[" << client->callsign()
           << "]: Incoming UDP packet has the wrong source ip, "
           << addr << " instead of " << client->remoteHost() << endl;
      return;
    }"""

    if old_udp_ip_check not in text:
        raise RuntimeError("Could not find old_udp_ip_check in Reflector.cpp")
    text = text.replace(old_udp_ip_check, "    // CGNAT-friendly: allow authenticated client UDP from roaming/CGNAT IP", 1)

    old_udp_port_check = """  if (client->remoteUdpPort() == 0)
  {
    client->setRemoteUdpSource(std::make_pair(addr, port));
    client->sendUdpMsg(MsgUdpHeartbeat());
  }
  if (port != client->remoteUdpPort())
  {
    cerr << "*** WARNING[" << client->callsign()
         << "]: Incoming UDP packet has the wrong source UDP "
            "port number, " << port << " instead of "
         << client->remoteUdpPort() << endl;
    return;
  }"""

    new_udp_port_check = """  if (client->remoteUdpPort() == 0)
  {
    client->setRemoteUdpSource(std::make_pair(addr, port));
    client->sendUdpMsg(MsgUdpHeartbeat());
  }
  else if (addr != client->remoteUdpHost() || port != client->remoteUdpPort())
  {
    cout << client->callsign() << ": Updating UDP source (CGNAT/Roaming) to "
         << addr << ":" << port << endl;
    client->setRemoteUdpSource(std::make_pair(addr, port));
  }"""

    if old_udp_port_check not in text:
        raise RuntimeError("Could not find old_udp_port_check in Reflector.cpp")
    text = text.replace(old_udp_port_check, new_udp_port_check, 1)

    CPP_PATH.write_text(text, encoding="utf-8")
    print("✓ Patched Reflector.cpp")


if __name__ == "__main__":
    patch_header()
    patch_cpp()
