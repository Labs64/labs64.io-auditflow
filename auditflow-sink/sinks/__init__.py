"""
Labs64.IO AuditFlow - Sink Modules

Built-in sinks for processing and sending audit events to various destinations.

Available Sinks:
- logging_sink: Console logging for debugging
- opensearch_sink: OpenSearch/Elasticsearch integration
- loki_sink: Grafana Loki integration
- syslog_sink: Syslog server integration (RFC 3164)
- webhook_sink: HTTP webhooks (Zapier, Make, n8n, etc.)
- aws_s3_sink: Amazon S3 storage
- aws_cloudwatch_sink: AWS CloudWatch Logs
- aws_cloudtrail_sink: AWS CloudTrail Lake
- gcs_sink: Google Cloud Storage
- azure_blob_sink: Azure Blob Storage
"""

__version__ = "1.0.0"
__author__ = "Labs64"
__all__ = [
    "logging_sink",
    "opensearch_sink",
    "loki_sink",
    "syslog_sink",
    "webhook_sink",
    "aws_s3_sink",
    "aws_cloudwatch_sink",
    "aws_cloudtrail_sink",
    "gcs_sink",
    "azure_blob_sink",
]

