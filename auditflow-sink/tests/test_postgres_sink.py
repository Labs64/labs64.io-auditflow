import pytest
from unittest.mock import patch, MagicMock
import psycopg
from sinks import postgres_sink

def test_missing_properties():
    properties = {"host": "localhost"}
    with pytest.raises(ValueError, match="Missing required properties: database, user, password, table"):
        postgres_sink.process({}, properties)

@patch('psycopg.connect')
def test_successful_insert(mock_connect):
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    
    # Setup mock cursor context manager
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_connect.return_value = mock_conn

    properties = {
        "host": "localhost",
        "database": "audit_db",
        "user": "admin",
        "password": "password",
        "table": "audit_events"
    }
    
    event_data = {"eventId": "123", "tenantId": "test"}

    result = postgres_sink.process(event_data, properties)

    assert result["sent"] is True
    assert result["destination"] == "postgres"
    
    mock_connect.assert_called_once_with(
        host="localhost", port="5432", dbname="audit_db", user="admin", password="password"
    )
    
    mock_cursor.execute.assert_called_once()
    mock_conn.commit.assert_called_once()
    mock_conn.close.assert_called_once()

@patch('psycopg.connect')
def test_database_error(mock_connect):
    mock_conn = MagicMock()
    mock_cursor = MagicMock()
    mock_conn.cursor.return_value.__enter__.return_value = mock_cursor
    mock_connect.return_value = mock_conn
    
    # Simulate DB error
    mock_cursor.execute.side_effect = psycopg.Error("Connection lost")

    properties = {
        "host": "localhost",
        "database": "audit_db",
        "user": "admin",
        "password": "password",
        "table": "audit_events"
    }

    with pytest.raises(RuntimeError, match="Database error: Connection lost"):
        postgres_sink.process({"test": "data"}, properties)
        
    mock_conn.rollback.assert_called_once()
    mock_conn.close.assert_called_once()
