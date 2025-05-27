<p align="center"><img src="https://raw.githubusercontent.com/Labs64/.github/refs/heads/master/assets/labs64-io-ecosystem.png"></p>

# Labs64.IO - AuditFlow: A Scalable & Searchable Microservices-based Auditing Solution

AuditFlow is a robust and scalable microservices-based auditing solution engineered to capture, process, and store all inbound and outbound traffic from your RESTful APIs. Designed to seamlessly integrate with APIs consuming both XML and JSON payloads, AuditFlow provides a decoupled, resilient, and highly searchable mechanism for logging critical operational data, enabling compliance, security monitoring, and business intelligence.

By offloading the auditing responsibility from core services, AuditFlow ensures minimal impact on the systems' performance and enhanced reliability for audit data capture.

## Key Features

* **API Agnostic Request/Response Capture:** Intercepts and logs full request and response payloads, regardless of whether they are XML or JSON, including headers, body, method, path, and query parameters.
* **Asynchronous Processing:** Utilizes message queues (e.g., Kafka, RabbitMQ) to decouple auditing from core API logic, ensuring high availability and low latency for your primary services.
* **Intelligent Data Processing:**
    * **Content Type Detection:** Automatically identifies and parses XML or JSON data.
    * **Data Normalization:** Transforms diverse request/response structures into a consistent, searchable format.
    * **Sensitive Data Redaction:** Configurable redaction rules to mask or remove Personally Identifiable Information (PII), payment data (PCI), or other sensitive fields before storage, enhancing data privacy and compliance.
* **Flexible Storage Destinations:** Supports multiple pluggable storage backends tailored for different use cases:
    * **Real-time Search & Analytics:** Integrates with systems like Elasticsearch for near real-time, full-text search, powerful aggregations, and dashboarding (e.g., Kibana).
    * **Structured Transactional Audits:** Option to store critical, structured audit trails in relational databases (e.g., PostgreSQL, MySQL) ensuring ACID compliance.
    * **Cost-Effective Long-term Archival:** Leverages cloud object storage (e.g., AWS S3, Azure Data Lake) with formats like Parquet or Avro for scalable, cost-efficient, and immutable historical data retention.
    * **Tamper-Proof Records (Optional):** Integration with Blockchain/DLT or WORM storage for highly sensitive, cryptographically verifiable audit trails.
* **High Searchability:** Designed for efficient querying and analysis of audit data across various dimensions (e.g., by user ID, API endpoint, timestamp, transaction ID, HTTP status code, error messages).
* **Scalability & Resilience:** Architected as a set of independent microservices, allowing for individual scaling of components to handle varying load requirements and ensuring system resilience against component failures.
* **Extensibility:** Modular design allows for easy integration of new data processing logic, storage destinations, or analytical tools as requirements evolve.
* **Compliance Ready:** Facilitates meeting regulatory compliance requirements by providing a verifiable, secure, and comprehensive audit trail of API interactions.

## Architecture Highlights

![labs64-io-auditflow-architecture](https://github.com/user-attachments/assets/ca5f0c0e-81dc-439d-a855-95ebc2fc50ed)

The AuditFlow architecture is built around several decoupled microservices:

1.  **API Gateway / Reverse Proxy:** Intercepts all traffic, enriches with metadata (e.g., unique request ID, timestamp), and publishes raw request/response pairs to the auditing queue.
2.  **Auditing Queue:** Acts as a reliable, asynchronous buffer for audit events, ensuring no data loss and decoupling the API from the auditing process.
3.  **Audit Processing Microservice:** Consumes from the queue, parses, normalizes, redacts, and transforms audit events. It then dispatches the processed data to the appropriate storage systems.
4.  **Storage Dispatcher:** (Often integrated within the Audit Processing Service or a dedicated component) Routes audit events to specific destinations based on configured rules.
5.  **Search & Analytics Microservice:** Provides interfaces and tools for querying, analyzing, and visualizing the stored audit data.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Labs64/labs64.io-auditflow&type=Date)](https://www.star-history.com/#Labs64/labs64.io-auditflow&Date)
