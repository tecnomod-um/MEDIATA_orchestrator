# MEDIATA Central Orchestrator

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F?style=flat&logo=spring-boot&logoColor=white)](#)
[![CI](https://github.com/tecnomod-um/MEDIATA_orchestrator/actions/workflows/ci_testing.yml/badge.svg?branch=main)](https://github.com/tecnomod-um/MEDIATA_orchestrator/actions/workflows/ci_testing.yml)
[![Coverage](./badges/coverage.svg)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE.md)

This repository contains the **central backend orchestrator** of the MEDIATA platform.  
For the complete platform (front end, back end, orchestration, and docs), see **[tecnomod-um/MEDIATA_project](https://github.com/tecnomod-um/MEDIATA_project)**.

## Overview

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
