from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from typing import List
from backend.app.core.database import BridgeRoute
from backend.app.models.schemas import BridgeRouteCreate

INITIAL_ROUTES = [
    {"name": "DMR 510 <-> YSF Indo Room", "source_mode": "DMR", "source_tg": 510, "source_slot": 1, "target_mode": "YSF", "target_tg": 51000, "target_slot": 1, "is_active": True, "bidirectional": True},
    {"name": "DMR 5101 <-> D-STAR XLX510 B", "source_mode": "DMR", "source_tg": 5101, "source_slot": 2, "target_mode": "D-STAR", "target_tg": 51012, "target_slot": 1, "is_active": True, "bidirectional": True},
    {"name": "DMR 5109 <-> Analog AllStarLink 59123", "source_mode": "DMR", "source_tg": 5109, "source_slot": 1, "target_mode": "ANALOG", "target_tg": 59123, "target_slot": 1, "is_active": True, "bidirectional": True},
    {"name": "DMR 5108 <-> CB 11M Digital Gateway", "source_mode": "DMR", "source_tg": 5108, "source_slot": 1, "target_mode": "CB", "target_tg": 11000, "target_slot": 1, "is_active": True, "bidirectional": True},
    {"name": "DMR 5100 <-> P25 TG 10200 Rescue", "source_mode": "DMR", "source_tg": 5100, "source_slot": 1, "target_mode": "P25", "target_tg": 10200, "target_slot": 1, "is_active": True, "bidirectional": True},
]

class BridgeService:
    @staticmethod
    async def seed_initial_routes(session: AsyncSession):
        stmt = select(BridgeRoute).limit(1)
        result = await session.execute(stmt)
        if not result.scalar_one_or_none():
            for item in INITIAL_ROUTES:
                entry = BridgeRoute(**item)
                session.add(entry)
            await session.commit()

    @staticmethod
    async def get_routes(session: AsyncSession) -> List[BridgeRoute]:
        stmt = select(BridgeRoute).order_by(BridgeRoute.id)
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def create_route(session: AsyncSession, data: BridgeRouteCreate) -> BridgeRoute:
        entry = BridgeRoute(**data.model_dump())
        session.add(entry)
        await session.commit()
        await session.refresh(entry)
        return entry

    @staticmethod
    async def toggle_route(session: AsyncSession, route_id: int) -> BridgeRoute:
        stmt = select(BridgeRoute).where(BridgeRoute.id == route_id)
        result = await session.execute(stmt)
        entry = result.scalar_one_or_none()
        if entry:
            entry.is_active = not entry.is_active
            await session.commit()
            await session.refresh(entry)
        return entry
