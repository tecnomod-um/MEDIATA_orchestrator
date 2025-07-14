# MEDIATA Central Orchestrator

This is the central backend service of the MEDIATA platform. It handles user authentication via Kerberos, manages node registration, and orchestrates harmonization tasks.

## Features

- Kerberos-based user authentication (KDC-hosted)
- Node registration and heartbeat monitoring
- Centralized orchestration of semantic alignment and mapping pipelines
- API endpoints for:
    - Mapping rule generation (YARRRML, RDF)
    - HL7 FHIR clustering and profile generation
    - Node and user management
    - Log collection and monitoring

This project is developed under the [MIT License](LICENSE.md).
