"""Standalone inspection application API. No connections to other systems."""
import os
import uuid
from datetime import datetime, timezone
from typing import Any
from contextlib import asynccontextmanager

from fastapi import FastAPI, Header, HTTPException, Depends
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from sqlalchemy import create_engine, String, ForeignKey, Integer, DateTime, Text, select, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker, Session

DATABASE_URL = os.getenv('DATABASE_URL', 'sqlite:///./obhody.db')
API_TOKEN = os.getenv('API_TOKEN', 'dev-change-me')
engine = create_engine(DATABASE_URL, connect_args={'check_same_thread': False} if DATABASE_URL.startswith('sqlite') else {}, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine)

class Base(DeclarativeBase): pass
class Route(Base):
    __tablename__ = 'routes'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str] = mapped_column(Text, default='')
    is_active: Mapped[int] = mapped_column(Integer, default=1)
    checkpoints: Mapped[list['Checkpoint']] = relationship(cascade='all, delete-orphan', order_by='Checkpoint.position')
class Checkpoint(Base):
    __tablename__ = 'checkpoints'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    route_id: Mapped[str] = mapped_column(ForeignKey('routes.id'), index=True)
    position: Mapped[int] = mapped_column(Integer, default=0)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    questions: Mapped[list['Question']] = relationship(cascade='all, delete-orphan', order_by='Question.position')
class Question(Base):
    __tablename__ = 'questions'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    checkpoint_id: Mapped[str] = mapped_column(ForeignKey('checkpoints.id'), index=True)
    position: Mapped[int] = mapped_column(Integer, default=0)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    kind: Mapped[str] = mapped_column(String(20), nullable=False)
    required: Mapped[int] = mapped_column(Integer, default=1)
    options_json: Mapped[str] = mapped_column(Text, default='[]')
class Inspection(Base):
    __tablename__ = 'inspections'
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    route_id: Mapped[str] = mapped_column(String(36), index=True)
    route_snapshot: Mapped[str] = mapped_column(Text, nullable=False)
    performed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    operator: Mapped[str] = mapped_column(String(150), nullable=False)
    comment: Mapped[str] = mapped_column(Text, default='')
    answers_json: Mapped[str] = mapped_column(Text, nullable=False)
    received_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

@asynccontextmanager
async def lifespan(app: FastAPI):
    Base.metadata.create_all(engine)
    yield
app = FastAPI(title='Обходы API', version='0.2.0', lifespan=lifespan)

def authorized(x_api_key: str | None = Header(None)):
    if not API_TOKEN or x_api_key != API_TOKEN:
        raise HTTPException(status_code=401, detail='Неверный API-ключ')
def session():
    with SessionLocal() as db: yield db

class QuestionInput(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    kind: str = Field(pattern='^(status|text|number|choice)$')
    required: bool = True
    options: list[str] = Field(default_factory=list)
class CheckpointInput(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    questions: list[QuestionInput] = Field(min_length=1)
class RouteInput(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    description: str = ''
    checkpoints: list[CheckpointInput] = Field(min_length=1)

import json

def route_dict(route: Route):
    return {'id':route.id, 'name':route.name, 'description':route.description, 'checkpoints': [
        {'id':c.id, 'title':c.title, 'questions':[{'id':q.id, 'title':q.title, 'kind':q.kind, 'required':bool(q.required), 'options':json.loads(q.options_json)} for q in c.questions]}
        for c in route.checkpoints]}

@app.get('/health')
def health(): return {'status':'ok'}

@app.get('/')
def admin_page():
    return FileResponse(os.path.join(os.path.dirname(__file__), 'static','index.html'))

@app.get('/api/routes', dependencies=[Depends(authorized)])
def routes(db: Session = Depends(session)):
    return [route_dict(r) for r in db.scalars(select(Route).where(Route.is_active == 1).order_by(Route.name)).all()]

@app.post('/api/routes', status_code=201, dependencies=[Depends(authorized)])
def create_route(item: RouteInput, db: Session = Depends(session)):
    route = Route(id=str(uuid.uuid4()), name=item.name, description=item.description)
    for ci, c in enumerate(item.checkpoints):
        checkpoint = Checkpoint(id=str(uuid.uuid4()), title=c.title, position=ci)
        for qi, q in enumerate(c.questions):
            if q.kind == 'choice' and not q.options:
                raise HTTPException(422, 'Вопрос типа choice требует варианты ответа')
            checkpoint.questions.append(Question(id=str(uuid.uuid4()), title=q.title, kind=q.kind,
                required=int(q.required), options_json=json.dumps(q.options, ensure_ascii=False), position=qi))
        route.checkpoints.append(checkpoint)
    db.add(route); db.commit(); db.refresh(route)
    return route_dict(route)

@app.delete('/api/routes/{route_id}', dependencies=[Depends(authorized)])
def deactivate_route(route_id: str, db: Session = Depends(session)):
    route = db.get(Route, route_id)
    if not route: raise HTTPException(404, 'Маршрут не найден')
    route.is_active = 0
    db.commit()
    return {'ok':True}

class InspectionInput(BaseModel):
    id: uuid.UUID
    route_id: uuid.UUID
    route_snapshot: dict[str, Any]
    performed_at: datetime
    operator: str = Field(min_length=1, max_length=150)
    comment: str = ''
    answers: list[dict[str, Any]] = Field(min_length=1)

@app.post('/api/inspections/batch', dependencies=[Depends(authorized)])
def batch(items: list[InspectionInput], db: Session = Depends(session)):
    if len(items) > 100: raise HTTPException(413, 'Максимум 100 результатов за запрос')
    if len({str(i.id) for i in items}) != len(items): raise HTTPException(422, 'Повторяющиеся ID в запросе')
    saved = []
    for item in items:
        inspection_id = str(item.id)
        old = db.get(Inspection, inspection_id)
        if old is not None:
            if old.route_id != str(item.route_id):
                raise HTTPException(409, 'Конфликт идентификатора обхода')
            saved.append(inspection_id); continue
        db.add(Inspection(id=inspection_id, route_id=str(item.route_id), route_snapshot=json.dumps(item.route_snapshot,ensure_ascii=False),
            performed_at=item.performed_at, operator=item.operator, comment=item.comment,
            answers_json=json.dumps(item.answers, ensure_ascii=False)))
        saved.append(inspection_id)
    db.commit()
    return {'saved_ids': saved}

@app.get('/api/inspections', dependencies=[Depends(authorized)])
def inspections(db: Session = Depends(session)):
    rows = db.scalars(select(Inspection).order_by(Inspection.received_at.desc()).limit(500)).all()
    return [{'id':r.id, 'route_id':r.route_id, 'route':json.loads(r.route_snapshot).get('name',''),
             'operator':r.operator, 'performed_at':r.performed_at.isoformat(), 'comment':r.comment,
             'answers':json.loads(r.answers_json)} for r in rows]
