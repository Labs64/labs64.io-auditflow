import pytest
import requests
from unittest.mock import patch, MagicMock
from sinks import webhook_sink

def test_missing_webhook_url():
    properties = {}
    with pytest.raises(ValueError, match="Missing required property: 'webhook-url'"):
        webhook_sink.process({}, properties)

@patch('requests.post')
def test_successful_post(mock_post):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.elapsed.total_seconds.return_value = 0.05
    mock_response.text = "Success"
    mock_post.return_value = mock_response

    event_data = {"test": "data"}
    properties = {"webhook-url": "http://example.com/webhook"}

    result = webhook_sink.process(event_data, properties)

    assert result["sent"] is True
    assert result["status_code"] == 200
    mock_post.assert_called_once()

@patch('requests.get')
def test_successful_get(mock_get):
    mock_response = MagicMock()
    mock_response.status_code = 200
    mock_response.elapsed.total_seconds.return_value = 0.05
    mock_response.text = "Success"
    mock_get.return_value = mock_response

    event_data = {"test": "data"}
    properties = {
        "webhook-url": "http://example.com/webhook",
        "method": "GET"
    }

    result = webhook_sink.process(event_data, properties)

    assert result["sent"] is True
    assert result["status_code"] == 200
    mock_get.assert_called_once()

@patch('requests.post')
def test_retries_on_failure(mock_post):
    # Setup mock to fail twice, then succeed
    error_mock = requests.exceptions.RequestException("Connection error")
    success_mock = MagicMock()
    success_mock.status_code = 200
    success_mock.elapsed.total_seconds.return_value = 0.05
    success_mock.text = "Success"
    
    mock_post.side_effect = [error_mock, error_mock, success_mock]

    event_data = {"test": "data"}
    properties = {"webhook-url": "http://example.com/webhook", "retry-count": "3"}

    with patch('time.sleep'):  # don't actually sleep in tests
        result = webhook_sink.process(event_data, properties)

    assert result["sent"] is True
    assert result["attempt"] == 3
    assert mock_post.call_count == 3

@patch('requests.post')
def test_fails_after_retries(mock_post):
    error_mock = requests.exceptions.RequestException("Connection error")
    mock_post.side_effect = [error_mock, error_mock, error_mock]

    event_data = {"test": "data"}
    properties = {"webhook-url": "http://example.com/webhook", "retry-count": "3"}

    with patch('time.sleep'):
        with pytest.raises(RuntimeError, match="Failed to send webhook"):
            webhook_sink.process(event_data, properties)
