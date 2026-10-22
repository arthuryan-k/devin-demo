import pytest
from fastapi.testclient import TestClient

from risk.api import create_app

pytest_plugins = ["portfolio.testing"]


@pytest.fixture
def client(pool):
    with TestClient(create_app(pool)) as c:
        yield c
