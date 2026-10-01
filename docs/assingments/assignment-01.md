# Assignment 01 — Feature Flag Management

## Overview

Implemented and tested the basic feature flag management flow for Switchly.

## API Flow

The following flow was implemented and verified:

1. Create an organization.
2. Create a project inside the organization.
3. Create a feature flag inside the project.
4. Enable/disable the feature flag.
5. Retrieve the feature flag and verify its current state.

## API Endpoints Tested

### Create Organization

```bash
POST /api/v1/orgs
```

Example request:

```bash
curl -X POST http://localhost:8080/api/v1/orgs \
  -H "Content-Type: application/json" \
  -d '{"name":"Zomato"}'
```

### Create Project

```bash
POST /api/v1/orgs/{orgId}/projects
```

### Create Feature Flag

```bash
POST /api/v1/projects/{projectId}/flags
```

Example payload:

```json
{
  "key": "new-checkout",
  "name": "New Checkout"
}
```

The flag is created with:

```json
{
  "enabled": false
}
```

### Update Flag State

```bash
PUT /api/v1/flags/{flagId}/state
```

Example payload:

```json
{
  "enabled": true
}
```

### Get Feature Flag

```bash
GET /api/v1/flags/{flagId}
```

## Verification

The complete flow was successfully tested against the local Spring Boot server running on port `8080`.

Final verification confirmed:

```json
{
  "key": "new-checkout",
  "name": "New Checkout",
  "enabled": true
}
```

## Test Result

- Organization creation — Passed
- Project creation — Passed
- Feature flag creation — Passed
- Flag state update — Passed
- Flag state retrieval — Passed

**Assignment 01: Completed and verified successfully.**
