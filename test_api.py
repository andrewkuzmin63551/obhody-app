import os
import tempfile
from pathlib import Path

# Database URL must be set before importing app.
tmp = tempfile.TemporaryDirectory()
os.environ['DATABASE_URL'] = 'sqlite:///' + str(Path(tmp.name) / 'test.db')
os.environ['API_TOKEN'] = 'unit-test-token'
from fastapi.testclient import TestClient
from app import app, Base, engine


def test_routes_and_idempotent_batch():
    with TestClient(app) as client:
        headers = {'X-API-Key': 'unit-test-token'}
        assert client.get('/api/routes').status_code == 401
        payload = {'name':'Склад', 'checkpoints':[{'title':'Ворота', 'questions':[{'title':'Замок исправен?', 'kind':'status'}]}]}
        r = client.post('/api/routes', json=payload, headers=headers)
        assert r.status_code == 201, r.text
        route = r.json()
        assert len(client.get('/api/routes',headers=headers).json()) == 1
        import uuid
        item = {'id':str(uuid.uuid4()),'route_id':route['id'],'route_snapshot':route,'performed_at':'2026-11-05T12:00:00Z','operator':'Тестовый сотрудник','answers':[{'question_id':route['checkpoints'][0]['questions'][0]['id'], 'value':'Норма'}]}
        for _ in range(2):
            res = client.post('/api/inspections/batch',json=[item],headers=headers)
            assert res.status_code == 200, res.text
            assert res.json()['saved_ids'] == [item['id']]
        assert len(client.get('/api/inspections', headers=headers).json()) == 1
        assert client.delete('/api/routes/' + route['id'], headers=headers).status_code == 200
        assert client.get('/api/routes', headers=headers).json() == []
        assert len(client.get('/api/inspections',headers=headers).json()) == 1
