from pydantic_settings import BaseSettings
from typing import List
import os

class Settings(BaseSettings):
    PROJECT_NAME: str = "NinjaRadio Server"
    VERSION: str = "1.0.0"
    API_V1_STR: str = "/api"
    
    # Server & DMR Identities
    SERVER_CALLSIGN: str = "N1NJA"
    SERVER_DMR_ID: int = 5109999
    DEFAULT_TALKGROUP: int = 510
    
    # Network Ports
    DMR_MASTER_PORT: int = 62031
    HBLINK_PEER_PORT: int = 62030
    USRP_AUDIO_PORT: int = 12345
    
    # Database
    DATABASE_URL: str = "sqlite+aiosqlite:///./ninjaradio.db"
    
    # CORS
    CORS_ORIGINS: List[str] = ["*"]
    
    class Config:
        case_sensitive = True
        env_file = ".env"

settings = Settings()
